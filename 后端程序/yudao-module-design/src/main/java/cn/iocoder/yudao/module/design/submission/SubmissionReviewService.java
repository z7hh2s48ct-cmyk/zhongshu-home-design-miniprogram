package cn.iocoder.yudao.module.design.submission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.PUBLICATION_VALIDATION_FAILED;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.SUBMISSION_STATE_CONFLICT;

/**
 * 投稿、退修复审与上架（架构 §6.7 / §8.4；蓝图 P7A）
 *
 * 合同：
 * - 三套状态分离：计算（结果版本）、投稿（本状态机）、发布（P3B case_publication）；
 * - 用户授予许可（公开展示/生成参考两项分开），审核决定合规性，发布是独立命令——三者不合并；
 * - revision 不可变：每次提交/重提新建 revision 轮次；仅 CHANGES_REQUESTED 可重提；
 * - 投稿人自审拒绝；审核通过 + 公开展示许可有效才可上架（生成 AI 案例版本）；
 * - WITHDRAWN 不可改回，重上需新案例版本 + 新授权 + 新发布事实（P3B 语义复用）。
 */
@Slf4j
@Service
public class SubmissionReviewService {
    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort accountStatePort;

    public String publicationStatus(Long caseId) {
        if (caseId == null) return null;
        List<String> rows = jdbcTemplate.queryForList("SELECT publication_status FROM design_case WHERE id=? AND deleted=FALSE",
                String.class, caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final CaseCatalogService caseCatalogService;

    private final ReliableEventPort reliableEventPort;

    public SubmissionReviewService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                   CaseCatalogService caseCatalogService, ReliableEventPort reliableEventPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.caseCatalogService = caseCatalogService;
        this.reliableEventPort = reliableEventPort;
    }

    /** 发布校核：返回缺失项（空列表=可提交） */
    // ========== 后台审核队列（管理端页面 05/06）==========

    /** 后台队列分页：可按状态过滤（不筛 user）；含最新一轮审核意见 */
    public long countAdminSubmissions(String status) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if ("SUBMITTED".equals(status)) {
            where.append(" AND status IN ('SUBMITTED','RESUBMITTED','IN_REVIEW')");
        } else if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM case_submission" + where,
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public java.util.List<SubmissionRow> pageAdminSubmissions(String status, int pageNo, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE s.deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if ("SUBMITTED".equals(status)) {
            where.append(" AND s.status IN ('SUBMITTED','RESUBMITTED','IN_REVIEW')");
        } else if (status != null && !status.isBlank()) {
            where.append(" AND s.status = ?");
            args.add(status);
        }
        int size = Math.min(Math.max(pageSize, 1), 100);
        args.add(size);
        args.add((long) Math.max(pageNo - 1, 0) * size);
        return jdbcTemplate.query(
                "SELECT " + SUBMISSION_COLUMNS + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                this::mapSubmissionRow, args.toArray());
    }

    public java.util.Optional<SubmissionRow> getAdminSubmission(long submissionId) {
        var rows = jdbcTemplate.query(
                "SELECT " + SUBMISSION_COLUMNS + "WHERE s.id = ? AND s.deleted = FALSE",
                this::mapSubmissionRow, submissionId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    // ========== 小程序读模型（页面 12 与「我的 → 户型投稿」） ==========

    public record SubmissionRow(long submissionId, long projectId, long userId, long resultVersionId, String status,
                                int currentRound, Long publishedCaseId, String reviewComment,
                                java.time.Instant createTime, boolean publicDisplayGranted,
                                boolean generationReferenceGranted, String note) {
    }

    private static final String SUBMISSION_COLUMNS =
            "s.id, s.project_id, s.user_id, s.result_version_id, s.status, s.current_round, s.published_case_id, "
                    + "s.create_time, s.public_display_granted, s.generation_reference_granted, d.comment AS review_comment, "
                    + "(SELECT r.content_snapshot->>'note' FROM submission_revision r WHERE r.submission_id=s.id AND r.round_no=s.current_round AND r.deleted=FALSE) AS note "
                    + "FROM case_submission s "
                    + "LEFT JOIN review_task t ON t.submission_id = s.id AND t.round_no = s.current_round "
                    + "AND t.deleted = FALSE "
                    + "LEFT JOIN review_decision d ON d.review_task_id = t.id AND d.deleted = FALSE ";

    /** 我的投稿列表（最新在前，游标为上一页末行 id；单用户投稿量小，limit 上限 50） */
    public java.util.List<SubmissionRow> listMySubmissions(long userId, Long beforeId, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        StringBuilder sql = new StringBuilder(
                "SELECT " + SUBMISSION_COLUMNS + "WHERE s.user_id = ? AND s.deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (beforeId != null) {
            sql.append(" AND s.id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY s.id DESC LIMIT ?");
        args.add(size + 1);
        var rows = jdbcTemplate.query(sql.toString(), this::mapSubmissionRow, args.toArray());
        boolean hasMore = rows.size() > size;
        return hasMore ? rows.subList(0, size) : rows;
    }

    public java.util.Optional<SubmissionRow> getMySubmission(long userId, long submissionId) {
        var rows = jdbcTemplate.query(
                "SELECT " + SUBMISSION_COLUMNS + "WHERE s.id = ? AND s.user_id = ? AND s.deleted = FALSE",
                this::mapSubmissionRow, submissionId, userId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    private SubmissionRow mapSubmissionRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Long publishedCaseId = rs.getObject("published_case_id") == null
                ? null : rs.getLong("published_case_id");
        return new SubmissionRow(rs.getLong("id"), rs.getLong("project_id"), rs.getLong("user_id"),
                rs.getLong("result_version_id"), rs.getString("status"), rs.getInt("current_round"),
                publishedCaseId, rs.getString("review_comment"),
                rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toInstant(),
                rs.getBoolean("public_display_granted"), rs.getBoolean("generation_reference_granted"), rs.getString("note"));
    }

    public List<String> validateForPublication(long userId, long projectId, long resultVersionId) {
        Long owner = jdbcTemplate.queryForObject("SELECT count(*) FROM design_project WHERE id=? AND user_id=? AND deleted=FALSE",
                Long.class, projectId, userId);
        if (owner == null || owner == 0) throw exception(RESOURCE_FORBIDDEN);
        List<String> missing = new java.util.ArrayList<>();
        Long version = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_result_version WHERE id = ? AND project_id = ? "
                        + "AND deleted = FALSE",
                Long.class, resultVersionId, projectId);
        if (version == null || version == 0) {
            missing.add("resultVersion: 最终结果版本不存在或不属于该项目");
        }
        Integer elevation = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_result_version WHERE id = ? "
                        + "AND elevation_selection_id IS NOT NULL",
                Integer.class, resultVersionId);
        if (elevation == null || elevation == 0) {
            missing.add("elevation: 尚未选定立面候选");
        }
        return missing;
    }

    /** 提交投稿（幂等键 project+version+Idempotency-Key）；许可两项分开授予 */
    public long submit(long userId, long projectId, long resultVersionId,
                       boolean publicDisplayGranted, boolean generationReferenceGranted,
                       String note, String idempotencyKey) {
        List<String> missing = validateForPublication(userId, projectId, resultVersionId);
        if (!missing.isEmpty()) {
            throw new ServiceException(1_071_000_002, "发布校核未通过: " + String.join("; ", missing));
        }
        if (!publicDisplayGranted) {
            throw exception(PUBLICATION_VALIDATION_FAILED); // 投稿至 AI 案例库必须授公开展示
        }
        return txTemplate.execute(status -> {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
                List<Long> existing = jdbcTemplate.query(
                        "SELECT id FROM case_submission WHERE idempotency_key = ?",
                        (rs, i) -> rs.getLong("id"), idempotencyKey);
                if (!existing.isEmpty()) {
                    return existing.get(0);
                }
            }
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            long submissionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO case_submission (id, project_id, user_id, result_version_id, current_round, "
                            + "status, latest_revision_id, public_display_granted, generation_reference_granted, "
                            + "idempotency_key) "
                            + "VALUES (?, ?, ?, ?, 1, 'SUBMITTED', NULL, ?, ?, ?)",
                    submissionId, projectId, userId, resultVersionId,
                    publicDisplayGranted, generationReferenceGranted, idempotencyKey);
            jdbcTemplate.update(
                    "INSERT INTO submission_revision (id, submission_id, round_no, result_version_id, "
                            + "rights_snapshot, content_snapshot) "
                            + "VALUES (?, ?, 1, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                    IdWorker.getId(), submissionId, resultVersionId,
                    rightsSnapshot(publicDisplayGranted, generationReferenceGranted),
                    note == null ? null : mapToJson(Map.of("note", note)));
            jdbcTemplate.update(
                    "INSERT INTO review_task (id, submission_id, round_no, status) "
                            + "VALUES (?, ?, 1, 'PENDING')",
                    IdWorker.getId(), submissionId);
            return submissionId;
        });
    }

    private String mapToJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String rightsSnapshot(boolean publicDisplay, boolean generationReference) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "PUBLIC_DISPLAY", publicDisplay, "GENERATION_REFERENCE", generationReference));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 审核决定：投稿人自审拒绝；APPROVE 后仍需独立发布命令 */
    public void decide(long submissionId, long reviewerUserId, String decision, String comment) {
        if (decision == null || !List.of("APPROVE", "CHANGES_REQUESTED", "REJECT").contains(decision)) {
            throw exception(SUBMISSION_STATE_CONFLICT);
        }
        if ("CHANGES_REQUESTED".equals(decision) && (comment == null || comment.isBlank())) {
            throw new ServiceException(1_071_000_002, "退修必须填写修改意见");
        }
        txTemplate.execute(status -> {
            Map<String, Object> submission = jdbcTemplate.queryForMap(
                    "SELECT id, user_id, status, current_round FROM case_submission "
                            + "WHERE id = ? AND deleted = FALSE FOR UPDATE", submissionId);
            long ownerId = ((Number) submission.get("user_id")).longValue();
            if (ownerId == reviewerUserId) {
                throw exception(RESOURCE_FORBIDDEN); // 投稿人自审拒绝
            }
            String currentStatus = (String) submission.get("status");
            if (!List.of("SUBMITTED", "RESUBMITTED", "IN_REVIEW").contains(currentStatus)) {
                throw exception(SUBMISSION_STATE_CONFLICT);
            }
            int round = ((Number) submission.get("current_round")).intValue();
            Long taskId = jdbcTemplate.queryForObject(
                    "SELECT id FROM review_task WHERE submission_id = ? AND round_no = ? FOR UPDATE",
                    Long.class, submissionId, round);
            String decided = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM review_decision WHERE review_task_id = ?",
                    Integer.class, taskId) > 0 ? "DECIDED" : "PENDING";
            if ("DECIDED".equals(decided)) {
                throw exception(SUBMISSION_STATE_CONFLICT); // 本轮已决定
            }
            String next = switch (decision) {
                case "APPROVE" -> "APPROVED";
                case "CHANGES_REQUESTED" -> "CHANGES_REQUESTED";
                default -> "REJECTED";
            };
            jdbcTemplate.update(
                    "INSERT INTO review_decision (id, review_task_id, reviewer_user_id, decision, comment) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    IdWorker.getId(), taskId, reviewerUserId, decision, comment);
            jdbcTemplate.update(
                    "UPDATE review_task SET status = 'DECIDED', reviewer_id = ?, update_time = now() "
                            + "WHERE id = ?", String.valueOf(reviewerUserId), taskId);
            jdbcTemplate.update(
                    "UPDATE case_submission SET status = ?, update_time = now() WHERE id = ?",
                    next, submissionId);
            reliableEventPort.append(OutboxEventMessage.builder()
                    .eventType("SUBMISSION_REVIEWED").bizType("case_submission")
                    .bizId(String.valueOf(submissionId))
                    .payload(Map.of("submissionId", submissionId, "decision", decision,
                            "ownerUserId", ownerId)).build());
            return null;
        });
    }

