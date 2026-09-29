package cn.iocoder.yudao.module.commerce.pricing;

import lombok.Value;

import java.time.Instant;

/**
 * 计价快照：任务创建时冻结（规则、单价、数量、总价），写入任务与扣点流水
 */
@Value
public class PriceRuleQuote {

    long ruleId;

    long ruleVersion;

    /** FLAT / ELEVATION */
    String stage;

    /** 2K / 4K */
    String resolution;

    long unitPointCost;

    int count;

    /** total = unitPointCost × count */
    long totalPointCost;

    Instant effectiveAt;

    public PriceRuleQuote(long ruleId, long ruleVersion, String stage, String resolution,
                          long unitPointCost, int count, long totalPointCost, Instant effectiveAt) {
        this.ruleId = ruleId;
        this.ruleVersion = ruleVersion;
        this.stage = stage;
        this.resolution = resolution;
        this.unitPointCost = unitPointCost;
        this.count = count;
        this.totalPointCost = totalPointCost;
        this.effectiveAt = effectiveAt;
    }

    public PriceRuleQuote(long ruleId, long ruleVersion, String stage, long unitPointCost,
                          int count, long totalPointCost, Instant effectiveAt) {
        this(ruleId, ruleVersion, stage, "2K", unitPointCost, count, totalPointCost, effectiveAt);
    }

}
