package cn.iocoder.yudao.module.commerce.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * commerce 模块错误码
 *
 * 使用 1-072-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识。
 */
public interface ErrorCodeConstants {

    ErrorCode POINTS_INSUFFICIENT = new ErrorCode(1_072_000_000, "设计点余额不足");
    ErrorCode PRICE_RULE_CHANGED = new ErrorCode(1_072_000_001, "计价规则已更新，请确认新价格后重试");
    ErrorCode PAYMENT_ORDER_STATE_CONFLICT = new ErrorCode(1_072_000_002, "充值订单状态不允许该操作");
    ErrorCode PAYMENT_PAID_CREDIT_PENDING = new ErrorCode(1_072_000_003, "支付成功，设计点到账处理中");
    ErrorCode REFUND_POINTS_ALREADY_USED = new ErrorCode(1_072_000_004, "到账后已发生点数消耗，无法受理整单退款");
    ErrorCode REFUND_POLICY_NOT_ENABLED = new ErrorCode(1_072_000_005, "P0 仅支持整单全额退款，不支持部分退款");

}
