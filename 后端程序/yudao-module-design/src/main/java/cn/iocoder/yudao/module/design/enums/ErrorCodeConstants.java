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

}
