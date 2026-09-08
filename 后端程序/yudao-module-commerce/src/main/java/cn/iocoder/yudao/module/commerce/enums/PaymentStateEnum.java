package cn.iocoder.yudao.module.commerce.enums;

/**
 * 支付事实状态（与到账履约状态分开，不得合并为一个“成功”）
 */
public enum PaymentStateEnum {

    CREATED,
    PENDING,
    SUCCEEDED,
    CLOSED,
    FAILED,
    /** 主动查单后再归并 */
    UNKNOWN

}
