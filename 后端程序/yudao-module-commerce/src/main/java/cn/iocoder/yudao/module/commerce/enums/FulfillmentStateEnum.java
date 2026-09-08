package cn.iocoder.yudao.module.commerce.enums;

/**
 * 权益到账履约状态：FAILED 可补偿重回 PENDING
 */
public enum FulfillmentStateEnum {

    NOT_READY,
    PENDING,
    CREDITED,
    FAILED

}
