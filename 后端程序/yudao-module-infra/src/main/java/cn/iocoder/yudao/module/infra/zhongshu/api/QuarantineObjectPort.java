package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * AI 隔离区对象操作端口（P4B：Runtime 写输出、Core 校验器读校验）
 *
 * 实现方：design 模块（私有对象存储适配器）；消费方：ai-orchestration 模块。
 * object key 必须位于任务输出前缀（ai-quarantine/{jobId}/）内。
 */
public interface QuarantineObjectPort {

    boolean existsWithSize(String objectKey, long expectedSize);

    byte[] getObject(String objectKey);

    void putObject(String objectKey, byte[] content);

}
