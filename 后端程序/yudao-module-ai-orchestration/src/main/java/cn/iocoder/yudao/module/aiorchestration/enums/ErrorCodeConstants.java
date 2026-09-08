package cn.iocoder.yudao.module.aiorchestration.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * ai-orchestration 模块错误码
 *
 * 使用 1-073-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识。
 */
public interface ErrorCodeConstants {

    ErrorCode AI_JOB_NOT_CANCELLABLE = new ErrorCode(1_073_000_000, "任务当前状态不可取消");

}
