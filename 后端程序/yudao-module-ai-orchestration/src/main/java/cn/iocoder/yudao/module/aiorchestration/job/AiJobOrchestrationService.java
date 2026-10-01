package cn.iocoder.yudao.module.aiorchestration.job;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.aiorchestration.enums.ErrorCodeConstants.AI_JOB_NOT_CANCELLABLE;

/**
 * AI 任务编排（架构 §6.4 / 蓝图 P4B）
 *
 * 合同：
 * - Runtime 主动 claim：FOR UPDATE SKIP LOCKED + 租约过期回收 + 每次领取 fencing_token 递增；
 *   旧 fencing token 的任何回写被拒绝（fencing 只证明持有租约，不参与结果去重）；
 * - 结果事件先落 durable inbox（(provider_code, source_event_id) 全局唯一），
 *   再进隔离结果；Core 校验器在事务外校验对象前缀/大小/Hash/内容，短事务 CAS 固化 ACCEPTED/REJECTED；
 * - 取消与结果到达共用单调 decision_seq：cancel_seq 裁决先后，received_seq > cancel_seq 的事件只留诊断（SUPERSEDED）；
 *   终态不可改；
 * - 输出只允许位于任务隔离前缀（ai-quarantine/{jobId}/）内。
 */
@Slf4j
@Service
public class AiJobOrchestrationService {
    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort accountStatePort;

    public enum ReportOutcome {QUARANTINED, DUPLICATE_EVENT, STALE_FENCING, SUPERSEDED, TERMINAL_IGNORED}

    public record ClaimedJob(long jobId, int attemptNo, long fencingToken, String phase,
                             int requestedCount, String outputPrefix) {
    }

    public record JobSnapshot(long jobId, long userId, String phase, String status, int requestedCount,
                              int acceptedCount, int progress, String cancelState, String projectRef,
                              Long unitPointCost, Long totalPointCost, Long refundedPointCost,
                              java.time.Instant createdAt, java.time.Instant finishedAt,
                              String resolution, String orientation) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final ReliableEventPort reliableEventPort;

    private final QuarantineObjectPort quarantineObjectPort;

    private final ContentScanPort contentScanPort;

