package cn.iocoder.yudao.module.identity.wechat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.test.core.util.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * T13-04 真实微信身份适配器隔离测试（无真实消费）。
 *
 * <p>全程把适配器 {@code base-url} 指向本地 {@link FakeHttpServer}，<b>绝不触达真实微信端点、不产生任何真实调用</b>；
 * 直接 {@code new} 适配器（构造器注入），无需 Spring 上下文。
 *
 * <p>对抗性覆盖（针对 codex 两轮评审发现的加固验证）：
 * <ul>
 *   <li>成功：映射 openid/unionid；请求 URL 携带 appid/secret/js_code/grant_type；session_key 从不被保留或返回；</li>
 *   <li><b>#2 errmsg 不可信</b>：微信 errmsg 即便回显凭据，也绝不进入异常消息 / 日志（record 不含 errmsg，日志只用数值 errcode）；</li>
 *   <li><b>#1 异常无 cause + URL 不外泄</b>：畸形 JSON 不经 cause/消息/日志泄露；畸形 base-url（下划线主机名 / 空 authority /
 *       非 http(s) scheme）在<b>构造期</b>即快速失败，绝不推迟到请求期由 {@code HttpRequest.newBuilder} 抛出含 secret+code 的完整 URL；</li>
 *   <li><b>#8 null 响应</b>：body 为 JSON 字面量 {@code null} 时按协议异常处理，而非 NPE；</li>
 *   <li><b>#4 一次性 code 不重试</b>：微信 errcode 失败时假服务器只收到 1 次请求（应用层不重发）；</li>
 *   <li><b>#3/#4 传输失败</b>：服务端接受后直接关闭连接（{@code stubCloseWithoutResponse}）→ 净化异常，不含带凭据的 URL、无 cause；</li>
 *   <li><b>#5 有界总超时（确定性）</b>：注入永不完成的 {@code sendAsync} future，确定性触发完整交换硬上限超时并 {@code cancel(true)} 释放；
 *       另有真实停滞响应体（{@code stubStalledBody}）验证线程不被无限挂起；</li>
 *   <li><b>#6 日志无 throwable 泄露</b>：{@code capturedLogs} 以 {@code ThrowableProxyUtil.asString} 渲染完整 stacktrace（含嵌套 cause/suppressed），
 *       并断言适配器所有日志事件<b>均不附带 throwable</b>——从根上杜绝 stacktrace 泄露含凭据的 cause；</li>
 *   <li>非 200 / errcode=0 却缺 openid：均抛出，且不泄露响应体、无 cause；</li>
 *   <li>unionid 缺省映射为 null；配置缺失（appsecret/appid 空）与空 code：<b>发起任何 HTTP 调用之前</b>即失败（假服务器零请求）。</li>
 * </ul>
 *
 * <p>日志断言用 logback {@link ListAppender} 捕获适配器 logger 的实际输出，证明「敏感值从不落日志」而非仅凭代码走查。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RealWechatIdentityAdapterTest {

    private static final String APPID = "wx-real-appid";
    private static final String SECRET = "test-appsecret-DO-NOT-LEAK";
    private static final String CODE = "temp-login-code-DO-NOT-LEAK";
    private static final String SESSION_KEY = "SENSITIVE-session_key-DO-NOT-LEAK";

    private FakeHttpServer fake;
    private Logger adapterLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws IOException {
        fake = FakeHttpServer.start();
        // 捕获适配器自身 logger 的实际输出（@Slf4j → 以类名为 logger 名），用于断言敏感值从不落日志
        adapterLogger = (Logger) LoggerFactory.getLogger(RealWechatIdentityAdapter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        adapterLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (adapterLogger != null && logAppender != null) {
            adapterLogger.detachAppender(logAppender);
            logAppender.stop();
        }
        fake.close();
    }

    /** 构造指向假服务器的适配器（appsecret 可指定，用于配置缺失用例）。 */
    private RealWechatIdentityAdapter adapter(String appSecret) {
        return new RealWechatIdentityAdapter(appSecret, fake.baseUrl(), 3000, 5000);
    }

    /**
     * 已捕获日志的完整文本：含格式化消息与（若有）以 {@link ThrowableProxyUtil#asString} 渲染的<b>完整 stacktrace</b>
     * （含嵌套 cause 与 suppressed），确保泄露断言覆盖所有异常渲染路径，而非仅外层异常的类名/消息。
     */
    private String capturedLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                sb.append(ThrowableProxyUtil.asString(throwable)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 断言适配器所有日志事件<b>均未附带任何 throwable</b>：适配器只以「异常类型名」写入格式化消息、绝不把异常对象交给 logger，
     * 故不存在 logback 渲染 stacktrace 时泄露含凭据 cause 的路径（较「哨兵 cause」测试更强的根因级保证）。
     */
    private void assertNoThrowableLogged() {
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy())
                        .as("适配器不得以 throwable 形式记录异常（避免 stacktrace 泄露含凭据的 cause）")
                        .isNull());
    }

    @Test
    void successMapsOpenidUnionidAndNeverRetainsSessionKey() {
        // 微信成功响应含 session_key（敏感）；适配器必须只取 openid/unionid，session_key 不进入返回对象
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"openid\":\"o-real-1\",\"unionid\":\"u-real-1\",\"session_key\":\"" + SESSION_KEY + "\"}");

        WechatIdentityPort.WechatSession session = adapter(SECRET).codeToSession(APPID, CODE);

        assertThat(session.openid()).isEqualTo("o-real-1");
        assertThat(session.unionid()).isEqualTo("u-real-1");
        // 返回对象（含其 toString）绝不含 session_key——类型层面即无该字段，此处再做行为断言兜底
        assertThat(session.toString()).doesNotContain(SESSION_KEY);
        // 成功路径日志绝不落 session_key
        assertThat(capturedLogs()).doesNotContain(SESSION_KEY);

        // 请求确实发往 code2session，且查询串携带 appid/secret/js_code/grant_type（服务端持密，客户端只给 code）
        FakeHttpServer.RecordedRequest req = fake.lastRequest();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.path()).isEqualTo("/sns/jscode2session");
        assertThat(req.query())
                .contains("appid=" + APPID)
                .contains("secret=" + SECRET)
                .contains("js_code=" + CODE)
                .contains("grant_type=authorization_code");
    }

    @Test
    void unionidAbsentMapsToNull() {
        // 未绑定微信开放平台账号时微信不返回 unionid：应映射为 null 而非报错
        fake.stubGet("/sns/jscode2session", 200, "application/json", "{\"openid\":\"o-only\"}");

        WechatIdentityPort.WechatSession session = adapter(SECRET).codeToSession(APPID, CODE);

        assertThat(session.openid()).isEqualTo("o-only");
        assertThat(session.unionid()).isNull();
    }

    @Test
    void errcodeInvalidCodeThrowsSafeMessageWithoutErrmsg() {
        // #2：40029=invalid code。消息只含数值 errcode，绝不含 errmsg / secret / code / session_key；且无 cause
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .contains("40029")
                .doesNotContain("invalid code") // errmsg 绝不外泄
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain(SESSION_KEY);
        assertThat(ex).hasNoCause();
        // 日志只记数值 errcode，绝不落 errmsg / secret / code，且不附带任何 throwable
        assertThat(capturedLogs())
                .contains("40029")
                .doesNotContain("invalid code")
                .doesNotContain(SECRET)
                .doesNotContain(CODE);
        assertNoThrowableLogged();
        assertThat(fake.lastRequest()).isNotNull(); // 确实发起了一次调用
    }

    @Test
    void errcodeCarriesIntoException() {
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"errcode\":40163,\"errmsg\":\"code been used\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrcode()).isEqualTo(40163);
        assertThat(ex.getMessage()).doesNotContain("code been used"); // errmsg 不外泄
    }

    @Test
    void systemBusyDoesNotRetryOneTimeCode() {
        // #4：-1=system busy。响应成功送达但业务失败——一次性 code 绝不由应用层重试，假服务器只应收到 1 次请求
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"errcode\":-1,\"errmsg\":\"system busy\"}");

        assertThat(catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class)).isNotNull();
        assertThat(fake.requests()).hasSize(1);
    }

    @Test
    void httpNon200ThrowsWithoutLeakingBodyOrCause() {
        // 非 200 视为服务端/网关异常：不记录 body（成功体才含 session_key，此处兜底断言不泄露），且无 cause
        fake.stubGet("/sns/jscode2session", 500, "text/plain", "gateway error " + SESSION_KEY);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("500").doesNotContain(SESSION_KEY);
        assertThat(ex).hasNoCause();
        assertThat(capturedLogs()).doesNotContain(SESSION_KEY);
        assertNoThrowableLogged();
    }

    @Test
    void malformedJsonWithSecretsDoesNotLeakViaMessageCauseOrLogs() {
        // #1：对抗性 fixture——畸形 JSON 中内嵌全部敏感值。Jackson 解析异常的 source 可能回显响应体片段，
        // 适配器绝不把它作为 cause 外抛、绝不记入日志，只取异常类型名。
        String poisoned = "not-json " + SESSION_KEY + " " + SECRET + " " + CODE;
        fake.stubGet("/sns/jscode2session", 200, "application/json", poisoned);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .contains("解析失败")
                .doesNotContain(SESSION_KEY)
                .doesNotContain(SECRET)
                .doesNotContain(CODE);
        assertThat(ex).hasNoCause(); // Jackson 异常绝不作为 cause 外抛
        // 服务端日志只记异常类型名，绝不含被解析的响应体片段，且不附带 throwable（无 stacktrace 泄露路径）
        assertThat(capturedLogs())
                .doesNotContain(SESSION_KEY)
                .doesNotContain(SECRET)
                .doesNotContain(CODE);
        assertNoThrowableLogged();
    }

    @Test
    void nullJsonBodyThrowsProtocolExceptionNotNpe() {
        // #8：body 为 JSON 字面量 null → readValue 返回 null，须显式判空按协议异常处理，而非 NPE
        fake.stubGet("/sns/jscode2session", 200, "application/json", "null");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("响应为空");
        assertThat(ex).hasNoCause();
    }

    @Test
    void errmsgEchoingSecretsIsNeverLoggedNorThrown() {
        // #2：对抗性——微信 errmsg 回显凭据（不可信上游文本）。record 不含 errmsg 字段、日志只用 errcode，
        // 故 errmsg 及其内嵌的 secret/code/session_key 绝不出现在异常消息或日志中。
        String evilErrmsg = "invalid code " + CODE + " secret=" + SECRET + " sk=" + SESSION_KEY;
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"errcode\":40029,\"errmsg\":\"" + evilErrmsg + "\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrcode()).isEqualTo(40029);
        assertThat(ex.getMessage())
                .contains("40029")
                .doesNotContain(evilErrmsg)
                .doesNotContain(SESSION_KEY)
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("invalid code");
        assertThat(capturedLogs())
                .doesNotContain(evilErrmsg)
                .doesNotContain(SESSION_KEY)
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("invalid code");
        assertNoThrowableLogged();
    }

    @Test
    void missingOpenidWithZeroErrcodeThrows() {
        // errcode 缺失/为 0 却无 openid：协议异常
        fake.stubGet("/sns/jscode2session", 200, "application/json", "{\"errcode\":0,\"errmsg\":\"ok\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("openid");
    }

    @Test
    void blankAppsecretFailsBeforeAnyHttpCall() {
        // 配置缺失：发起任何 HTTP 调用之前即失败（假服务器零请求）
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter("  ").codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("appsecret");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void blankAppidFailsBeforeAnyHttpCall() {
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession("", CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("appid");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void blankLoginCodeFailsBeforeAnyHttpCall() {
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, ""), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("code");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void transportFailureThrowsSafeErrorWithoutLeakingUrl() {
        // #3/#4：服务端接受请求后直接关闭连接、不发送任何响应 → 客户端传输失败（ExecutionException→IOException）。
        // 经 FakeHttpServer 注入（而非指向真实死端口），验证净化异常：不含带 secret+code 的 URL、无 cause、日志不泄露。
        fake.stubCloseWithoutResponse("GET", "/sns/jscode2session");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .contains("请求失败")
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("http");
        assertThat(ex).hasNoCause(); // 传输异常绝不作为 cause 外抛（其消息可能含完整 URL）
        assertThat(capturedLogs())
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("http://");
        assertNoThrowableLogged();
        assertThat(fake.requests()).isNotEmpty(); // 确实尝试过调用
    }

    @Test
    void malformedBaseUrlFailsAtConstructionWithoutSecret() {
        // #3：含空格的畸形 base-url 在 URI.create 阶段即抛 IllegalArgumentException（构造期，尚未拼入 secret/code）
        IllegalArgumentException ex = catchThrowableOfType(
                () -> new RealWechatIdentityAdapter(SECRET, "https://bad host with space", 1000, 1000),
                IllegalArgumentException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).doesNotContain(SECRET).doesNotContain(CODE);
        // 构造期失败不发起任何请求
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void malformedBaseUrlVariantsFailAtConstructionWithoutSecret() {
        // #1：三类畸形 base-url 均在构造期即拦截，绝不推迟到请求期由 HttpRequest.newBuilder 抛出
        // 「unsupported URI https://…?secret=…&js_code=…」这类含完整凭据 URL 的异常（会被 GlobalExceptionHandler 落库）：
        //   (a) bad_host 含下划线主机名 → Java URI 走 registry-authority，getHost() 返回 null（host 守卫拦截）；
        //   (b) base-url 自带 query（?x=1）→ 与请求期拼接的凭据查询串冲突，getQuery()!=null 守卫拦截；
        //   (c) ftp:// 非 http(s) → scheme 守卫拦截。
        for (String badBaseUrl : new String[]{
                "https://bad_host", "https://api.weixin.qq.com?x=1", "ftp://api.weixin.qq.com"}) {
            IllegalArgumentException ex = catchThrowableOfType(
                    () -> new RealWechatIdentityAdapter(SECRET, badBaseUrl, 1000, 1000),
                    IllegalArgumentException.class);

            assertThat(ex).as("base-url=%s 应在构造期即失败", badBaseUrl).isNotNull();
            assertThat(ex.getMessage()).doesNotContain(SECRET).doesNotContain(CODE);
        }
        // 构造期失败不发起任何请求
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void totalDeadlineCancelsIncompleteExchangeDeterministically() {
        // #5（确定性）：真实停滞响应下「至响应头超时(HttpRequest.timeout)」与「完整交换硬上限(future.get(totalDeadline))」
        // 何者先触发依 JDK 版本而异，无法稳定断言 cancel 分支。此处注入一个<b>永不完成</b>的 sendAsync future，
        // 令 future.get(totalDeadline) 必然超时，从而确定性触发总时限分支并 cancel(true) 释放底层交换。
        CompletableFuture<HttpResponse<String>> neverCompleting = new CompletableFuture<>();
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(neverCompleting).when(mockClient)
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        // connect=50 + read=100 → totalDeadline=150ms；mock 不真正联网，仅验证超时/cancel 逻辑
        RealWechatIdentityAdapter adapter =
                new RealWechatIdentityAdapter(SECRET, "https://api.weixin.qq.com", 50, 100, mockClient);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("超时");
        assertThat(ex).hasNoCause();
        // 关键：证明总时限超时后确实 cancel 了未完成的交换（释放底层资源、不挂起请求线程、不重试一次性 code）
        assertThat(neverCompleting.isCancelled()).isTrue();
    }

    @Test
    void stalledBodyIsBoundedByTotalDeadlineAndDoesNotHang() {
        // #5（真实停滞）：服务端立即发响应头、随后停滞 5000ms 才写 body。适配器完整交换硬上限 = connect(300)+read(400)=700ms，
        // 请求线程应在停滞期间超时返回、绝不被挂起至 5000ms（兜底 Java 17 header-timeout 收到响应头即失效的缺口）。
        fake.stubStalledBody("GET", "/sns/jscode2session", 200, "application/json",
                "{\"openid\":\"o-slow\"}", 5000);
        RealWechatIdentityAdapter adapter = new RealWechatIdentityAdapter(SECRET, fake.baseUrl(), 300, 400);

        long start = System.nanoTime();
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("超时");
        // 远小于 5000ms 停滞：证明 body 读取受有界总超时约束、线程未被无限挂起（cancel 分支的确定性证明见上一用例）
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(ex).hasNoCause();
    }
}
