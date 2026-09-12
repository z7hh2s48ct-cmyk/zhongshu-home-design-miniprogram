package cn.iocoder.yudao.module.identity.wechat;

import java.util.Objects;

/**
 * 微信身份适配异常（T13-04；T13-05 增加失败分类）。
 *
 * <p>由 {@link RealWechatIdentityAdapter} 在 code2session 失败时抛出。<b>异常消息不得包含 appsecret、js_code、
 * session_key 或完整请求 URL</b>——code2session 的 URL 查询串携带 secret+一次性 code，属敏感数据；消息仅可包含
 * 微信数值 errcode 与本地净化描述（不含上游 errmsg 等不可信文本）。
 *
 * <p><b>本类刻意不提供携带 {@code cause} 的构造器</b>：从类型层面杜绝把底层 Jackson/IO 异常作为 cause 外抛——
 * 其消息可能回显响应体片段或含 secret+code 的完整 URL，并经 {@code GlobalExceptionHandler} 的
 * rootCauseMessage/stackTrace 落入错误日志库。失败根因仅以「异常类型名」记录于服务端日志，绝不进入外抛异常。
 *
 * <p>{@code errcode} 保留微信原始返回码（IO/超时/协议/配置类失败时为 {@code null}）；{@code failure}（T13-05）标注
 * 四类失败分类（无效 / 已使用 code、微信服务异常、配置错误），供登录链路映射为不同错误码与用户可读结果——{@code errcode==null}
 * 的本地失败无法仅凭 errcode 区分，故由 {@link RealWechatIdentityAdapter} 在每个抛出点显式标注，绝不依赖对消息做字符串匹配。
 */
public class WechatIdentityException extends RuntimeException {

    /** 微信 code2session 返回码；非微信业务错误（IO/超时/协议/配置）时为 {@code null}。 */
    private final Integer errcode;

    /** 失败分类（T13-05）：恒非 {@code null}，由 {@link RealWechatIdentityAdapter} 在每个抛出点显式标注。 */
    private final WechatLoginFailure failure;

    public WechatIdentityException(String message, Integer errcode, WechatLoginFailure failure) {
        super(message);
        this.errcode = errcode;
        this.failure = Objects.requireNonNull(failure, "failure 分类不得为空：每个失败点都必须显式归类");
    }

    public Integer getErrcode() {
        return errcode;
    }

    public WechatLoginFailure getFailure() {
        return failure;
    }
}
