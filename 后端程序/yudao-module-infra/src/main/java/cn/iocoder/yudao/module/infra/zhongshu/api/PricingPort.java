package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 计价端口（commerce 实现）：报价冻结快照 + 扣点前失效校验（PRICE_RULE_CHANGED）
 */
public interface PricingPort {

    record PriceConfirmation(String ruleId, long ruleVersion) { }

    static void requireConfirmed(PriceSnapshot snapshot, PriceConfirmation confirmation) {
        if (confirmation != null && (!String.valueOf(snapshot.ruleId()).equals(confirmation.ruleId())
                || snapshot.ruleVersion() != confirmation.ruleVersion())) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1_072_000_001, "计价规则已更新，请确认新价格后重试");
        }
    }

    PriceSnapshot quote(String stage, int count);

    void validateSnapshotStillValid(PriceSnapshot snapshot);

    record PriceSnapshot(long ruleId, long ruleVersion, String stage,
                         long unitPointCost, int count, long totalPointCost) {
    }

}
