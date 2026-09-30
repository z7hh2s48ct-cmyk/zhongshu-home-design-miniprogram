package cn.iocoder.yudao.module.aiorchestration.job;

import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * P4C：任务创建扣点与槽位结算（架构 §6.4 结算规则 / §8.2 事务链）
 *
 * 合同：
 * - 创建任务同一事务：报价校验（PRICE_RULE_CHANGED 可重试）→ 锁账户扣点 → charge 快照 → job → Outbox；
 *   幂等键 user_id + Idempotency-Key（同键重放返回原任务，不重复扣点）；
 * - 终态结算同一事务：只读 ACCEPTED 槽位（容量闸门保证 ≤ N）→ 正式状态 + settlement + 差额/全额退点 + Outbox；
 *   0 个有效 → FAILED/CANCELLED 全退；1~N-1 → PARTIALLY_SUCCEEDED 差额退；N → SUCCEEDED 不退；
 * - settlement (job_id, settlement_version, reason) 唯一；累计退款 ≤ 原扣点；终态幂等。
 */
@Slf4j
@Service
public class AiJobSettlementService {

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final PricingPort pricingPort;

    private final PointLedgerPort pointLedgerPort;

    private final AiJobOrchestrationService orchestrationService;

    private final cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort reliableEventPort;
    private final cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricingPort;

