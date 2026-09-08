package cn.iocoder.yudao.module.commerce.enums;

/**
 * 人工调点单状态：制单人与复核人必须不同（maker_user_id != checker_user_id）
 */
public enum ManualAdjustmentStatusEnum {

    DRAFT,
    SUBMITTED,
    APPROVED,
    EXECUTED,
    REJECTED

}
