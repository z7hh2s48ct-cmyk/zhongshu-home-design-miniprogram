package cn.iocoder.yudao.module.commerce.enums;

/**
 * 渠道退款状态（独立于 PaymentState）
 */
public enum ChannelRefundStateEnum {

    CREATED,
    PROCESSING,
    SUCCEEDED,
    /** 主动查单后回 PROCESSING */
    UNKNOWN,
    FAILED

}
