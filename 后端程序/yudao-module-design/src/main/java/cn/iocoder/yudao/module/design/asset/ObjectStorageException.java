package cn.iocoder.yudao.module.design.asset;

/**
 * 对象存储失败异常（B2 T13-11）：区分「不存在 / 权限拒绝 / 超时 / 服务异常」四类根因，
 * 杜绝把权限拒绝或超时错误当成「对象不存在」——计划 T13-11 明确要求「区分 404、权限拒绝、超时，
 * 不将所有错误当不存在」。{@link ObjectStoragePort#existsWithSize} 仅在确属 404 时返回 {@code false}，
 * 其余（403/超时/服务异常）一律抛出本异常，避免把服务端凭据权限问题或瞬态网络故障误判为用户未上传。
 *
 * <p>刻意继承 {@link IllegalStateException}：与既有 {@link LocalObjectStorageAdapter} 以
 * {@code IllegalStateException} 表达存储失败的约定保持一致，令 {@code AssetService} 中既有的
 * {@code catch (IOException | IllegalStateException)} 消费点（readReviewTicket/readCasePreviewTicket）
 * 无需改动即可捕获 COS 存储失败，不破坏 T13-14 各入口回归。
 *
 * <p>安全：消息只含 {@code objectKey}（存储路径，非凭据）与失败类别，<b>绝不含签名 URL、secret-key
 * 或完整请求 URL</b>；构造时亦不接收底层 SDK 异常作为 cause（其消息可能回显含签名参数的请求 URL），
 * 根因只以「状态码 + 错误码 + 异常类型名」记录于服务端日志（见 {@link CosObjectStorageAdapter}）。
 */
public class ObjectStorageException extends IllegalStateException {

    /** 失败根因分类（T13-11：区分 404/权限拒绝/超时，不将所有错误当不存在）。 */
    public enum Reason {
        /** 对象确不存在（S3 404 / NoSuchKey）——existsWithSize 对此返回 false，getObject 对此抛出。 */
        NOT_FOUND,
        /** 权限拒绝（S3 403 / AccessDenied）——服务端凭据无权访问，属配置/权限问题，绝非「不存在」。 */
        ACCESS_DENIED,
        /** 调用超时或网络/客户端异常（ApiCallTimeout / SdkClientException）——瞬态，可重试，绝非「不存在」。 */
        TIMEOUT,
        /** 其它 S3 服务异常（5xx 等）——服务端侧问题，绝不静默降级为「不存在」。 */
        SERVICE_ERROR
    }

    private final Reason reason;

    public ObjectStorageException(Reason reason, String objectKey, String detail) {
        super("对象存储失败[" + reason + "] key=" + objectKey + (detail == null || detail.isBlank() ? "" : "：" + detail));
        this.reason = reason;
    }

    /** 失败根因分类，供调用方按需区分处理（如瞬态超时可重试、权限拒绝需告警运维）。 */
    public Reason reason() {
        return reason;
    }
}
