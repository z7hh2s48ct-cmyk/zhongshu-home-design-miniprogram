package cn.iocoder.yudao.module.identity.wechat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 微信身份真实适配器（T13-04）：服务端直连微信 {@code code2session}，用临时 login code 换取 openid/unionid。
 *
 * <p>装配条件（T13-01）：{@code zhongshu.identity.wechat.provider=real}；与 {@link StubWechatIdentityAdapter}
 * 互斥，每端口只装配一个实现。生产禁止 stub，由 {@code ZhongshuWiringEnvironmentPostProcessor} 启动守卫强制。
 *
 * <p>安全边界（T13-04 验收）：
 * <ul>
 *   <li>只接受客户端提交的<b>临时 login code</b>；appid 由调用方（{@code AppAuthController}，来自配置）作为入参传入，
 *       appsecret 仅存于服务端配置。入口 VO（{@code AppAuthLoginReqVO}）只有 code 字段，类型层面即无法提交自称的 openid；</li>
 *   <li>微信返回的 {@code session_key} 属敏感凭据：<b>不解析保留、不返回上层、不写日志</b>——响应 DTO 刻意不含该字段，
 *       Jackson 忽略未知字段，session_key 从不进入对象图；</li>
 *   <li>{@link WechatIdentityPort.WechatSession} 仅含 openid/unionid，返回类型层面即不可能把 session_key 泄露给前端；</li>
 *   <li><b>外抛异常绝不携带 cause、绝不含 errmsg/URL/secret/code/session_key</b>：底层 Jackson/IO 异常的消息可能回显
 *       响应体片段或含凭据的完整 URL，若作为 cause 外抛会经 {@code GlobalExceptionHandler} 的 rootCauseMessage/stackTrace
 *       落入错误日志库。故失败根因只以「异常类型名」记录于服务端日志，消息只含数值 errcode 与本地净化描述。</li>
 * </ul>
 *
 * <p>健壮性（T13-04）与失败分类（T13-05：各抛出点以 {@link WechatLoginFailure} 标注、微信 errcode 经 {@link #classifyErrcode} 归类）：
 * <ul>
 *   <li><b>有界总超时</b>：{@code sendAsync(...).get(totalDeadline)} 界定「建连+响应头+body 读取」的完整交换，兜底
 *       Java 17 下 {@code HttpRequest.timeout} 收到响应头即失效、不限时 body 读取的缺口，杜绝请求线程被慢速/停滞响应无限挂起；</li>
 *   <li><b>一次性 code 绝不重试</b>：应用层单次调用、任何失败立即抛出不再发；并以 {@code jdk.httpclient.disableRetryConnect=true}
 *       关闭 JDK HttpClient 对连接失败的内部自动重发（见静态初始化块）；</li>
 *   <li>畸形 {@code base-url} 在<b>构造期</b>即快速失败（此时尚未拼入 secret/code，异常只含 base-url）。</li>
 * </ul>
 *
 * <p>可测试性：{@code base-url} 可配置，隔离测试将其指向本地 {@code FakeHttpServer}，全程不触达真实微信、不产生真实调用。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhongshu.identity.wechat", name = "provider", havingValue = "real")
public class RealWechatIdentityAdapter implements WechatIdentityPort {

    static {
        // T13-04 安全：关闭 JDK HttpClient 对连接失败的内部自动重试。js_code 是一次性凭证，传输层任何形式的
        // 自动重发都可能重复投递同一 code。该属性须在 HttpClient 首次使用前设置才生效，故置于静态初始化块；
        // 若部署方已显式设置（含设为 false）则尊重外部值，不覆盖。
        if (System.getProperty("jdk.httpclient.disableRetryConnect") == null) {
            System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        }
    }

    /** code2session 固定端点路径（拼在 base-url 之后）。 */
    private static final String CODE2SESSION_PATH = "/sns/jscode2session";

    /**
     * 专用 ObjectMapper：仅解析 code2session 响应。<b>不复用 {@code JsonUtils.parseObject}</b>——后者解析失败时会
     * {@code log.error} 完整响应体，而微信成功响应含 session_key（敏感）。此处自行解析并保证任何分支都不记录响应体。
     * 关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}：忽略 session_key、errmsg 等本适配器不保留/不外泄的字段。
     */
    private static final ObjectMapper RESPONSE_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final String appSecret;
    /** 构造期已解析校验的端点 URI（不含任何凭据）；请求期以其字符串形式拼接查询串后再 URI.create。 */
    private final URI baseUri;
    /** 至响应头的时间上限（{@code HttpRequest.timeout}）。 */
    private final Duration headerTimeout;
    /** 完整交换（建连+响应头+body 读取）的硬上限毫秒数，由 {@code sendAsync().get(deadline)} 强制。 */
    private final long totalDeadlineMs;
    private final HttpClient httpClient;

    /**
     * Spring 装配构造器：因本类另有一个供隔离测试注入替身 {@link HttpClient} 的包级构造器（见下），
     * 多构造器并存时 Spring 无法自行判定注入入口（会回退寻找无参构造器而报 {@code No default constructor found}），
     * 故以 {@code @Autowired} 显式指明；各参经 {@code @Value} 从配置解析，随后委派给包级构造器构建默认 {@link HttpClient}。
     */
    @Autowired
    public RealWechatIdentityAdapter(
            @Value("${zhongshu.identity.wechat-appsecret:}") String appSecret,
            @Value("${zhongshu.identity.wechat.base-url:https://api.weixin.qq.com}") String baseUrl,
            @Value("${zhongshu.identity.wechat.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${zhongshu.identity.wechat.read-timeout-ms:5000}") long readTimeoutMs) {
        // HttpClient 线程安全、可长期复用：connectTimeout 限制建连；Redirect.NEVER 禁止跟随重定向，
        // 避免被恶意 3xx 把带 secret 的 URL 泄露到其他主机。
        this(appSecret, baseUrl, connectTimeoutMs, readTimeoutMs, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 包级可见构造器：供隔离测试注入替身 {@link HttpClient}，以<b>确定性</b>验证「完整交换硬上限超时 → {@code cancel} 释放」
     * 路径——真实停滞响应下 {@code HttpRequest.timeout}（至响应头）与 {@code future.get(totalDeadline)}（完整交换）何者先触发
     * 依 JDK 版本而异，无法稳定断言 {@code cancel} 被调用；注入一个永不完成的 {@code sendAsync} future 则可确定性触发总时限分支。
     */
    RealWechatIdentityAdapter(String appSecret, String baseUrl, long connectTimeoutMs, long readTimeoutMs,
                             HttpClient httpClient) {
        this.appSecret = appSecret;
        // 构造期解析并校验 base-url（此时不含任何凭据）：畸形配置在启动/构建期即抛 IllegalArgumentException 快速失败，
        // 绝不推迟到携带 secret+code 的请求期——否则 URI.create / HttpRequest.newBuilder 的异常消息会含带凭据的完整 URL。
        URI parsed = URI.create(stripTrailingSlash(baseUrl) + CODE2SESSION_PATH);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("微信 base-url 非法：scheme 必须为 http/https");
        }
        // getHost() 对含下划线等非法主机名返回 null（Java URI 的 server-authority 解析语义），故 bad_host、缺失 host
        // （https:///path）均在此拦截；请求期再由包裹 HttpRequest.newBuilder 的 try/catch 兜底任何残余校验失败。
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("微信 base-url 非法：缺少合法 host");
        }
        if (parsed.getQuery() != null || parsed.getFragment() != null) {
            // base-url 只应是「scheme://host[:port][/path]」；自带 query/fragment 会与请求期拼接的凭据查询串冲突。
            throw new IllegalArgumentException("微信 base-url 非法：不应包含 query 或 fragment");
        }
        this.baseUri = parsed;
        this.headerTimeout = Duration.ofMillis(readTimeoutMs);
        // 完整交换硬上限 = 建连 + 读取；至少 1ms，避免非正值导致 get(0) 立即超时。
        this.totalDeadlineMs = Math.max(1L, connectTimeoutMs + readTimeoutMs);
        this.httpClient = httpClient;
    }

    @Override
    public WechatSession codeToSession(String appid, String loginCode) {
        // 配置校验：real 模式下 appid（入参，来自配置）与 appsecret（服务端配置）必须齐备。
        // 启动守卫已校验二者，此处防御性再校验，便于隔离测试与非 Spring 场景快速失败，且不发起任何 HTTP 调用。
        if (isBlank(appid) || isBlank(appSecret)) {
            throw new WechatIdentityException(
                    "微信 code2session 配置缺失：appid/appsecret 必须非空"
                            + "（检查 zhongshu.identity.wechat-appid 与 zhongshu.identity.wechat-appsecret）",
                    null, WechatLoginFailure.CONFIG_ERROR);
        }
        if (isBlank(loginCode)) {
            // 客户端 code 为空属请求问题；一次性凭证语义下空 code 必然失败，直接拒绝而不发起调用。
            throw new WechatIdentityException("微信登录 code 不能为空", null, WechatLoginFailure.INVALID_CODE);
        }

        // 携带 secret+一次性 code 的请求 URI 与 HttpRequest 属敏感数据：把「URI.create + HttpRequest.newBuilder + build」
        // 整条构建链全程包裹，任何 RuntimeException 都转成「不含 URL、不含原始 cause」的净化异常。关键：
        // HttpRequest.newBuilder(uri) 会二次校验 URI（scheme/host 等），其 IllegalArgumentException 消息含完整 URL
        // （带 secret+code），若逸出会被 GlobalExceptionHandler 落库，故必须与 URI.create 同处一个 try 内。
        HttpRequest request;
        try {
            // 用字符串拼接 + URI.create 构建完整请求 URI，而非 baseUri.resolve("?...")——后者对「仅查询串」的相对引用
            // 会丢失/改写基址路径（Java URI.resolve 的已知语义），导致请求打到错误路径。baseUri 已在构造期校验合法，
            // 此处拼接的查询值均经 URL 编码，故 URI.create 不会因非法字符抛出；try/catch 为纵深防御。
            URI requestUri = URI.create(baseUri.toString() + "?appid=" + enc(appid)
                    + "&secret=" + enc(appSecret)
                    + "&js_code=" + enc(loginCode)
                    + "&grant_type=authorization_code");
            request = HttpRequest.newBuilder(requestUri)
                    .timeout(headerTimeout)
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            log.warn("[code2session] 请求构建失败（{}）", e.getClass().getSimpleName());
            throw new WechatIdentityException(
                    "微信 code2session 请求构建失败：" + e.getClass().getSimpleName(),
                    null, WechatLoginFailure.CONFIG_ERROR);
        }

        HttpResponse<String> response = sendOnceBounded(request);

        int status = response.statusCode();
        if (status != 200) {
            // 微信正常业务错误仍以 200 + errcode 返回；非 200 视为服务端/网关异常。不记录 body（成功体含 session_key）。
            log.warn("[code2session] 微信返回非 200 状态：{}", status);
            throw new WechatIdentityException("微信 code2session HTTP 状态异常：" + status,
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        }

        Code2SessionResponse parsed;
        try {
            parsed = RESPONSE_MAPPER.readValue(response.body(), Code2SessionResponse.class);
        } catch (IOException e) {
            // 解析失败：绝不记录 body（可能含 session_key）；绝不把 Jackson 异常作为 cause 外抛
            // （其消息可能回显响应体片段，经 GlobalExceptionHandler 落入错误日志库）。只取异常类型名。
            log.warn("[code2session] 响应解析失败（{}）", e.getClass().getSimpleName());
            throw new WechatIdentityException("微信 code2session 响应解析失败",
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        }
        if (parsed == null) {
            // body 为 JSON 字面量 null：readValue 返回 null，需显式判空避免 NPE，按协议异常处理。
            log.warn("[code2session] 响应为空（协议异常）");
            throw new WechatIdentityException("微信 code2session 响应为空",
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        }

        Integer errcode = parsed.errcode();
        if (errcode != null && errcode != 0) {
            // 只记录数值 errcode；errmsg 是微信返回的不可信文本，可能回显 js_code/secret/URL，
            // 故绝不写入日志或异常消息（错误分类见 classifyErrcode：按数值 errcode 归入四类）。
            log.warn("[code2session] 微信返回错误：errcode={}", errcode);
            throw new WechatIdentityException("微信 code2session 失败：errcode=" + errcode,
                    errcode, classifyErrcode(errcode));
        }
        if (isBlank(parsed.openid())) {
            // errcode 缺失/为 0 却无 openid：协议异常（微信正常成功必含 openid）。
            log.warn("[code2session] 响应缺少 openid（协议异常）");
            throw new WechatIdentityException("微信 code2session 响应缺少 openid",
                    errcode, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        }
        // unionid 允许为空：未绑定微信开放平台账号时微信不返回 unionid。
        return new WechatSession(parsed.openid(), parsed.unionid());
    }

    /**
     * 单次、有界地发送请求：一次性 js_code 绝不由本方法重发；完整交换（含 body 读取）受 {@link #totalDeadlineMs} 硬上限约束。
     *
     * <p>用 {@code sendAsync().get(deadline)} 而非 {@code send()}：Java 17 的 {@code send()}+{@code HttpRequest.timeout}
     * 在收到响应头后即取消计时器，不限时 body 读取——服务端发完响应头再停滞即可无限挂起请求线程。{@code future.get(deadline)}
     * 从调用时刻起界定「直到完整响应（含 body）就绪」的总时长，超时即 {@code cancel} 释放底层交换并让调用线程立即返回。
     *
     * <p>任何传输失败都转成<b>不含 URL、不含原始 cause</b> 的净化异常，只保留根因类型名于服务端日志。
     */
    private HttpResponse<String> sendOnceBounded(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(totalDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 完整交换超过硬上限（含 body 读取停滞）：取消底层交换，调用线程立即返回，不被挂起；不重试一次性 code。
            future.cancel(true);
            log.warn("[code2session] 请求超时（超过 {}ms 完整交换上限），不重试一次性 code", totalDeadlineMs);
            throw new WechatIdentityException("微信 code2session 请求超时",
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WechatIdentityException("微信 code2session 请求被中断",
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        } catch (ExecutionException e) {
            // 传输失败（连接被拒/重置/EOF/至响应头超时等）：只取根因类型名，绝不外抛原始 cause（其消息可能含 URL）。
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                // 至响应头超时（HttpRequest.timeout）：与完整交换超时归一为「请求超时」语义，便于 T13-05 分类。
                log.warn("[code2session] 请求超时（至响应头 {}ms 上限），不重试一次性 code", headerTimeout.toMillis());
                throw new WechatIdentityException("微信 code2session 请求超时",
                        null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
            }
            String type = (cause != null ? cause : e).getClass().getSimpleName();
            log.warn("[code2session] 请求微信失败（{}），不重试一次性 code", type);
            throw new WechatIdentityException("微信 code2session 请求失败：" + type,
                    null, WechatLoginFailure.WECHAT_SERVICE_ERROR);
        }
    }

    /**
     * 按微信数值 errcode 归类登录失败（T13-05）。errcode 语义经微信官方返回码文档核实：
     * <ul>
     *   <li>{@code 40029} 无效 code → {@link WechatLoginFailure#INVALID_CODE}；</li>
     *   <li>{@code 40163} code 已被使用（oauth_code 已使用）→ {@link WechatLoginFailure#CODE_ALREADY_USED}；</li>
     *   <li>{@code 40013} 不合法 AppID、{@code 40125} 不合法 secret、{@code 41002} 缺 appid、{@code 41004} 缺 secret
     *       → {@link WechatLoginFailure#CONFIG_ERROR}（服务端凭据配置问题，非用户可修复）；</li>
     *   <li>其它（{@code -1} 系统繁忙、{@code 45011} 频率限制、{@code 40226} 高风险用户拦截等）
     *       → {@link WechatLoginFailure#WECHAT_SERVICE_ERROR}（微信侧瞬态 / 非预期，可稍后由客户端取新 code 重试）。</li>
     * </ul>
     *
     * @param errcode 微信返回码（调用点已保证非 {@code null} 且非 0）
     * @return 对应失败分类
     */
    private static WechatLoginFailure classifyErrcode(int errcode) {
        return switch (errcode) {
            case 40029 -> WechatLoginFailure.INVALID_CODE;
            case 40163 -> WechatLoginFailure.CODE_ALREADY_USED;
            case 40013, 40125, 41002, 41004 -> WechatLoginFailure.CONFIG_ERROR;
            default -> WechatLoginFailure.WECHAT_SERVICE_ERROR;
        };
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * code2session 响应，仅保留本适配器需要的字段。<b>刻意不含 session_key</b>（Jackson 忽略未知字段，敏感凭据不进入
     * 对象图，从源头杜绝被返回上层或写入日志）；<b>亦不含 errmsg</b>（上游不可信文本，可能回显凭据，故不解析保留、不外泄）。
     */
    private record Code2SessionResponse(String openid, String unionid, Integer errcode) {
    }
}
