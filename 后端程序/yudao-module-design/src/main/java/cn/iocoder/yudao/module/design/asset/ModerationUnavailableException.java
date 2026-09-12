package cn.iocoder.yudao.module.design.asset;

/** Infrastructure uncertainty is retryable, never an approval or a content rejection. */
public final class ModerationUnavailableException extends RuntimeException {
    public ModerationUnavailableException() { super("审核服务暂不可用，请稍后重试"); }
}