    /** 退修后重提：仅 CHANGES_REQUESTED；新建 revision 轮次与新审核任务 */
    public long resubmit(long userId, long submissionId, String note) {
        return resubmit(userId, submissionId, note, null).submissionId();
    }

    public SubmissionRow resubmit(long userId, long submissionId, String note, String idempotencyKey) {
        if (note != null && note.length() > 512 || idempotencyKey != null && idempotencyKey.length() > 128) {
            throw new ServiceException(1_071_000_002, "重提说明或请求编号过长");
        }
        return txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            Map<String, Object> submission = jdbcTemplate.queryForMap(
                    "SELECT id, user_id, status, current_round, result_version_id, "
                            + "public_display_granted, generation_reference_granted FROM case_submission "
                            + "WHERE id = ? AND deleted = FALSE FOR UPDATE", submissionId);
            if (((Number) submission.get("user_id")).longValue() != userId) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            // 锁定投稿后读回执：响应丢失或并发重复请求均只创建一次轮次。
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                var receipts = jdbcTemplate.queryForList("SELECT content_snapshot::text, response_snapshot::text FROM submission_revision "
                        + "WHERE submission_id=? AND idempotency_key=?", submissionId, idempotencyKey);
                if (!receipts.isEmpty()) {
                    var receipt = receipts.get(0);
                    if (!java.util.Objects.equals(BudgetInputs.readJson((String) receipt.get("content_snapshot")).get("note"), note)) {
                        throw exception(SUBMISSION_STATE_CONFLICT);
                    }
                    return JsonUtils.parseObject((String) receipt.get("response_snapshot"), SubmissionRow.class);
                }
            }
            if (!"CHANGES_REQUESTED".equals(submission.get("status"))) {
                throw exception(SUBMISSION_STATE_CONFLICT);
            }
            int nextRound = ((Number) submission.get("current_round")).intValue() + 1;
            jdbcTemplate.update(
                    "INSERT INTO submission_revision (id, submission_id, round_no, result_version_id, "
                            + "rights_snapshot, content_snapshot) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                    IdWorker.getId(), submissionId, nextRound, submission.get("result_version_id"),
                    rightsSnapshot((Boolean) submission.get("public_display_granted"),
                            (Boolean) submission.get("generation_reference_granted")),
                    note == null ? null : mapToJson(Map.of("note", note)));
            jdbcTemplate.update(
                    "INSERT INTO review_task (id, submission_id, round_no, status) "
                            + "VALUES (?, ?, ?, 'PENDING')",
                    IdWorker.getId(), submissionId, nextRound);
            jdbcTemplate.update(
                    "UPDATE case_submission SET status = 'RESUBMITTED', current_round = ?, "
                            + "update_time = now() WHERE id = ?", nextRound, submissionId);
            SubmissionRow response = getMySubmission(userId, submissionId).orElseThrow();
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                jdbcTemplate.update("UPDATE submission_revision SET idempotency_key=?, response_snapshot=CAST(? AS jsonb) "
                        + "WHERE submission_id=? AND round_no=?", idempotencyKey, JsonUtils.toJsonString(response), submissionId, nextRound);
            }
            return response;
        });
    }

    public record PreviewAsset(String assetId, String role, String label) { }

    /** 只读取冻结选择，不读取项目当前选择或所有历史候选。 */
    public List<PreviewAsset> previewAssets(long resultVersionId) {
        return jdbcTemplate.query("SELECT c.asset_id,s.stage FROM design_result_version v "
                        + "JOIN design_selection s ON s.id IN (v.flat_selection_id,v.elevation_selection_id) "
                        + "AND s.project_id=v.project_id AND s.deleted=FALSE "
                        + "JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=v.project_id AND c.deleted=FALSE "
                        + "WHERE v.id=? AND v.deleted=FALSE ORDER BY s.stage DESC",
                (rs, i) -> new PreviewAsset(rs.getString("asset_id"), rs.getString("stage"),
                        "FLAT".equals(rs.getString("stage")) ? "选定平面方案" : "选定立面效果"), resultVersionId);
    }

    private Map<String, Object> publicationParameters(long resultVersionId) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        var snapshots = jdbcTemplate.queryForList("SELECT s.inputs::text FROM design_requirement_snapshot s "
                + "JOIN design_result_version v ON v.project_id=s.project_id WHERE v.id=? AND s.deleted=FALSE "
                + "AND s.create_time<=v.create_time ORDER BY s.input_version,s.id", resultVersionId);
        snapshots.forEach(s -> fields.putAll(BudgetInputs.readJson((String) s.get("inputs"))));
        fields.putAll(BudgetInputs.readJson(jdbcTemplate.queryForObject(
                "SELECT config_snapshot::text FROM design_result_version WHERE id=?", String.class, resultVersionId)));
        if (!fields.containsKey("floorCount") && fields.containsKey("floors")) fields.put("floorCount", fields.get("floors"));
        var resolved = new java.util.LinkedHashMap<String, Object>(BudgetInputs.resolve(BudgetInputs.fromSnapshot(fields), Map.of()).values());
        Object style = fields.get("styleCode");
        if (style == null) {
            var styles = jdbcTemplate.queryForList("SELECT cv.style_code FROM design_result_version v JOIN design_project p ON p.id=v.project_id "
                    + "JOIN design_case_version cv ON cv.id=p.ref_version_id WHERE v.id=?", String.class, resultVersionId);
            if (!styles.isEmpty()) style = styles.get(0);
        }
        if (style == null || style.toString().isBlank() || !resolved.containsKey("buildingArea") || !resolved.containsKey("floorCount")) {
            throw new ServiceException(1_071_000_002, "投稿版本缺少建筑面积、层数或风格，不能使用默认参数发布");
        }
        resolved.put("styleCode", style);
        return resolved;
    }

    /**
     * 独立发布命令：APPROVED + 公开展示许可有效 → 创建 AI 案例并上架（复用 P3B 发布事实）；
     * 用户被授予的生成参考许可落为案例平面图资产的 GENERATION_REFERENCE 授权（不随审核自动获得）。
     */
    public long publishApprovedAsCase(long submissionId, String operator) {
        return publishApprovedAsCase(submissionId, operator, null);
    }

    /** P1-A（报告 15）：投稿版本面积为小数时不接受静默截断，必须由管理员核定整数面积（confirmedBuildingArea）后发布，核定事实留痕入案例描述。 */
    public long publishApprovedAsCase(long submissionId, String operator, Integer confirmedBuildingArea) {
        return txTemplate.execute(status -> {
            // Account before submission: a queued admin action cannot republish a closed account.
            var owners = jdbcTemplate.queryForList("SELECT user_id FROM case_submission WHERE id=? AND deleted=FALSE", Long.class, submissionId);
            if (owners.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(owners.get(0));
            Map<String, Object> submission = jdbcTemplate.queryForMap(
                    "SELECT id, user_id, status, result_version_id, public_display_granted, generation_reference_granted "
                            + "FROM case_submission WHERE id = ? AND deleted = FALSE FOR UPDATE", submissionId);
            if (!"APPROVED".equals(submission.get("status"))) {
                throw exception(SUBMISSION_STATE_CONFLICT);
            }
            if (!Boolean.TRUE.equals(submission.get("public_display_granted"))) {
                throw exception(PUBLICATION_VALIDATION_FAILED);
            }
            Long alreadyPublished = jdbcTemplate.queryForObject(
                    "SELECT published_case_id FROM case_submission WHERE id = ? FOR UPDATE",
                    Long.class, submissionId);
            if (alreadyPublished != null) {
                throw exception(SUBMISSION_STATE_CONFLICT); // 已发布过：重复发布拒绝
            }
            long userId = ((Number) submission.get("user_id")).longValue();
            long resultVersionId = ((Number) submission.get("result_version_id")).longValue();
            var parameters = publicationParameters(resultVersionId);
            String styleCode = parameters.get("styleCode").toString();
            // 案例目录的既有面积字段为整数平方米。不能静默截断小数或填假值；
            // 小数面积必须由管理员核定（confirmedBuildingArea），核定事实写入案例描述留痕。
            int buildingArea;
            String areaConfirmationNote = null;
            java.math.BigDecimal snapshotArea = new java.math.BigDecimal(parameters.get("buildingArea").toString());
            try { buildingArea = snapshotArea.intValueExact(); }
            catch (ArithmeticException e) {
                if (confirmedBuildingArea == null || confirmedBuildingArea <= 0) {
                    throw new ServiceException(1_071_000_002, "案例目录暂仅支持整数平方米，请先核定投稿版本面积");
                }
                buildingArea = confirmedBuildingArea;
                areaConfirmationNote = "；建筑面积经管理员核定 " + snapshotArea.stripTrailingZeros().toPlainString()
                        + "㎡ → " + buildingArea + "㎡";
            }
            int floorCount = ((Number) parameters.get("floorCount")).intValue();
            var selected = previewAssets(resultVersionId);
            if (selected.size() != 2 || selected.stream().map(PreviewAsset::role).distinct().count() != 2) {
                throw exception(PUBLICATION_VALIDATION_FAILED);
            }
            for (var asset : selected) {
                Integer usable = jdbcTemplate.queryForObject("SELECT count(*) FROM asset WHERE id=? AND owner_user_id=? "
                        + "AND upload_status='ACCEPTED' AND deleted=FALSE", Integer.class, Long.parseLong(asset.assetId()), userId);
                if (usable == null || usable == 0) throw exception(PUBLICATION_VALIDATION_FAILED);
            }
            long caseId = IdWorker.getId();
            long versionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_case (id, source_type, creator_user_id, current_version_id, "
                            + "publication_status) VALUES (?, 'AI', ?, ?, 'PUBLISHED')",
                    caseId, userId, versionId);
            jdbcTemplate.update(
                    "INSERT INTO design_case_version (id, case_id, version, title, description, style_code, "
                            + "floor_count, building_area) VALUES (?, ?, 1, ?, ?, ?, ?, ?)",
                    versionId, caseId, aiCaseTitle(styleCode, buildingArea, submissionId),
                    "用户投稿（结果版本 " + resultVersionId + "）"
                            + (areaConfirmationNote == null ? "" : areaConfirmationNote),
                    styleCode, floorCount, buildingArea);
            jdbcTemplate.update(
                    "INSERT INTO case_publication (id, case_id, action, published_version_id, operator_id) "
                            + "VALUES (?, ?, 'PUBLISH', ?, ?)",
                    IdWorker.getId(), caseId, versionId, operator);
            jdbcTemplate.update(
                    "UPDATE case_submission SET published_case_id = ?, update_time = now() WHERE id = ?",
                    caseId, submissionId);
            for (var asset : selected) {
                long candidateAssetId = Long.parseLong(asset.assetId());
                String role = "FLAT".equals(asset.role()) ? "FLOOR_PLAN" : "ELEVATION";
                jdbcTemplate.update(
                        "INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        IdWorker.getId(), versionId, candidateAssetId, role,
                        role.equals("FLOOR_PLAN") ? 1 : null);
                if ("ELEVATION".equals(role)) {
                    jdbcTemplate.update("INSERT INTO design_case_asset (id,case_version_id,asset_id,asset_role) VALUES (?,?,?,'COVER')",
                            IdWorker.getId(), versionId, candidateAssetId);
                }
            }
            // 用户授予的生成参考许可 → 案例平面资产的显式授权记录（审查 C5 缺口 #3：asset_id 必须是真实资产）
            if ((Boolean) submission.get("generation_reference_granted")) {
                for (var asset : selected) {
                    jdbcTemplate.update(
                            "INSERT INTO asset_rights_grant (id, asset_id, grantor_user_id, rights_holder, "
                                    + "scope, territories, purposes, effective_at) "
                            + "VALUES (?, ?, ?, ?, 'GENERATION_REFERENCE', '*', '*', now())",
                            IdWorker.getId(), Long.parseLong(asset.assetId()), userId, "投稿用户");
                }
            }
            reliableEventPort.append(OutboxEventMessage.builder()
                    .eventType("SUBMISSION_PUBLISHED").bizType("case_submission")
                    .bizId(String.valueOf(submissionId))
                    .payload(Map.of("submissionId", submissionId, "caseId", caseId, "userId", userId)).build());
            log.info("[publishApprovedAsCase][submission={} → AI 案例 {}]", submissionId, caseId);
            return caseId;
        });
    }

    /** P3-D（报告 15）：图库可读标题——风格 + 核定面积 + 投稿短码，替代不可读的「AI 案例 · 投稿 <ID 串>」。 */
    private String aiCaseTitle(String styleCode, int buildingArea, long submissionId) {
        var labels = java.util.Map.of(
                "NEW_CHINESE", "新中式", "MODERN", "现代简约", "CHINESE", "中式", "EUROPEAN", "欧式");
        String style = labels.getOrDefault(styleCode, styleCode);
        String shortCode = String.valueOf(submissionId);
        shortCode = shortCode.length() <= 6 ? shortCode : shortCode.substring(shortCode.length() - 6);
        return "AI 案例 · " + style + " · " + buildingArea + "㎡ · #" + shortCode;
    }

}
