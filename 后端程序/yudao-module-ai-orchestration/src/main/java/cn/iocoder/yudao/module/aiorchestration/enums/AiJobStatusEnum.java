package cn.iocoder.yudao.module.aiorchestration.enums;

/**
 * AI 任务状态机（架构文档 §6.4）
 */
public enum AiJobStatusEnum {

    CREATED,
    QUEUED,
    RUNNING,
    VALIDATING,
    SETTLING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    /** 运行中收到取消请求，等待按有效槽位结算 */
    CANCEL_REQUESTED,
    CANCELLED

}
