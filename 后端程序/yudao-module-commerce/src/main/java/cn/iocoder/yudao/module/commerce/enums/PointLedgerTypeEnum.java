package cn.iocoder.yudao.module.commerce.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 设计点流水类型（只追加，不更新历史）
 */
@RequiredArgsConstructor
@Getter
public enum PointLedgerTypeEnum {

    /** 充值基础点入账 */
    RECHARGE_BASE_CREDIT,

    /** 充值赠送点入账 */
    RECHARGE_BONUS_CREDIT,

    /** 平面生成扣点 */
    FLAT_GENERATION_DEBIT,

    /** 立面生成扣点 */
    ELEVATION_GENERATION_DEBIT,

    /** 任务结算退回（差额/全额） */
    TASK_SETTLEMENT_REFUND,

    /** 人工调增 */
    MANUAL_CREDIT,

    /** 人工调减 */
    MANUAL_DEBIT,

    /** 渠道退款成功：基础点冲正 */
    RECHARGE_BASE_REVERSAL,

    /** 渠道退款成功：赠送点冲正 */
    RECHARGE_BONUS_REVERSAL

}