    public AiJobOrchestrationService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                     ReliableEventPort reliableEventPort, QuarantineObjectPort quarantineObjectPort,
                                     ContentScanPort contentScanPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.reliableEventPort = reliableEventPort;
        this.quarantineObjectPort = quarantineObjectPort;
        this.contentScanPort = contentScanPort;
    }

    // ========== 任务创建 / 查询 ==========

    public boolean freezeInput(long jobId, Map<String,Object> snapshot) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("AI_INPUT_REQUIRES_TRANSACTION");
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(snapshot);
            if (jdbcTemplate.update("UPDATE ai_job SET input_snapshot=CAST(? AS jsonb) WHERE id=? AND input_snapshot IS NULL AND status='QUEUED'",
                    json,jobId)==1) return true;
            String existing = jdbcTemplate.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, jobId);
            if (existing != null) {
                var original=mapper.readTree(existing);var replay=mapper.readTree(json);
                // Legacy jobs did not record these envelope fields. Their already-frozen
                // requirements/options/reference assets still must match exactly; neither
                // replay nor migration may overwrite the original snapshot.
                if (replay instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                    for(String field:List.of("sourceFlat","requestedImageOptions","requestConfig"))
                        if (!original.has(field)) object.remove(field);
                }
                if (original.equals(replay)) return false;
            }
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("AI_INPUT_INVALID"); }
    }

    public long createJob(long userId, String phase, int count, String idempotencyKey, String projectRef) {
        return txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            Long replay=findRequestReplay(userId,phase,count,idempotencyKey,projectRef,null);
            if (replay != null) return replay;
            long jobId=IdWorker.getId();
            jdbcTemplate.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix,idempotency_key) VALUES(?,?,?,?,'QUEUED',?,?,?)",
                    jobId,userId,projectRef,phase,count,"ai-quarantine/"+jobId,idempotencyKey);
            return jobId;
        });
    }

    /** Serialize the account's operation before any debit, including calls from different projects. */
    public Long findRequestReplay(long userId,String phase,int count,String key,String ref,String resolution) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("AI_REQUEST_REQUIRES_TRANSACTION");
        if (key == null || key.isBlank()) return null;
        if (key.length()>128) throw new IllegalArgumentException("AI_REQUEST_KEY_TOO_LONG");
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs -> null,"ai-job:"+userId+":"+key);
        var rows=jdbcTemplate.queryForList("SELECT id,phase,project_ref,requested_count,request_resolution,deleted FROM ai_job WHERE user_id=? AND idempotency_key=?",userId,key);
        if (rows.isEmpty()) return null;
        var row=rows.get(0);
        if (Boolean.TRUE.equals(row.get("deleted")) || !java.util.Objects.equals(phase,row.get("phase"))
                || !java.util.Objects.equals(ref,row.get("project_ref")) || count!=((Number)row.get("requested_count")).intValue()
                || resolution!=null && !resolution.equals(row.get("request_resolution")))
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        return ((Number)row.get("id")).longValue();
    }

    /** App 端属主校验版查询（IDOR 防护） */
    public Optional<JobSnapshot> getJobForUser(long jobId, long userId) {
        return getJob(jobId).filter(j -> j.userId() == userId);
    }

    /** App 端属主校验版取消 */
    public void requestCancellationForUser(long jobId, long userId) {
        Long owner = jdbcTemplate.queryForObject(
                "SELECT user_id FROM ai_job WHERE id = ? AND deleted = FALSE", Long.class, jobId);
        if (owner == null || owner != userId) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN);
        }
        requestCancellation(jobId);
    }

    public Optional<JobSnapshot> getJob(long jobId) {
        List<JobSnapshot> rows = jdbcTemplate.query(AiJobQueryService.baseSelect()
                        + " WHERE id = ? AND deleted = FALSE",
                (rs, i) -> AiJobQueryService.mapRow(rs),
                jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<JobSnapshot> latestJob(long userId, long projectId, String phase) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM ai_job WHERE user_id=? AND project_ref=? "
                + "AND phase=? AND deleted=FALSE ORDER BY create_time DESC, id DESC LIMIT 1",
                Long.class, userId, String.valueOf(projectId), phase);
        return ids.isEmpty() ? Optional.empty() : getJob(ids.get(0));
    }

    // ========== Runtime：领取 / 续租 / 回写 ==========

    /** 领取：QUEUED + 租约过期的 RUNNING 一并可作为候补（FOR UPDATE SKIP LOCKED）；fencing_token 递增 */
    public List<ClaimedJob> claim(String workerId, int maxJobs, long leaseSeconds, String providerCode) {
        List<ClaimedJob> claimed = txTemplate.execute(status -> {
            List<Long> ids = jdbcTemplate.queryForList(
                    "UPDATE ai_job SET status = 'RUNNING', claimed_by = ?, claim_expires_at = "
                            + "now() + (? * interval '1 second'), fencing_token = fencing_token + 1, "
                            + "update_time = now() WHERE id IN ("
                            + "  SELECT id FROM ai_job WHERE deleted=FALSE AND create_time > now()-interval '30 minutes' "
                            + "  AND (SELECT count(*) FROM ai_job_attempt a WHERE a.job_id=ai_job.id) < 3 "
                            + "  AND (status = 'QUEUED' OR (status = 'RUNNING' AND claim_expires_at < now())) "
                            + "  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"
                            + ") RETURNING id",
                            Long.class, workerId, leaseSeconds, maxJobs);
            List<ClaimedJob> jobs = new java.util.ArrayList<>(ids.size());
            for (Long jobId : ids) {
                Map<String, Object> job = jdbcTemplate.queryForMap(
                        "SELECT fencing_token, phase, requested_count, output_prefix FROM ai_job WHERE id = ?", jobId);
                long fencing = ((Number) job.get("fencing_token")).longValue();
                Integer attemptNo = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM ai_job_attempt WHERE job_id = ?",
                        Integer.class, jobId);
                jdbcTemplate.update(
                        "INSERT INTO ai_job_attempt (id, job_id, attempt_no, worker_id, fencing_token) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        IdWorker.getId(), jobId, attemptNo, workerId + "@" + providerCode, fencing);
                jobs.add(new ClaimedJob(jobId, attemptNo, fencing,
                        (String) job.get("phase"),
                        ((Number) job.get("requested_count")).intValue(),
                        (String) job.get("output_prefix")));
            }
            return jobs;
        });
        log.info("[claim][worker={} 领取 {} 个任务]", workerId, claimed == null ? 0 : claimed.size());
        return claimed;
    }

    public boolean renewLease(long jobId, int attemptNo, long fencingToken, long leaseSeconds) {
        return jdbcTemplate.update(
                "UPDATE ai_job SET claim_expires_at = now() + (? * interval '1 second'), update_time = now() "
                        + "WHERE id = ? AND status='RUNNING' AND claim_expires_at>now() AND fencing_token = ? "
                        + "AND EXISTS (SELECT 1 FROM ai_job_attempt a WHERE a.job_id = ai_job.id "
                        + "  AND a.attempt_no = ? AND a.fencing_token = ?)",
                leaseSeconds, jobId, fencingToken, attemptNo, fencingToken) == 1;
    }

    public void reportProgress(long jobId, int attemptNo, long fencingToken, int progress, String stage) {
        if (fencingMatches(jobId, attemptNo, fencingToken)) {
            jdbcTemplate.update(
                    "UPDATE ai_job SET progress = GREATEST(progress,?), update_time = now() WHERE id = ? AND fencing_token = ? AND status='RUNNING' AND claim_expires_at>now()",
                    Math.max(0, Math.min(progress, 100)), jobId, fencingToken);
        }
    }

    /**
     * 结果事件：同锁递增 decisionSeq → Inbox（幂等）→ 隔离结果。
     * 迟于 cancel_seq 或终态后到达的事件只留诊断（SUPERSEDED / TERMINAL_IGNORED）。
     */
    public ReportOutcome reportResult(long jobId, int attemptNo, long fencingToken, String providerCode,
                                      String sourceEventId, int candidateSlotNo, String objectKey,
                                      String contentSha256, String mimeType, long sizeBytes) {
        return txTemplate.execute(status -> {
                Map<String, Object> job = jdbcTemplate.queryForMap(
                        "SELECT status, fencing_token, cancel_seq, output_prefix, decision_seq, claim_expires_at, runtime_completed_at FROM ai_job "
                                + "WHERE id = ? FOR UPDATE", jobId);
                long currentFencing = ((Number) job.get("fencing_token")).longValue();
                if (currentFencing != fencingToken || !attemptIsLatest(jobId, attemptNo, fencingToken)) {
                    return ReportOutcome.STALE_FENCING;
                }
                String jobStatus = (String) job.get("status");
                if (isTerminal(jobStatus)) {
                    insertInbox(jobId, attemptNo, providerCode, sourceEventId, "RESULT",
                            Map.of("note", "terminal-late"), 0L, "SUPERSEDED");
                    return ReportOutcome.TERMINAL_IGNORED;
                }
                if (job.get("runtime_completed_at") != null || (!"CANCEL_REQUESTED".equals(jobStatus)
                        && (!"RUNNING".equals(jobStatus) || job.get("claim_expires_at") == null
                        || ((java.sql.Timestamp)job.get("claim_expires_at")).toInstant().isBefore(java.time.Instant.now()))))
                    return ReportOutcome.STALE_FENCING;
                long receivedSeq = ((Number) job.get("decision_seq")).longValue() + 1;
                Object cancelSeq = job.get("cancel_seq");
                boolean superseded = cancelSeq != null
                        && receivedSeq > ((Number) cancelSeq).longValue();
                jdbcTemplate.update(
                        "UPDATE ai_job SET decision_seq = ?, update_time = now() WHERE id = ?",
                        receivedSeq, jobId);

                String inboxStatus = superseded ? "SUPERSEDED" : "RECEIVED";
                int inserted = jdbcTemplate.update(
                            "INSERT INTO ai_result_event_inbox (id, job_id, attempt_no, provider_code, "
                                    + "source_event_id, event_kind, payload, received_seq, process_status) "
                                    + "VALUES (?, ?, ?, ?, ?, 'RESULT', "
                                    + "JSONB_BUILD_OBJECT('slot', ?, 'objectKey', ?, 'sha256', ?, 'mime', ?, 'size', ?), ?, ?) "
                                    + "ON CONFLICT (provider_code, source_event_id) DO NOTHING",
                            IdWorker.getId(), jobId, attemptNo, providerCode, sourceEventId,
                            candidateSlotNo, objectKey, contentSha256, mimeType, sizeBytes,
                            receivedSeq, inboxStatus);
                if (inserted == 0) return ReportOutcome.DUPLICATE_EVENT;
                if (superseded) {
                    return ReportOutcome.SUPERSEDED;
                }
                jdbcTemplate.update(
                        "INSERT INTO ai_job_result (id, job_id, attempt_no, provider_code, candidate_slot_no, "
                                + "content_sha256, object_key, mime_type, size_bytes, validation_state) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OUTPUT_QUARANTINED')",
                        IdWorker.getId(), jobId, attemptNo, providerCode, candidateSlotNo,
                        contentSha256, objectKey, mimeType, sizeBytes);
                return ReportOutcome.QUARANTINED;
            });
    }

    /** 失败事件：记录并关闭尝试，任务回队（新 attempt 可再领取） */
    public boolean reportFailure(long jobId, int attemptNo, long fencingToken, String providerCode,
                                 String sourceEventId, String errorCode, String message) {
        return txTemplate.execute(status -> {
            Map<String, Object> job = jdbcTemplate.queryForMap(
                    "SELECT status, fencing_token, cancel_seq, decision_seq FROM ai_job "
                            + "WHERE id = ? FOR UPDATE", jobId);
            long currentFencing = ((Number) job.get("fencing_token")).longValue();
            if (currentFencing != fencingToken || !attemptIsLatest(jobId, attemptNo, fencingToken)) {
                return false;
            }
            String st = (String) job.get("status");
            if (isTerminal(st)) {
                return true;
            }
            if (!"RUNNING".equals(st)) {
                // 任务已回队/取消中：旧 Worker 不再持有租约，失败回写拒绝
                return false;
            }
            long receivedSeq = ((Number) job.get("decision_seq")).longValue() + 1;
            jdbcTemplate.update(
                    "UPDATE ai_job SET decision_seq = ?, update_time = now() WHERE id = ?", receivedSeq, jobId);
            insertInbox(jobId, attemptNo, providerCode, sourceEventId, "FAILURE",
                    Map.of("errorCode", errorCode == null ? "" : errorCode,
                            "message", message == null ? "" : message), receivedSeq, "RECEIVED");
            jdbcTemplate.update(
                    "UPDATE ai_job_attempt SET finished_at = now(), last_error = ?, update_time = now() "
                            + "WHERE job_id = ? AND attempt_no = ?",
                    errorCode + ": " + message, jobId, attemptNo);
            if ("RUNNING".equals(st)) {
                jdbcTemplate.update(
                        "UPDATE ai_job SET status = CASE WHEN ? >= 3 THEN 'VALIDATING' ELSE 'QUEUED' END, "
                                + "runtime_completed_at=CASE WHEN ? >= 3 THEN now() ELSE NULL END, claimed_by = NULL, claim_expires_at = NULL, "
                                + "update_time = now() WHERE id = ?", attemptNo, attemptNo, jobId);
            }
            return true;
        });
    }

    // ========== Core 校验器：隔离结果 → ACCEPTED / REJECTED（事务外校验 + 短事务固化） ==========

    /** Stable completion barrier: no subsequent output from this attempt may become billable. */
    public boolean completeAttempt(long jobId, int attemptNo, long fence) {
        return txTemplate.execute(status -> {
            var row = jdbcTemplate.queryForMap("SELECT status,fencing_token,runtime_completed_at FROM ai_job WHERE id=? FOR UPDATE", jobId);
            if (((Number)row.get("fencing_token")).longValue()!=fence || !attemptIsLatest(jobId,attemptNo,fence)) return false;
            if (row.get("runtime_completed_at") != null || isTerminal((String)row.get("status"))) return true;
            int updated = jdbcTemplate.update("UPDATE ai_job SET runtime_completed_at=now(), status=CASE WHEN status='CANCEL_REQUESTED' THEN status ELSE 'VALIDATING' END, "
                    + "update_time=now() WHERE id=? AND status IN ('RUNNING','CANCEL_REQUESTED') AND claim_expires_at>now()", jobId);
            if (updated==1) jdbcTemplate.update("UPDATE ai_job_attempt SET finished_at=now() WHERE job_id=? AND attempt_no=?",jobId,attemptNo);
            return updated==1;
        });
    }

    public int validateQuarantinedResults(long jobId) {
        List<Map<String, Object>> quarantined = jdbcTemplate.queryForList(
                "SELECT id, candidate_slot_no, content_sha256, object_key, mime_type, size_bytes "
                        + "FROM ai_job_result WHERE job_id = ? AND validation_state = 'OUTPUT_QUARANTINED' "
                        + "AND validation_due_at<=now() AND (validation_expires_at IS NULL OR validation_expires_at<now()) ORDER BY id LIMIT 8", jobId);
        if (quarantined.isEmpty()) {
            return 0;
        }
        Map<String, Object> jobRow = jdbcTemplate.queryForMap(
                "SELECT requested_count, output_prefix FROM ai_job WHERE id = ?", jobId);
        int requestedCount = ((Number) jobRow.get("requested_count")).intValue();
        String outputPrefix = (String) jobRow.get("output_prefix");
        int accepted = 0;
        for (Map<String, Object> r : quarantined) {
            long resultId = ((Number) r.get("id")).longValue();
            String token = java.util.UUID.randomUUID().toString();
            if (jdbcTemplate.update("UPDATE ai_job_result SET validation_token=?,validation_expires_at=now()+interval '2 minutes' "
                    + "WHERE id=? AND validation_state='OUTPUT_QUARANTINED' AND (validation_expires_at IS NULL OR validation_expires_at<now())",token,resultId)!=1) continue;
            r.put("validation_token", token);
            try {
                String rejectReason = validateOne(requestedCount, outputPrefix, r);
                if (Boolean.TRUE.equals(finalizeResult(resultId, jobId, rejectReason, r))) accepted++;
            } catch (RuntimeException e) {
                jdbcTemplate.update("UPDATE ai_job_result SET validation_due_at=now()+interval '60 seconds',validation_expires_at=NULL, "
                        + "reject_reason='VALIDATION_RETRY' WHERE id=? AND validation_token=? AND validation_state='OUTPUT_QUARANTINED'",resultId,token);
            }
        }
        syncAcceptedCount(jobId);
        return accepted;
    }

    /** 事务外校验：槽位范围、前缀、对象存在性、内容 SHA、格式与内容安全 */
    private String validateOne(int requestedCount, String outputPrefix, Map<String, Object> r) {
        String objectKey = (String) r.get("object_key");
        long sizeBytes = ((Number) r.get("size_bytes")).longValue();
        int slotNo = ((Number) r.get("candidate_slot_no")).intValue();
        if (sizeBytes <= 0 || sizeBytes > 20 * 1024 * 1024 || !java.util.List.of("image/png","image/jpeg").contains(r.get("mime_type"))) return "OUTPUT_POLICY";
        if (slotNo < 1 || slotNo > requestedCount) {
            return "SLOT_OUT_OF_RANGE: 槽位 " + slotNo + " 超出请求数 " + requestedCount;
        }
        if (objectKey == null || !objectKey.startsWith(outputPrefix + "/")) {
            return "OBJECT_KEY_PREFIX: 输出不在任务隔离前缀内";
        }
        if (!quarantineObjectPort.existsWithSize(objectKey, sizeBytes)) {
            return "OBJECT_MISSING: 隔离区不存在声明大小的对象";
        }
        byte[] content = quarantineObjectPort.getObject(objectKey);
        if (content.length != sizeBytes) return "SIZE_MISMATCH";
        String actualSha = sha256Hex(content);
        if (!actualSha.equalsIgnoreCase((String) r.get("content_sha256"))) {
            return "SHA_MISMATCH: 对象内容与声明 SHA-256 不一致";
        }
        var outcome = contentScanPort.scan((String) r.get("mime_type"), content);
        r.put("scan_evidence",outcome.evidence());
        if (!outcome.passed()) {
            return "CONTENT_FAILED: " + String.join("; ", outcome.failures());
        }
        byte[] sanitized = outcome.sanitizedContent() == null ? content : outcome.sanitizedContent();
        if (sanitized.length > 20*1024*1024) return "SANITIZED_SIZE_LIMIT";
        String acceptedKey = "accepted/ai/" + r.get("id") + "/" + r.get("validation_token")
                + ("image/png".equals(r.get("mime_type")) ? ".png" : ".jpg");
        quarantineObjectPort.putObject(acceptedKey,sanitized);
        r.put("accepted_key",acceptedKey); r.put("accepted_sha",sha256Hex(sanitized)); r.put("accepted_size",sanitized.length);
        r.put("scan_evidence",outcome.evidence());
        return null;
    }

    /**
     * 短事务 CAS 固化：取任务行锁（串行化同任务接受、与取消互斥）→ 终态/容量闸门 →
     * ACCEPT CAS（savepoint 内捕获部分唯一冲突，回滚到 savepoint 后落 REJECTED——PG 事务中止后不能直接续写）。
     */
    private boolean finalizeResult(long resultId, long jobId, String rejectReason, Map<String,Object> result) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            jdbcTemplate.queryForMap("SELECT id FROM ai_job WHERE id=? FOR UPDATE",jobId);
            if (jdbcTemplate.queryForObject("SELECT count(*) FROM ai_job_result WHERE id=? AND validation_token=? AND validation_state='OUTPUT_QUARANTINED'",
                    Integer.class,resultId,result.get("validation_token")) != 1) return false;
            if (rejectReason != null) {
                jdbcTemplate.update(
                        "UPDATE ai_job_result SET validation_state = 'REJECTED', reject_reason = ?, scan_evidence=?, "
                                + "update_time = now() WHERE id = ? AND validation_state = 'OUTPUT_QUARANTINED'",
                        rejectReason, result.get("scan_evidence"), resultId);
                return false;
            }
            Map<String, Object> job = jdbcTemplate.queryForMap(
                    "SELECT status, requested_count FROM ai_job WHERE id = ? FOR UPDATE", jobId);
            if (isTerminal((String) job.get("status"))) {
                jdbcTemplate.update(
                        "UPDATE ai_job_result SET validation_state = 'REJECTED', "
                                + "reject_reason = 'JOB_TERMINAL', update_time = now() "
                                + "WHERE id = ? AND validation_state = 'OUTPUT_QUARANTINED'", resultId);
                return false;
            }
            int requestedCount = ((Number) job.get("requested_count")).intValue();
            Object savepoint = status.createSavepoint();
            try {
                int updated = jdbcTemplate.update(
                        "UPDATE ai_job_result SET validation_state = 'ACCEPTED', object_key=?,content_sha256=?,size_bytes=?,scan_evidence=?, update_time = now() "
                                + "WHERE id = ? AND validation_state = 'OUTPUT_QUARANTINED' "
                                + "AND (SELECT count(*) FROM ai_job_result "
                                + "      WHERE job_id = ? AND validation_state = 'ACCEPTED') < ?",
                        result.get("accepted_key"),result.get("accepted_sha"),result.get("accepted_size"),result.get("scan_evidence"),resultId, jobId, requestedCount);
                return updated == 1;
            } catch (DuplicateKeyException e) {
                status.rollbackToSavepoint(savepoint);
                jdbcTemplate.update(
                        "UPDATE ai_job_result SET validation_state = 'REJECTED', "
                                + "reject_reason = 'SLOT_OR_CONTENT_TAKEN', update_time = now() "
                                + "WHERE id = ? AND validation_state = 'OUTPUT_QUARANTINED'", resultId);
                return false;
            }
        }));
    }

    private void syncAcceptedCount(long jobId) {
        jdbcTemplate.update(
                "UPDATE ai_job SET accepted_count = ("
                        + "SELECT count(*) FROM ai_job_result WHERE job_id = ? AND validation_state = 'ACCEPTED'), "
                        + "update_time = now() WHERE id = ?", jobId, jobId);
    }

    /** 已接受结果明细（供 AiJobPort/候选晋升使用） */
    public List<AcceptedResult> listAcceptedResults(long jobId) {
        return jdbcTemplate.query(
                "SELECT id, candidate_slot_no, object_key, content_sha256, mime_type "
                        + "FROM ai_job_result WHERE job_id = ? AND validation_state = 'ACCEPTED' "
                        + "ORDER BY candidate_slot_no",
                (rs, i) -> new AcceptedResult(rs.getLong("id"), rs.getInt("candidate_slot_no"),
                        rs.getString("object_key"), rs.getString("content_sha256"),
                        rs.getString("mime_type")),
                jobId);
    }

    public record AcceptedResult(long resultId, int slotNo, String objectKey,
                                 String sha256, String mimeType) {
    }

    // ========== 取消 ==========

    public void requestCancellation(long jobId) {
        txTemplate.execute(status -> {
            Map<String, Object> job = jdbcTemplate.queryForMap(
                    "SELECT status, decision_seq, user_id FROM ai_job WHERE id = ? FOR UPDATE", jobId);
            String st = (String) job.get("status");
            if (isTerminal(st)) {
                throw exception(AI_JOB_NOT_CANCELLABLE);
            }
            long cancelSeq = ((Number) job.get("decision_seq")).longValue() + 1;
            // QUEUED 也进入 CANCEL_REQUESTED：终态与退款由 settle(CANCEL) 在同一事务链统一处理，
            // 修复原实现「QUEUED 直取消跳过结算导致已扣点不退」的资损缺陷（审查 C1）。
            jdbcTemplate.update(
                    "UPDATE ai_job SET status = 'CANCEL_REQUESTED', cancel_seq = ?, cancel_requested_at = now(), "
                            + "decision_seq = ?, update_time = now() WHERE id = ?",
                    cancelSeq, cancelSeq, jobId);
            return null;
        });
    }

    /** 完成取消（P4B 最小路径）：只要存在未被判拒的结果（在途/隔离/已接受）就不完成，交给 P4C 槽位结算 */
    public boolean completeCancellation(long jobId) {
        return txTemplate.execute(status -> {
            Map<String, Object> job = jdbcTemplate.queryForMap(
                    "SELECT status, user_id FROM ai_job WHERE id = ? FOR UPDATE", jobId);
            if (!"CANCEL_REQUESTED".equals(job.get("status"))) {
                return false;
            }
            Integer liveResults = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_job_result WHERE job_id = ? "
                            + "AND validation_state IN ('RECEIVED','OUTPUT_QUARANTINED','VALIDATING','ACCEPTED')",
                    Integer.class, jobId);
            if (liveResults != null && liveResults > 0) {
                return false;
            }
            int updated = jdbcTemplate.update(
                    "UPDATE ai_job SET status = 'CANCELLED', claimed_by = NULL, claim_expires_at = NULL, "
                            + "update_time = now() WHERE id = ? AND status = 'CANCEL_REQUESTED'", jobId);
            if (updated == 1) {
                // 本路径的任务已被领取过（CANCEL_REQUESTED 只在运行中产生），与 requestCancellation 的
                // QUEUED 直取消区分开，便于消息文案与审计还原取消发生的时点。
                long jobOwner = ((Number) job.get("user_id")).longValue();
                reliableEventPort.append(cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage.builder()
                        .eventType("AI_JOB_CANCELLED").bizType("ai_job").bizId(String.valueOf(jobId))
                        .payload(Map.of("jobId", jobId, "userId", jobOwner, "stage", "after-claim")).build());
            }
            return updated == 1;
        });
    }

    // ========== 内部 ==========

    private void insertInbox(long jobId, int attemptNo, String providerCode, String sourceEventId,
                             String kind, Map<String, Object> payload, long receivedSeq, String status) {
        jdbcTemplate.update(
                "INSERT INTO ai_result_event_inbox (id, job_id, attempt_no, provider_code, source_event_id, "
                        + "event_kind, payload, received_seq, process_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, JSONB_BUILD_OBJECT(?::text, ?::text), ?, ?) "
                        + "ON CONFLICT (provider_code, source_event_id) DO NOTHING",
                IdWorker.getId(), jobId, attemptNo, providerCode, sourceEventId, kind,
                "payload", payload.toString(), receivedSeq, status);
    }

    private boolean fencingMatches(long jobId, int attemptNo, long fencingToken) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT fencing_token = ? AND EXISTS (SELECT 1 FROM ai_job_attempt a "
                        + "WHERE a.job_id = ai_job.id AND a.attempt_no = ? AND a.fencing_token = ?) "
                        + "FROM ai_job WHERE id = ?",
                Boolean.class, fencingToken, attemptNo, fencingToken, jobId));
    }

    private boolean attemptIsLatest(long jobId, int attemptNo, long fencingToken) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_job_attempt WHERE job_id = ? AND attempt_no = ? AND fencing_token = ?",
                Integer.class, jobId, attemptNo, fencingToken);
        return n != null && n > 0;
    }

    private static String sha256Hex(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIALLY_SUCCEEDED".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

}
