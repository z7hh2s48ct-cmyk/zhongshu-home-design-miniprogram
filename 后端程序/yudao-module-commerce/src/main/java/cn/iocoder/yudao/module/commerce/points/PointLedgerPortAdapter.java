package cn.iocoder.yudao.module.commerce.points;

import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import org.springframework.stereotype.Component;

/**
 * PointLedgerPort 的 commerce 实现（复用 PointAccountService；同事务语义随调用方事务传播）
 */
@Component
public class PointLedgerPortAdapter implements PointLedgerPort {
    @Override public void reserve(long userId, long amount, String bizType, String bizId) { pointAccountService.reserve(userId, amount, bizType, bizId); }
    @Override public void releaseReserve(long userId, long amount) { pointAccountService.releaseReserve(userId, amount); }
    @Override public long consumeReserve(long userId, long amount, String type, String bizType, String bizId, String key) {
        return pointAccountService.consumeReserve(userId, amount, type, bizType, bizId, key);
    }

    private final PointAccountService pointAccountService;

    public PointLedgerPortAdapter(PointAccountService pointAccountService) {
        this.pointAccountService = pointAccountService;
    }

    @Override
    public long credit(long userId, String type, long delta, String bizType, String bizId,
                       String idempotencyKey, String operatorId, String reason) {
        return pointAccountService.credit(userId, type, delta, bizType, bizId,
                idempotencyKey, operatorId, reason);
    }

    @Override
    public long debit(long userId, String type, long amount, String bizType, String bizId,
                      String idempotencyKey, String operatorId, String reason) {
        return pointAccountService.debit(userId, type, amount, bizType, bizId,
                idempotencyKey, operatorId, reason);
    }

}
