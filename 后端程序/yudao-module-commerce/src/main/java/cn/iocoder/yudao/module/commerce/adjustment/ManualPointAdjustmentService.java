package cn.iocoder.yudao.module.commerce.adjustment;

import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;

/**
 * 人工调点：制单—复核—执行（架构 §6.5 / §10.7）
 *
 * 合同：
 * - 任何角色都不能直接 UPDATE 余额；人工增减点只能走调整单；
 * - maker_user_id != checker_user_id（生产环境所有人工调点适用，不因金额小绕过）；
 * - 复核通过即执行：一个事务内写调整单终态、账本流水、账户余额与审计事件；
 * - 执行以 adjustmentId 为幂等键，重复执行返回既有流水，不重复调点；
 * - 已执行的调整只能用反向调整单纠正，禁止修改/删除原流水。
 */
@Slf4j
@Service
public class ManualPointAdjustmentService {

    public record AdjustmentRow(long id, long targetUserId, long delta, String reason, String status,
                                long makerUserId, Long checkerUserId, String checkerComment,
                                Long ledgerId) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final PointAccountService pointAccountService;

    private final AuditPort auditPort;

    public ManualPointAdjustmentService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                        PointAccountService pointAccountService, AuditPort auditPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.pointAccountService = pointAccountService;
        this.auditPort = auditPort;
    }

    /** 制单并直接提交复核（P0 不单独保留草稿态操作入口） */
    public long submit(long targetUserId, long delta, String reason, long makerUserId) {
        if (delta == 0) {
            throw new IllegalArgumentException("调整点数不能为 0");
        }
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO manual_point_adjustment (id, target_user_id, delta, reason, status, maker_user_id) "
                        + "VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
                id, targetUserId, delta, reason, makerUserId);
        return id;
    }

    /**
     * 2026-10-02 运营决策：人工调点单人操作即可，管理员确认后直接生效。
     * 创建即执行：单据以 APPROVED 落库（maker=checker 同人）并同事务入账；
     * 幂等键仍为 adjustmentId，重复调用不会重复调点。
     */
    public long submitAndExecute(long targetUserId, long delta, String reason, long operator, String requestKey) {
        if (delta == 0) {
            throw new IllegalArgumentException("Adjustment delta must not be zero");
        }
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128) {
            throw new IllegalArgumentException("requestKey must contain 1-128 characters");
        }
        try {
            return txTemplate.execute(status -> {
                long id = IdWorker.getId();
                jdbcTemplate.update(
                        "INSERT INTO manual_point_adjustment (id, target_user_id, delta, reason, status, "
                                + "maker_user_id, checker_user_id, checker_comment, request_key) "
                                + "VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, 'Admin direct execution', ?)",
                        id, targetUserId, delta, reason, operator, operator, requestKey);
                AdjustmentRow row = new AdjustmentRow(id, targetUserId, delta, reason, "APPROVED",
                        operator, operator, "Admin direct execution", null);
                executeApproved(id, row, operator);
                return id;
            });
        } catch (DuplicateKeyException duplicate) {
            // The unique index waits for an in-flight request to commit. Read its durable result
            // after the losing insert has rolled back.
            return replayedAdjustment(targetUserId, delta, reason, operator, requestKey, duplicate);
        }
    }

    private long replayedAdjustment(long targetUserId, long delta, String reason, long operator,
                                    String requestKey, DuplicateKeyException duplicate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, target_user_id, delta, reason, status, ledger_id "
                        + "FROM manual_point_adjustment WHERE maker_user_id = ? AND request_key = ?",
                operator, requestKey);
        if (rows.isEmpty()) {
            throw duplicate;
        }
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("target_user_id")).longValue() != targetUserId
                || ((Number) row.get("delta")).longValue() != delta
                || !reason.equals(row.get("reason"))) {
            throw new IllegalArgumentException("requestKey was already used for another adjustment");
        }
        if (!"EXECUTED".equals(row.get("status")) || row.get("ledger_id") == null) {
            throw new IllegalStateException("Adjustment request has not completed");
        }
        return ((Number) row.get("id")).longValue();
    }

    /**
     * 复核：approve=true 时在同一事务内完成「APPROVED→EXECUTED + 流水 + 余额 + 审计」；
     * approve=false 走 REJECTED。
     */
    public Long review(long adjustmentId, long checkerUserId, boolean approve, String comment) {
        return txTemplate.execute(status -> {
            AdjustmentRow row = lockById(adjustmentId);
            if (row.makerUserId() == checkerUserId) {
                // 制单人与复核人必须不同：同人复核按“无权执行该动作”拒绝
                throw exception(RESOURCE_FORBIDDEN);
            }
            if (!"SUBMITTED".equals(row.status())) {
                throw new IllegalStateException("调整单状态不允许复核: " + row.status());
            }
            if (!approve) {
                jdbcTemplate.update(
                        "UPDATE manual_point_adjustment SET status = 'REJECTED', checker_user_id = ?, "
                                + "checker_comment = ?, update_time = now() WHERE id = ?",
                        checkerUserId, comment, adjustmentId);
                audit(adjustmentId, checkerUserId, row, "REJECT", AuditEventMessage.AuditResult.SUCCESS);
                return null;
            }
            jdbcTemplate.update(
                    "UPDATE manual_point_adjustment SET status = 'APPROVED', checker_user_id = ?, "
                            + "checker_comment = ?, update_time = now() WHERE id = ?",
                    checkerUserId, comment, adjustmentId);
            return executeApproved(adjustmentId, row, checkerUserId);
        });
    }

    /** 独立执行入口：APPROVED → EXECUTED；幂等（重复执行返回既有流水 ID） */
    public Long execute(long adjustmentId) {
        return txTemplate.execute(status -> {
            AdjustmentRow row = lockById(adjustmentId);
            if ("EXECUTED".equals(row.status())) {
                return row.ledgerId();
            }
            if (!"APPROVED".equals(row.status())) {
                throw new IllegalStateException("调整单状态不允许执行: " + row.status());
            }
            return executeApproved(adjustmentId, row,
                    row.checkerUserId() == null ? 0L : row.checkerUserId());
        });
    }

    // ========== 内部 ==========

    /** 复核通过后的执行：写流水 → 回填 ledger_id/EXECUTED → 审计，同一事务 */
    private Long executeApproved(long adjustmentId, AdjustmentRow row, long checkerUserId) {
        String idempotencyKey = "MANUAL_ADJUSTMENT:" + adjustmentId;
        String ledgerType = row.delta() > 0 ? "MANUAL_CREDIT" : "MANUAL_DEBIT";
        long ledgerId = row.delta() > 0
                ? pointAccountService.credit(row.targetUserId(), ledgerType, row.delta(),
                "MANUAL_ADJUSTMENT", String.valueOf(adjustmentId), idempotencyKey,
                String.valueOf(row.makerUserId()), row.reason())
                : pointAccountService.debit(row.targetUserId(), ledgerType, -row.delta(),
                "MANUAL_ADJUSTMENT", String.valueOf(adjustmentId), idempotencyKey,
                String.valueOf(row.makerUserId()), row.reason());
        jdbcTemplate.update(
                "UPDATE manual_point_adjustment SET status = 'EXECUTED', ledger_id = ?, executed_at = now(), "
                        + "update_time = now() WHERE id = ? AND status = 'APPROVED'",
                ledgerId, adjustmentId);
        audit(adjustmentId, checkerUserId, row, "EXECUTE", AuditEventMessage.AuditResult.SUCCESS);
        log.info("[executeApproved][adjustment={} ledger={} delta={} target={}]",
                adjustmentId, ledgerId, row.delta(), row.targetUserId());
        return ledgerId;
    }

    private AdjustmentRow lockById(long adjustmentId) {
        List<AdjustmentRow> rows = jdbcTemplate.query(
                "SELECT id, target_user_id, delta, reason, status, maker_user_id, checker_user_id, "
                        + "checker_comment, ledger_id FROM manual_point_adjustment "
                        + "WHERE id = ? FOR UPDATE",
                (rs, i) -> new AdjustmentRow(rs.getLong("id"), rs.getLong("target_user_id"),
                        rs.getLong("delta"), rs.getString("reason"), rs.getString("status"),
                        rs.getLong("maker_user_id"),
                        rs.getObject("checker_user_id") == null ? null : rs.getLong("checker_user_id"),
                        rs.getString("checker_comment"),
                        rs.getObject("ledger_id") == null ? null : rs.getLong("ledger_id")),
                adjustmentId);
        if (rows.isEmpty()) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return rows.get(0);
    }

    private void audit(long adjustmentId, long checkerUserId, AdjustmentRow row,
                       String action, AuditEventMessage.AuditResult result) {
        auditPort.record(AuditEventMessage.builder()
                .eventType("MANUAL_POINT_ADJUSTMENT").actorType(AuditEventMessage.ActorType.ADMIN)
                .actorId(String.valueOf(checkerUserId)).action(action)
                .bizType("manual_point_adjustment").bizId(String.valueOf(adjustmentId))
                .result(result)
                .detail(Map.of("targetUserId", row.targetUserId(), "delta", row.delta(),
                        "makerUserId", row.makerUserId()))
                .build());
    }

}
