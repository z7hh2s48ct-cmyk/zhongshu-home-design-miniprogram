package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 点数账本端口（commerce 实现）：全部资金变化必须走只追加流水 + 同事务余额更新
 *
 * type 取值须为 commerce PointLedgerTypeEnum 之一（RECHARGE_BASE_CREDIT/.../TASK_SETTLEMENT_REFUND/MANUAL_*）。
 * 幂等：同 idempotencyKey 重放返回既有流水，不重复记账。
 */
public interface PointLedgerPort {
    default void reserve(long userId, long amount, String bizType, String bizId) { throw new UnsupportedOperationException("Reservation not wired"); }
    default void releaseReserve(long userId, long amount) { throw new UnsupportedOperationException("Reservation not wired"); }
    default long consumeReserve(long userId, long amount, String type, String bizType, String bizId, String key) { throw new UnsupportedOperationException("Reservation not wired"); }

    /** 入账（delta > 0），返回流水 ID */
    long credit(long userId, String type, long delta, String bizType, String bizId,
                String idempotencyKey, String operatorId, String reason);

    /** 扣减可用点（amount > 0），余额不足抛 POINTS_INSUFFICIENT，返回流水 ID */
    long debit(long userId, String type, long amount, String bizType, String bizId,
               String idempotencyKey, String operatorId, String reason);

}
