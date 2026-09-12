package cn.iocoder.yudao.module.commerce.points;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.POINTS_INSUFFICIENT;

/**
 * 设计点账户与只追加流水（架构 §6.5）
 *
 * 合同：
 * - 账户是唯一真源（available + reserved，version 乐观锁展示用），Redis 不参与判定；
 * - 任何余额变化 = 一个事务内的「一条条件 UPDATE（行锁原子）+ 一条只追加流水」；
 * - 预留（可用→预留）与释放只做账户内转移，总余额不变，因此不写流水；
 * - 总余额不变量：available + reserved = 初始值 + Σ流水 delta；
 * - idempotencyKey 命中时直接返回既有流水，不重复记账。
 */
@Slf4j
@Service
public class PointAccountService {
    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort accountStatePort;

    public record PointAccount(long userId, long availablePoints, long reservedPoints, long version) {
    }

    public record LedgerRow(long id, long userId, String type, long delta,
                            long availableAfter, long reservedAfter,
                            String bizType, String bizId, String operatorId, String reason,
                            Instant createTime) {
    }

    private record BalanceAfter(long available, long reserved) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    public PointAccountService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ========== 查询 ==========

    public Optional<PointAccount> findAccount(long userId) {
        List<PointAccount> rows = jdbcTemplate.query(
                "SELECT user_id, available_points, reserved_points, version "
                        + "FROM design_point_account WHERE user_id = ?",
                (rs, i) -> new PointAccount(rs.getLong("user_id"), rs.getLong("available_points"),
                        rs.getLong("reserved_points"), rs.getLong("version")),
                userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 查询不存在则创建零账户（首次登录/充值场景） */
    public PointAccount ensureAccount(long userId) {
        Optional<PointAccount> existing = findAccount(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        return txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO design_point_account (id, user_id) VALUES (?, ?) "
                            + "ON CONFLICT (user_id) DO NOTHING",
                    IdWorker.getId(), userId);
            return findAccount(userId).orElseThrow(() -> new IllegalStateException("点数账户创建失败: " + userId));
        });
    }

    // ========== 变更（幂等） ==========

