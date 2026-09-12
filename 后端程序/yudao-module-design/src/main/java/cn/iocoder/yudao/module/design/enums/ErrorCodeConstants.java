package cn.iocoder.yudao.module.design.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * design 模块错误码
 *
 * 使用 1-071-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识。
 */
public interface ErrorCodeConstants {

    ErrorCode DESIGN_STAGE_CONFLICT = new ErrorCode(1_071_000_000, "设计阶段冲突：未选定平面候选前不得创建立面任务");
    ErrorCode ASSET_VALIDATION_FAILED = new ErrorCode(1_071_000_001, "资产校验未通过");
    ErrorCode PUBLICATION_VALIDATION_FAILED = new ErrorCode(1_071_000_002, "发布校核未通过");
    ErrorCode GENERATION_REFERENCE_NOT_AUTHORIZED = new ErrorCode(1_071_000_003, "该案例未授权用作生成参考");
    ErrorCode SUBMISSION_STATE_CONFLICT = new ErrorCode(1_071_000_004, "投稿状态不允许该操作");

    ErrorCode BUDGET_INPUT_INVALID = new ErrorCode(1_071_000_100, "预算输入不符合字段、单位或精度要求");
    ErrorCode BUDGET_INCOMPLETE = new ErrorCode(1_071_000_101, "预算存在待补或待复核项，不能形成完整报价");
    ErrorCode BUDGET_PRICE_CONFLICT = new ErrorCode(1_071_000_102, "同地区选项的价格生效区间冲突");
    ErrorCode BUDGET_AMOUNT_LIMIT = new ErrorCode(1_071_000_103, "预算金额超过允许范围");

}
