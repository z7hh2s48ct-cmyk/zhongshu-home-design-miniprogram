package cn.iocoder.yudao.module.commerce.pricing;

import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * PricingPort 的 commerce 实现（复用 PriceRuleService）
 */
@Component
public class PricingPortAdapter implements PricingPort {

    private final PriceRuleService priceRuleService;

    public PricingPortAdapter(PriceRuleService priceRuleService) {
        this.priceRuleService = priceRuleService;
    }

    @Override
    public PriceSnapshot quote(String stage, int count, String resolution) {
        var quote = priceRuleService.quote(stage, resolution, count, Instant.now());
        return new PriceSnapshot(quote.getRuleId(), quote.getRuleVersion(), quote.getStage(), quote.getResolution(),
                quote.getUnitPointCost(), quote.getCount(), quote.getTotalPointCost());
    }

    @Override
    public void validateSnapshotStillValid(PriceSnapshot snapshot) {
        priceRuleService.validateSnapshotStillValid(new PriceRuleQuote(
                snapshot.ruleId(), snapshot.ruleVersion(), snapshot.stage(),
                snapshot.resolution(),
                snapshot.unitPointCost(), snapshot.count(), snapshot.totalPointCost(),
                Instant.now()), Instant.now());
    }

}
