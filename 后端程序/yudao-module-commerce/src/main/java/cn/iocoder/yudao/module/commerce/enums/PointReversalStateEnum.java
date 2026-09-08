package cn.iocoder.yudao.module.commerce.enums;

/**
 * 点数冲正状态：渠道退款成功后的冲正进度
 */
public enum PointReversalStateEnum {

    NOT_RESERVED,
    /** 受理退款：基础点/赠送点已从可用转入预留（不写冲正流水） */
    RESERVED,
    PENDING,
    REVERSED,
    /** 冲正失败可补偿重回 PENDING */
    FAILED,
    /** 渠道明确失败，释放预留 */
    RELEASED

}