    /** 入账（充值/人工调增等，delta > 0），返回流水 ID */
    public long credit(long userId, String type, long delta, String bizType, String bizId,
                       String idempotencyKey, String operatorId, String reason) {
        if (delta <= 0) {
            throw new IllegalArgumentException("credit delta 必须为正: " + delta);
        }
        Long replayed = findByKey(idempotencyKey);
        if (replayed != null) {
            return replayed;
        }
        try {
            return txTemplate.execute(status -> {
                // Manual grants are new business writes; payment/refund replay remains governed by its order state.
                if ("MANUAL_CREDIT".equals(type) && accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
                ensureAccountInTx(userId);
                BalanceAfter after = updateBalance(userId, "available_points = available_points + ?", delta);
                return insertLedger(userId, type, delta, after, bizType, bizId, idempotencyKey, operatorId, reason);
            });
        } catch (DuplicateKeyException e) {
            // 嵌套在外层业务事务中：事务仍活跃，不能查库——重抛给外层整体回滚后按各自幂等键重读；
            // 独立事务：本方法事务已回滚，查既有流水安全；查不到则原样抛出
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()) {
                throw e;
            }
            Long existing = findByKey(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    /** 扣减可用点（任务扣点/人工调减，amount > 0）；余额不足抛 POINTS_INSUFFICIENT，返回流水 ID */
    public long debit(long userId, String type, long amount, String bizType, String bizId,
                      String idempotencyKey, String operatorId, String reason) {
        if (amount <= 0) {
            throw new IllegalArgumentException("debit amount 必须为正: " + amount);
        }
        Long replayed = findByKey(idempotencyKey);
        if (replayed != null) {
            return replayed;
        }
        try {
            return txTemplate.execute(status -> {
                if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
                ensureAccountInTx(userId);
                BalanceAfter after = updateBalanceGuarded(userId,
                        "available_points = available_points - ?", amount, "available_points >= ?");
                if (after == null) {
                    throw exception(POINTS_INSUFFICIENT);
                }
                return insertLedger(userId, type, -amount, after, bizType, bizId, idempotencyKey, operatorId, reason);
            });
        } catch (DuplicateKeyException e) {
            // 嵌套在外层业务事务中：事务仍活跃，不能查库——重抛给外层整体回滚后按各自幂等键重读；
            // 独立事务：本方法事务已回滚，查既有流水安全；查不到则原样抛出
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()) {
                throw e;
            }
            Long existing = findByKey(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    /**
     * 预留：可用 → 预留转移（总余额不变，不写流水；退款受理等场景使用）。
     * 非幂等原语：重复调用会重复转移。调用方必须在自己的幂等业务事务内调用
     * （例如以退款单的 refund_request_key 唯一约束先占坑，再执行 reserve）。
     */
    public void reserve(long userId, long amount, String bizType, String bizId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("reserve amount 必须为正: " + amount);
        }
        txTemplate.execute(status -> {
            ensureAccountInTx(userId);
            BalanceAfter after = updateBalanceGuarded(userId,
                    "available_points = available_points - ?, reserved_points = reserved_points + ?", amount,
                    "available_points >= ?");
            if (after == null) {
                throw exception(POINTS_INSUFFICIENT);
            }
            log.info("[reserve][user={} amount={} after={}/{} biz={}/{}]",
                    userId, amount, after.available(), after.reserved(), bizType, bizId);
            return null;
        });
    }

    /**
     * 预留转冲正（渠道退款成功）：预留点直接扣减、总余额减少，并写两类冲正流水（同一事务）。
     * 幂等由调用方以 bizId+幂等键保证。
     */
    public void consumeReserveWithReversal(long userId, long baseAmount, long bonusAmount,
                                           String bizType, String bizId,
                                           String idemBase, String idemBonus) {
        long total = baseAmount + bonusAmount;
        txTemplate.execute(status -> {
            BalanceAfter after = updateBalanceGuarded(userId,
                    "reserved_points = reserved_points - ?", total, "reserved_points >= ?");
            if (after == null) {
                throw exception(POINTS_INSUFFICIENT);
            }
            insertLedger(userId, "RECHARGE_BASE_REVERSAL", -baseAmount, after,
                    bizType, bizId, idemBase, null, "渠道退款成功冲正基础点");
            insertLedger(userId, "RECHARGE_BONUS_REVERSAL", -bonusAmount, after,
                    bizType, bizId, idemBonus, null, "渠道退款成功冲正赠送点");
            return null;
        });
    }

    /** 释放预留：预留 → 可用转移（渠道明确失败等场景），不写流水 */
    public void releaseReserve(long userId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("release amount 必须为正: " + amount);
        }
        txTemplate.execute(status -> {
            BalanceAfter after = updateBalanceGuarded(userId,
                    "reserved_points = reserved_points - ?, available_points = available_points + ?", amount,
                    "reserved_points >= ?");
            if (after == null) {
                throw exception(POINTS_INSUFFICIENT);
            }
            return null;
        });
    }

    // ========== 流水查询 ==========

    public long countLedger(Long userId, String type) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        List<Object> args = new java.util.ArrayList<>();
        if (userId != null) {
            where.append(" AND user_id = ?");
            args.add(userId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND type = ?");
            args.add(type);
        }
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM design_point_ledger" + where,
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public List<LedgerRow> pageLedger(Long userId, String type, int pageNo, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        List<Object> args = new java.util.ArrayList<>();
        if (userId != null) {
            where.append(" AND user_id = ?");
            args.add(userId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND type = ?");
            args.add(type);
        }
        args.add(pageSize);
        args.add((long) Math.max(pageNo - 1, 0) * pageSize);
        return jdbcTemplate.query(
                "SELECT id, user_id, type, delta, available_after, reserved_after, biz_type, biz_id, "
                        + "operator_id, reason, create_time FROM design_point_ledger" + where
                        + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("type"),
                        rs.getLong("delta"), rs.getLong("available_after"), rs.getLong("reserved_after"),
                        rs.getString("biz_type"), rs.getString("biz_id"),
                        rs.getString("operator_id"), rs.getString("reason"),
                        toInstant(rs.getTimestamp("create_time"))),
                args.toArray());
    }

    // ========== 内部实现（须在事务内调用） ==========

    private void ensureAccountInTx(long userId) {
        // ON CONFLICT DO NOTHING 遇到未提交的并发首建会等待其提交，
        // 因此返回 0 行时账户行必然已存在且可见，无需额外加锁（FOR SHARE 会造成并发扣点锁升级死锁）。
        jdbcTemplate.update(
                "INSERT INTO design_point_account (id, user_id) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING",
                IdWorker.getId(), userId);
    }

    private BalanceAfter updateBalance(long userId, String setClause, long delta) {
        Object[] args = expandDeltaArgs(userId, setClause, delta, null);
        List<BalanceAfter> rows = jdbcTemplate.query(
                "UPDATE design_point_account SET " + setClause
                        + ", version = version + 1, update_time = now() "
                        + "WHERE user_id = ? RETURNING available_points, reserved_points",
                (rs, i) -> new BalanceAfter(rs.getLong(1), rs.getLong(2)), args);
        return rows.get(0);
    }

    /** 守卫不满足返回 null（不抛异常，由调用方决定错误码） */
    private BalanceAfter updateBalanceGuarded(long userId, String setClause, long delta, String guard) {
        Object[] args = expandDeltaArgs(userId, setClause, delta, guard);
        List<BalanceAfter> rows = jdbcTemplate.query(
                "UPDATE design_point_account SET " + setClause
                        + ", version = version + 1, update_time = now() "
                        + "WHERE user_id = ? AND " + guard + " "
                        + "RETURNING available_points, reserved_points",
                (rs, i) -> new BalanceAfter(rs.getLong(1), rs.getLong(2)), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** setClause 里每个 ? 都绑定同一个 delta，随后绑定 userId；有 guard 时最后绑定 guard 的 delta */
    private Object[] expandDeltaArgs(long userId, String setClause, long delta, String guard) {
        int placeholders = 0;
        for (char c : setClause.toCharArray()) {
            if (c == '?') {
                placeholders++;
            }
        }
        List<Object> args = new java.util.ArrayList<>(placeholders + 2);
        for (int i = 0; i < placeholders; i++) {
            args.add(delta);
        }
        args.add(userId);
        if (guard != null) {
            args.add(delta);
        }
        return args.toArray();
    }

    private long insertLedger(long userId, String type, long delta, BalanceAfter after,
                              String bizType, String bizId, String idempotencyKey,
                              String operatorId, String reason) {
        long ledgerId = IdWorker.getId();
        // 并发同幂等键：DuplicateKeyException 穿出事务，由 credit/debit 在自身事务回滚后查既有流水返回
        jdbcTemplate.update(
                "INSERT INTO design_point_ledger (id, user_id, type, delta, available_after, reserved_after, "
                        + "biz_type, biz_id, idempotency_key, operator_id, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ledgerId, userId, type, delta, after.available(), after.reserved(),
                bizType, bizId, idempotencyKey, operatorId, reason);
        return ledgerId;
    }

    private Long findByKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM design_point_ledger WHERE idempotency_key = ?",
                (rs, i) -> rs.getLong("id"), idempotencyKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

}
