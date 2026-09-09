package cn.iocoder.yudao.module.identity.wechat;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

/**
 * 微信登录失败分类（T13-05）：把 {@link RealWechatIdentityAdapter} code2session 的各种失败归入<b>四类</b>，
 * 供登录链路（{@code AccountLoginService}）映射为<b>不同错误码与用户可读结果</b>，而非一律当作通用 500。
 *
 * <p><b>验收对齐</b>（T13-05）：区分「无效 code」「已使用 code」「微信服务异常」「配置错误」。分类由适配器在抛出
 * {@link WechatIdentityException} 时以本枚举<b>显式标注</b>——{@code errcode==null} 的本地失败（配置缺失/超时/传输/
 * 解析/协议）无法仅凭 errcode 区分，故绝不依赖对异常消息做字符串匹配（脆弱且易被上游文案变更击穿）。
 *
 * <p><b>错误码段位约定</b>：本枚举的数值码位于 {@code 1-070-001-xxx}「认证 / 会话层」子段，<b>刻意不入架构 §7.6
 * 稳定错误码注册表</b>（{@code 1-070-000-xxx} 段、由 {@code ZhongshuErrorCodeRegistryTest} 锁定为固定 21 个领域业务码）——
 * 与既有认证层码 {@code IdentitySessionPort.ACCESS_GRANT_REQUIRED}（{@code 1_070_001_001}）、会话 {@code 401} 同层同理：
 * 微信登录失败属认证传输层，非 §7.6 的领域业务规则。故以 {@code int} 码 + {@link ServiceException} 承载（遵循
 * {@code ACCESS_GRANT_REQUIRED} 先例），不占用注册表、不改动跨模块合同测试与冻结的架构文档。
 *
 * <p><b>安全</b>：{@link #CONFIG_ERROR} 的服务端根因（appid/appsecret 缺失或被微信判定非法、请求构建失败等）属内部配置问题，
 * 其 {@code clientMessage} <b>刻意泛化</b>（不回显任何配置细节），仅以<b>独立错误码</b>供运维告警与前端区分；
 * 适配器已保证异常消息与日志不含 URL/secret/code（T13-04），本枚举的 {@code clientMessage} 亦为固定文案、不含任何动态敏感值。
 *
 * @author 众墅之家设计平台（T13-05）
 */
public enum WechatLoginFailure {

    /**
     * 无效 code：微信判定 js_code 非法（{@code errcode=40029}），或客户端提交的 code 为空（防御性；HTTP 入口已由
     * {@code AppAuthLoginReqVO} 的 {@code @NotBlank} 先行拦截为 400）。一次性 code 天然会过期 / 失效，属<b>正常可恢复</b>流程——
     * 客户端应重新 {@code wx.login} 取新 code 再试，<b>绝不自动重放同一 code</b>。
     */
    INVALID_CODE(1_070_001_010, "微信登录凭证已失效，请重新登录"),

    /**
     * 已使用 code：微信判定 js_code 已被消费（{@code errcode=40163}，oauth_code 已使用）。一次性凭证不可重放——
     * 通常源于客户端重复提交同一 code 或重放；客户端应重新 {@code wx.login} 取新 code，服务端<b>绝不重试</b>。
     */
    CODE_ALREADY_USED(1_070_001_011, "微信登录凭证已被使用，请重新登录"),

    /**
     * 微信服务异常：微信侧瞬态或非预期错误——系统繁忙（{@code errcode=-1}）、频率限制（{@code 45011}）、高风险用户拦截
     * （{@code 40226}）等其它 errcode，以及非 200 HTTP 状态、请求超时（有界总时限 / 至响应头）、传输失败、响应解析失败、
     * 响应为空 / 缺 openid 等协议异常。<b>可稍后重试</b>，但重试须由客户端重新取新 code（一次性 code 绝不自动重发）。
     */
    WECHAT_SERVICE_ERROR(1_070_001_012, "微信服务暂时不可用，请稍后重试"),

    /**
     * 配置错误：服务端配置问题——appid/appsecret 缺失（本地校验）、请求构建失败（如非正的 {@code read-timeout-ms} 使
     * {@code HttpRequest.timeout} 抛 {@code IllegalArgumentException}；构建链已全程 try/catch 兜底为不含 URL 与 cause 的净化异常），
     * 或微信判定凭据非法（{@code errcode=40013} 不合法 AppID、{@code 40125} 不合法 secret、{@code 41002} 缺 appid、{@code 41004} 缺 secret）。
     * <b>与构造期校验区分</b>：base-url 非法（scheme/host 不合法或含 query/fragment）由 {@link RealWechatIdentityAdapter} 在<b>构造期</b>即抛
     * {@code IllegalArgumentException} 快速失败（T13-04：启动/构建期暴露，绝不推迟到携带 secret+code 的请求期），<b>不经登录期本分类</b>。
     * <b>非用户可修复</b>：登录链路以 {@code ERROR} 级日志告警运维；对客户端只返回泛化文案（不泄露配置细节），仅以独立错误码区分。
     */
    CONFIG_ERROR(1_070_001_013, "登录服务暂时不可用，请稍后重试或联系支持");

    /** 认证层错误码（{@code 1-070-001-xxx} 子段，不入 §7.6 注册表）。 */
    private final int code;

    /** 面向客户端的用户可读文案（固定字符串，不含任何动态敏感值）。 */
    private final String clientMessage;

    WechatLoginFailure(int code, String clientMessage) {
        this.code = code;
        this.clientMessage = clientMessage;
    }

    public int code() {
        return code;
    }

    public String clientMessage() {
        return clientMessage;
    }

    /**
     * 转译为业务异常：{@code GlobalExceptionHandler.serviceExceptionHandler} 会渲染为
     * {@code CommonResult.error(code, clientMessage)}，使前端按 {@code error.code} 区分、按 {@code error.msg} 提示。
     */
    public ServiceException toServiceException() {
        return new ServiceException(code, clientMessage);
    }
}
