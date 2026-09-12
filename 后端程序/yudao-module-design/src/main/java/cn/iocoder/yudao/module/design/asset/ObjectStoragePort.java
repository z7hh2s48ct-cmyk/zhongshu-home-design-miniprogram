package cn.iocoder.yudao.module.design.asset;

/**
 * 对象存储端口：私有桶语义（架构 §6.9 / §10.5）
 *
 * 合同：
 * - 全部对象只存私有桶；读取一律经短期下载（presign），数据库不存长期签名 URL；
 * - Runtime 只可写指定任务前缀（AI 隔离区，P4B 接入时强制）；
 * - P0 提供 LocalObjectStorageAdapter（本地文件系统）用于开发与自动化验证；
 *   真实腾讯云 COS Adapter 在凭据就绪后替换，端口不变。
 */
public interface ObjectStoragePort {

    /** 客户端直传地址（真实 COS 为预签名 PUT URL；本地适配器返回内部标记地址） */
    String presignUploadUrl(String objectKey, long ttlSeconds);

    /** 对象是否存在且大小一致 */
    boolean existsWithSize(String objectKey, long expectedSize);

    /** 读取对象内容（校验/消毒用；调用方负责关闭流） */
    java.io.InputStream getObject(String objectKey);

    /** 服务端写对象；已审核内容必须写入客户端无写权限的独立不可变 key。 */
    void putObject(String objectKey, byte[] content);

    /** 短期下载 URL */
    String presignDownloadUrl(String objectKey, long ttlSeconds);

}
