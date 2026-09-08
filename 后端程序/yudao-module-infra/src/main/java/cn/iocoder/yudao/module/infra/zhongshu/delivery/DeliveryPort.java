package cn.iocoder.yudao.module.infra.zhongshu.delivery;

/**
 * 异步交付端口（架构 §6.10）
 *
 * 合同：
 * 1. 大批量导出不占同步请求线程：业务包创建 export_job 后异步生成，完成/失败/过期均可查询并写审计；
 * 2. 授权码交付/文件下载走一次性票据：数据库只存 token 的 SHA-256，明文仅在签发时返回一次；
 *    票据先原子标记 CONSUMED 再输出内容，传输中断也不可重放；
 * 3. 票据过期、已消费、已吊销、不存在必须可区分。
 */
public interface DeliveryPort {

    /** 创建异步导出任务（PENDING），返回任务 ID */
    long createExportJob(ExportJobRequest request);

    ExportJobSnapshot getExportJob(long jobId);

    /** CAS 推进导出任务为 COMPLETED（仅 PENDING/RUNNING 可转） */
    boolean completeExportJob(long jobId, String fileAssetId, String fileSha256, long ttlSeconds);

    /** CAS 推进导出任务为 FAILED（仅 PENDING/RUNNING 可转） */
    boolean failExportJob(long jobId, String error);

    /** 签发一次性交付票据（授权码批次等），明文 token 仅此一次返回 */
    IssuedTicket issueDeliveryTicket(String purpose, String bizRef, Long ownerUserId, long ttlSeconds);

    /** 签发一次性下载票据（资产/导出文件等），明文 token 仅此一次返回 */
    IssuedTicket issueDownloadTicket(String purpose, String bizRef, Long ownerUserId, long ttlSeconds);

    /** 原子消费一次性票据：先标记 CONSUMED 再返回业务引用 */
    TicketConsumption consumeDeliveryTicket(String token, String consumerId);

    /** 原子消费一次性下载票据 */
    TicketConsumption consumeDownloadTicket(String token, String consumerId);

}