    @org.springframework.beans.factory.annotation.Autowired
    public AiJobSettlementService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  PricingPort pricingPort, PointLedgerPort pointLedgerPort,
                                  AiJobOrchestrationService orchestrationService,
                                  cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort reliableEventPort,
                                  cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricingPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.pricingPort = pricingPort;
        this.pointLedgerPort = pointLedgerPort;
        this.orchestrationService = orchestrationService;
        this.reliableEventPort = reliableEventPort;
        this.usagePricingPort = usagePricingPort;
    }

    public AiJobSettlementService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  PricingPort pricingPort, PointLedgerPort pointLedgerPort,
                                  AiJobOrchestrationService orchestrationService,
                                  cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort reliableEventPort) {
        this(dataSource, transactionManager, pricingPort, pointLedgerPort, orchestrationService, reliableEventPort, null);
    }

    /** 创建任务并同事务扣点（P4C 接通账本；幂等键 = user_id + Idempotency-Key） */
    public long createJobWithCharge(long userId, String phase, int count,
                                    String idempotencyKey, String projectRef) {
        return createJobWithCharge(userId, phase, count, idempotencyKey, projectRef, null);
    }

    public long createJobWithCharge(long userId, String phase, int count,
                                    String idempotencyKey, String projectRef, PricingPort.PriceConfirmation confirmation) {
        return createJobWithCharge(userId, phase, count, idempotencyKey, projectRef, confirmation, "2K");
    }

    public long createJobWithCharge(long userId, String phase, int count,
                                    String idempotencyKey, String projectRef,
                                    PricingPort.PriceConfirmation confirmation, String resolution) {
        try {
            return txTemplate.execute(status -> {
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    // 审查 H8：幂等键限定 user 维度（跨用户同 Key 不得复用他人任务）
                    List<Long> existing = jdbcTemplate.query(
                            "SELECT id FROM ai_job WHERE idempotency_key = ? AND user_id = ?",
                            (rs, i) -> rs.getLong("id"), idempotencyKey, userId);
                    if (!existing.isEmpty()) {
                        return existing.get(0);
                    }
                }
                PricingPort.PriceSnapshot quote = pricingPort.quote(phase, count, resolution);
                PricingPort.requireConfirmed(quote, confirmation);
                pricingPort.validateSnapshotStillValid(quote); // 价格更新竞态：失效即 PRICE_RULE_CHANGED
                cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Snapshot promptQuote = null;
                if (usagePricingPort != null) {
                    promptQuote = usagePricingPort.quote("AI_PROMPT");
                    cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.requireConfirmed(promptQuote,
                            confirmation == null ? null : confirmation.usageConfirmation());
                }

                long jobId = IdWorker.getId();
                String ledgerType = "FLAT".equals(phase) ? "FLAT_GENERATION_DEBIT" : "ELEVATION_GENERATION_DEBIT";
                long ledgerId = pointLedgerPort.debit(userId, ledgerType, quote.totalPointCost(),
                        "ai_job", String.valueOf(jobId),
                        "AI_JOB:" + (idempotencyKey == null ? jobId : idempotencyKey), null, null);

                long chargeId = IdWorker.getId();
                jdbcTemplate.update(
                        "INSERT INTO ai_job (id, user_id, project_ref, phase, status, requested_count, "
                                + "output_prefix, idempotency_key, unit_point_cost, total_point_cost, charge_id) "
                                + "VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?)",
                        jobId, userId, projectRef, phase, count,
                        "ai-quarantine/" + jobId, idempotencyKey,
                        quote.unitPointCost(), quote.totalPointCost(), chargeId);
                jdbcTemplate.update(
                        "INSERT INTO ai_task_charge (id, job_id, user_id, price_rule_id, price_rule_version, "
                                + "stage, unit_point_cost, requested_count, total_point_cost, ledger_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        chargeId, jobId, userId, quote.ruleId(), quote.ruleVersion(),
                        quote.stage(), quote.unitPointCost(), count, quote.totalPointCost(), ledgerId);
                if (promptQuote != null) usagePricingPort.prepareCharge(userId, promptQuote, "ai_job", String.valueOf(jobId));
                reliableEventPort.append(cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage.builder()
                        .eventType("AI_JOB_CREATED").bizType("ai_job").bizId(String.valueOf(jobId))
                        .payload(Map.of("jobId", jobId, "phase", phase, "count", count)).build());
                log.info("[createJobWithCharge][job={} user={} 扣点={}]", jobId, userId, quote.totalPointCost());
                return jobId;
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同幂等键：本事务（含扣点）整体回滚，复用获胜方任务（同用户）
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_job WHERE idempotency_key = ? AND user_id = ?",
                    Long.class, idempotencyKey, userId);
        }
    }

    /** 扣除一次实际提示词模型调用；业务键使 runtime 重试保持幂等。 */
    public boolean chargePromptCall(long jobId) {
        long userId = jdbcTemplate.queryForObject("SELECT user_id FROM ai_job WHERE id = ?", Long.class, jobId);
        return usagePricingPort.chargePrepared(userId, "AI_PROMPT", "ai_job", String.valueOf(jobId));
    }

    /**
     * 槽位结算：按 ACCEPTED 槽位终态化并退点（幂等；终态后重入返回既有结算）。
     * reason：SETTLE（正常完成）/ CANCEL（取消路径带结果）。
     */
    public SettlementSummary settle(long jobId, String reason) {
        SettlementSummary existing = txTemplate.execute(status -> {
            Map<String, Object> job = jdbcTemplate.queryForMap(
                    "SELECT status, user_id, requested_count, unit_point_cost, total_point_cost, accepted_count "
                            + "FROM ai_job WHERE id = ? FOR UPDATE", jobId);
            String currentStatus = (String) job.get("status");
            if (isTerminal(currentStatus)) {
                Long refunded = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(refunded_points), 0) FROM ai_job_settlement WHERE job_id = ?",
                        Long.class, jobId);
                return new SettlementSummary(currentStatus, 0L,
                        refunded == null ? 0L : refunded);
            }
            // 状态白名单：SETTLE 仅允许运行中完成态；CANCEL 仅允许取消请求态（防提前结算漏计费）
            boolean allowed = "CANCEL".equals(reason)
                    ? "CANCEL_REQUESTED".equals(currentStatus)
                    : "SETTLE".equals(reason) && List.of("QUEUED", "RUNNING", "VALIDATING", "CANCEL_REQUESTED")
                            .contains(currentStatus);
            if (!allowed) {
                throw new IllegalStateException("任务状态不允许结算: " + currentStatus + " reason=" + reason);
            }
            int requested = ((Number) job.get("requested_count")).intValue();
            long unitCost = ((Number) job.get("unit_point_cost")).longValue();
            long originalDebit = ((Number) job.get("total_point_cost")).longValue();
            long userId = ((Number) job.get("user_id")).longValue();

            // 只认 ACCEPTED 槽位（容量闸门已在接受时保证 ≤ N）
            Integer acceptedRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_job_result WHERE job_id = ? AND validation_state = 'ACCEPTED'",
                    Integer.class, jobId);
            int accepted = acceptedRows == null ? 0 : acceptedRows;
            int billable = Math.min(accepted, requested);
            long refund = originalDebit - (long) billable * unitCost;

            String nextStatus;
            if ("CANCEL_REQUESTED".equals(currentStatus) || "CANCELLED".equals(currentStatus)) {
                nextStatus = billable == 0 ? "CANCELLED" : "PARTIALLY_SUCCEEDED";
            } else if (billable == 0) {
                nextStatus = "FAILED";
            } else if (billable >= requested) {
                nextStatus = "SUCCEEDED";
            } else {
                nextStatus = "PARTIALLY_SUCCEEDED";
            }

            long settlementVersion = 1;
            long refundedNow = 0;
            if (refund > 0) {
                String refundKey = "AI_JOB_SETTLE:" + jobId + ":v" + settlementVersion + ":" + reason;
                pointLedgerPort.credit(userId, "TASK_SETTLEMENT_REFUND", refund,
                        "ai_job", String.valueOf(jobId), refundKey, null,
                        "任务结算退回（" + nextStatus + "）");
                refundedNow = refund;
            }
            jdbcTemplate.update(
                    "INSERT INTO ai_job_settlement (id, job_id, settlement_version, requested_count, "
                            + "accepted_billable_count, unit_point_cost, original_debit, refunded_points, reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), jobId, settlementVersion, requested, billable,
                    unitCost, originalDebit, refundedNow, reason);
            jdbcTemplate.update(
                    "UPDATE ai_job SET status = ?, accepted_count = ?, claimed_by = NULL, "
                            + "claim_expires_at = NULL, update_time = now() WHERE id = ? AND status = ?",
                    nextStatus, accepted, jobId, currentStatus);
            reliableEventPort.append(cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage.builder()
                    .eventType("AI_JOB_SETTLED").bizType("ai_job").bizId(String.valueOf(jobId))
                    .payload(Map.of("jobId", jobId, "userId", userId, "status", nextStatus,
                            "billable", billable, "refund", refundedNow)).build());
            log.info("[settle][job={} → {} billable={} refund={}]", jobId, nextStatus, billable, refundedNow);
            return new SettlementSummary(nextStatus, refundedNow, refundedNow);
        });
        return existing;
    }

    /** Readiness is rechecked while holding the same job lock used for accepting outputs. */
    public boolean settleIfReady(long jobId, int deadlineMinutes, int cancelMinutes) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            var job = jdbcTemplate.queryForMap("SELECT status, "
                    + "(create_time < now()-(? * interval '1 minute')) AS expired, "
                    + "(status='CANCEL_REQUESTED' AND cancel_requested_at < now()-(? * interval '1 minute')) AS cancel_expired, "
                    + "((SELECT count(*) FROM ai_job_result r WHERE r.job_id=ai_job.id AND r.validation_state='ACCEPTED')>=requested_count) AS full, "
                    + "(runtime_completed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id=ai_job.id AND r.validation_state='OUTPUT_QUARANTINED')) AS done "
                    + "FROM ai_job WHERE id=? FOR UPDATE",deadlineMinutes,cancelMinutes,jobId);
            if (isTerminal((String)job.get("status"))) return false;
            if (!Boolean.TRUE.equals(job.get("expired")) && !Boolean.TRUE.equals(job.get("cancel_expired"))
                    && !Boolean.TRUE.equals(job.get("full")) && !Boolean.TRUE.equals(job.get("done"))) return false;
            // A timed-out pending validator cannot add billable results after this lock is released.
            jdbcTemplate.update("UPDATE ai_job_result SET validation_state='REJECTED',reject_reason='JOB_DEADLINE',update_time=now() "
                    + "WHERE job_id=? AND validation_state='OUTPUT_QUARANTINED'",jobId);
            settle(jobId,"CANCEL_REQUESTED".equals(job.get("status"))?"CANCEL":"SETTLE");
            return true;
        }));
    }

    public record SettlementSummary(String status, long refundedNow, long refundedTotal) {
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIALLY_SUCCEEDED".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

}
