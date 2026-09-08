package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 计价端口（commerce 实现）：报价冻结快照 + 扣点前失效校验（PRICE_RULE_CHANGED）
 */
public interface PricingPort {

    PriceSnapshot quote(String stage, int count);

    void validateSnapshotStillValid(PriceSnapshot snapshot);

    record PriceSnapshot(long ruleId, long ruleVersion, String stage,
                         long unitPointCost, int count, long totalPointCost) {
    }

}
