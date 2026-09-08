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

    long unitPointCost;

    int count;

    /** total = unitPointCost × count */
    long totalPointCost;

    Instant effectiveAt;

}
