package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.Builder;
import lombok.Value;

/**
 * 一次性票据消费结果
 */
@Value
@Builder
public class TicketConsumption {

    public enum Outcome {
        /** 首次消费成功 */
        CONSUMED_NOW,
        /** token 不存在 */
        UNKNOWN,
        /** 已被消费，不可重放 */
        ALREADY_CONSUMED,
        /** 已过期 */
        EXPIRED,
        /** 已吊销 */
        REVOKED
    }

    Outcome outcome;

    /** 消费成功时返回票据绑定的业务对象（批次号/导出任务号/资产号） */
    String bizRef;

    String purpose;

}
