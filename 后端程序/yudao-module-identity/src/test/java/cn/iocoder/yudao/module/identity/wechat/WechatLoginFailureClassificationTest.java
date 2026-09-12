package cn.iocoder.yudao.module.identity.wechat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
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
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * T13-05 微信登录失败分类隔离测试（无真实消费）。
 *
 * <p>与 {@link RealWechatIdentityAdapterTest}（T13-04，覆盖各失败点的<b>消息/无 cause/不泄密/不重试</b>）互补：
 * 本类专注验证每个失败点被 {@link WechatLoginFailure} <b>正确归类</b>——即 {@code ex.getFailure()} 与
 * {@code ex.getErrcode()}。全程把适配器 {@code base-url} 指向本地 {@link FakeHttpServer}，绝不触达真实微信端点。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li><b>枚举契约</b>：四类错误码位于认证层子段 {@code 1-070-001-01x}、互不相同、<b>刻意落在 §7.6 注册段位之外</b>；
 *       {@code clientMessage} 非空；{@code toServiceException} 忠实携带 code+message；</li>
 *   <li><b>微信 errcode → 分类</b>：40029 无效、40163 已使用、40013/40125/41002/41004 配置、
 *       -1/45011/40226/未知码 服务异常（经 {@code classifyErrcode} 归类，errcode 原样保留）；</li>
 *   <li><b>本地失败（errcode==null）→ 分类</b>：空 code 无效、配置缺失/非法 appid 配置、非 200/畸形 JSON/空响应/
 *       缺 openid/传输失败/总时限超时 服务异常——证明分类不依赖 errcode（仅凭 errcode 无法区分这些本地失败）；</li>
 *   <li><b>分类不引入新泄密路径</b>：即便微信 errmsg 回显凭据，归类为 CONFIG_ERROR 时消息/日志仍不含 secret/code/session_key，
 *       且日志不附带任何 throwable。</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WechatLoginFailureClassificationTest {

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

    /** 已捕获日志全文：含格式化消息与（若有）以 {@link ThrowableProxyUtil#asString} 渲染的完整 stacktrace。 */
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

    // ========== 枚举契约 ==========

    @Test
    void enumCarriesDistinctAuthLayerCodesOutsideRegistrySegment() {
        for (WechatLoginFailure failure : WechatLoginFailure.values()) {
            // 认证 / 会话层子段，与 IdentitySessionPort.ACCESS_GRANT_REQUIRED(1_070_001_001) 同层
            assertThat(failure.code()).isBetween(1_070_001_010, 1_070_001_013);
            // 刻意不入 §7.6 稳定错误码注册段位 [1_070_000_000, 1_070_000_999]（该段由 ZhongshuErrorCodeRegistryTest
            // 锁定为固定 21 个领域业务码）；认证层子段码恒大于该段上界，故以 isGreaterThan 表达「落在注册段之外」。
            assertThat(failure.code()).isGreaterThan(1_070_000_999);
            assertThat(failure.clientMessage()).isNotBlank();
            // toServiceException 忠实携带 code + clientMessage，供 GlobalExceptionHandler 渲染为 CommonResult.error
            ServiceException se = failure.toServiceException();
            assertThat(se.getCode()).isEqualTo(failure.code());
            assertThat(se.getMessage()).isEqualTo(failure.clientMessage());
        }
        // 四码互不相同：前端可按 error.code 精确区分四类失败
        long distinct = Arrays.stream(WechatLoginFailure.values())
                .map(WechatLoginFailure::code).distinct().count();
        assertThat(distinct).isEqualTo(WechatLoginFailure.values().length);
    }

    // ========== 微信 errcode → 分类 ==========

    @Test
    void wechatErrcodesClassifyIntoFourKinds() {
        // errcode 语义经微信官方返回码文档核实（见 RealWechatIdentityAdapter#classifyErrcode javadoc）
        Map<Integer, WechatLoginFailure> expected = new LinkedHashMap<>();
        expected.put(40029, WechatLoginFailure.INVALID_CODE);        // 无效 code
        expected.put(40163, WechatLoginFailure.CODE_ALREADY_USED);   // code 已被使用
        expected.put(40013, WechatLoginFailure.CONFIG_ERROR);        // 不合法 AppID
        expected.put(40125, WechatLoginFailure.CONFIG_ERROR);        // 不合法 secret
        expected.put(41002, WechatLoginFailure.CONFIG_ERROR);        // 缺 appid
        expected.put(41004, WechatLoginFailure.CONFIG_ERROR);        // 缺 secret
        expected.put(-1, WechatLoginFailure.WECHAT_SERVICE_ERROR);   // 系统繁忙
        expected.put(45011, WechatLoginFailure.WECHAT_SERVICE_ERROR);// 频率限制
        expected.put(40226, WechatLoginFailure.WECHAT_SERVICE_ERROR);// 高风险用户拦截
        expected.put(9999999, WechatLoginFailure.WECHAT_SERVICE_ERROR); // 未知码兜底为服务异常

        expected.forEach((errcode, kind) -> {
            fake.reset();
            fake.stubGet("/sns/jscode2session", 200, "application/json",
                    "{\"errcode\":" + errcode + ",\"errmsg\":\"boom\"}");

            WechatIdentityException ex = catchThrowableOfType(
                    () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

            assertThat(ex).as("errcode=%s 应抛出并归类", errcode).isNotNull();
            assertThat(ex.getFailure()).as("errcode=%s", errcode).isEqualTo(kind);
            assertThat(ex.getErrcode()).as("errcode=%s 原样保留", errcode).isEqualTo(errcode);
        });
    }

    // ========== 本地失败（errcode==null）→ 分类 ==========

    @Test
    void blankLoginCodeClassifiesAsInvalidCodeWithNullErrcode() {
        // 空 code 在发起任何 HTTP 调用之前即被拒（假服务器零请求），归类为无效 code、errcode 为 null
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, ""), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.INVALID_CODE);
        assertThat(ex.getErrcode()).isNull();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void blankAppsecretClassifiesAsConfigErrorWithNullErrcode() {
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter("  ").codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.CONFIG_ERROR);
        assertThat(ex.getErrcode()).isNull();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void blankAppidClassifiesAsConfigErrorWithNullErrcode() {
        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession("", CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.CONFIG_ERROR);
        assertThat(ex.getErrcode()).isNull();
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void non200ClassifiesAsWechatServiceErrorWithNullErrcode() {
        fake.stubGet("/sns/jscode2session", 500, "text/plain", "gateway error");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
    }

    @Test
    void malformedJsonClassifiesAsWechatServiceErrorWithNullErrcode() {
        fake.stubGet("/sns/jscode2session", 200, "application/json", "not-json-at-all");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
    }

    @Test
    void nullJsonBodyClassifiesAsWechatServiceErrorWithNullErrcode() {
        fake.stubGet("/sns/jscode2session", 200, "application/json", "null");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
    }

    @Test
    void missingOpenidClassifiesAsWechatServiceErrorCarryingZeroErrcode() {
        // errcode=0 却缺 openid：协议异常。errcode 原样保留为 0（非 null），仍归类服务异常
        fake.stubGet("/sns/jscode2session", 200, "application/json", "{\"errcode\":0,\"errmsg\":\"ok\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isEqualTo(0);
    }

    @Test
    void transportFailureClassifiesAsWechatServiceErrorWithNullErrcode() {
        // 服务端接受请求后直接关闭连接、不发送任何响应 → 传输失败
        fake.stubCloseWithoutResponse("GET", "/sns/jscode2session");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
    }

    @Test
    void totalDeadlineTimeoutClassifiesAsWechatServiceErrorWithNullErrcode() {
        // 注入永不完成的 sendAsync future，确定性触发完整交换硬上限超时分支
        CompletableFuture<HttpResponse<String>> neverCompleting = new CompletableFuture<>();
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(neverCompleting).when(mockClient)
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        RealWechatIdentityAdapter adapter =
                new RealWechatIdentityAdapter(SECRET, "https://api.weixin.qq.com", 50, 100, mockClient);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
        assertThat(neverCompleting.isCancelled()).isTrue();
    }

    @Test
    void requestBuildFailureClassifiesAsConfigErrorWithNullErrcode() {
        // 至响应头超时为非正 Duration（readTimeoutMs=-1）时，HttpRequest.newBuilder(uri).timeout(..) 抛
        // IllegalArgumentException（JDK 契约：timeout 拒绝 non-positive duration）→ 命中「请求构建失败」catch 分支。
        // base-url 在构造期已校验合法（不因负 readTimeoutMs 失败），构建失败发生在携带凭据的请求期 try 内、
        // 且在任何 HTTP 发送之前（假服务器零请求）。归类 CONFIG_ERROR、errcode 为 null、消息不含凭据/URL。
        RealWechatIdentityAdapter adapter =
                new RealWechatIdentityAdapter(SECRET, fake.baseUrl(), 3000, -1);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.CONFIG_ERROR);
        assertThat(ex.getErrcode()).isNull();
        // 请求构建即失败，绝不发起任何 HTTP 调用
        assertThat(fake.requests()).isEmpty();
        // 消息净化：只含异常类型名，绝不含 secret/code/URL
        assertThat(ex.getMessage())
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("https://");
    }

    @Test
    void interruptionClassifiesAsWechatServiceErrorRestoresInterruptAndCancels() {
        // 注入一个 get(deadline) 确定性抛 InterruptedException 的 future，命中「请求被中断」分支：
        // 适配器应 cancel 底层交换、恢复线程中断状态，并归类 WECHAT_SERVICE_ERROR（errcode 为 null）。
        CompletableFuture<HttpResponse<String>> interrupting = new CompletableFuture<>() {
            @Override
            public HttpResponse<String> get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("test-interrupt");
            }
        };
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(interrupting).when(mockClient)
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        RealWechatIdentityAdapter adapter =
                new RealWechatIdentityAdapter(SECRET, "https://api.weixin.qq.com", 50, 100, mockClient);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);

        // 立即读取并清除中断状态，避免污染后续断言 / 用例
        boolean interruptRestored = Thread.interrupted();

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
        // 底层交换被取消、线程中断状态被恢复（既不吞中断，也不重试一次性 code）
        assertThat(interrupting.isCancelled()).isTrue();
        assertThat(interruptRestored).isTrue();
    }

    @Test
    void headerTimeoutClassifiesAsWechatServiceErrorWithNullErrcode() {
        // 注入一个已以 HttpTimeoutException 异常完成的 future：get(deadline) 抛 ExecutionException，其 cause 为
        // HttpTimeoutException → 命中「至响应头超时」分支（区别于完整交换硬上限的 TimeoutException 分支）。
        CompletableFuture<HttpResponse<String>> headerTimedOut =
                CompletableFuture.failedFuture(new HttpTimeoutException("header-timeout"));
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(headerTimedOut).when(mockClient)
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        RealWechatIdentityAdapter adapter =
                new RealWechatIdentityAdapter(SECRET, "https://api.weixin.qq.com", 50, 100, mockClient);

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter.codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR);
        assertThat(ex.getErrcode()).isNull();
        // 消息净化：至响应头超时消息绝不含 secret/code/URL
        assertThat(ex.getMessage())
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("https://");
    }

    // ========== 分类不引入新泄密路径 ==========

    @Test
    void configErrorClassificationDoesNotLeakSecretsIntoMessageOrLogs() {
        // 对抗性：微信 errmsg 回显全部凭据，且 errcode=40125（不合法 secret）归类为 CONFIG_ERROR。
        // 分类逻辑绝不得把 errmsg/secret/code/session_key 带入异常消息或日志。
        String evilErrmsg = "invalid secret " + SECRET + " code=" + CODE + " sk=" + SESSION_KEY;
        fake.stubGet("/sns/jscode2session", 200, "application/json",
                "{\"errcode\":40125,\"errmsg\":\"" + evilErrmsg + "\"}");

        WechatIdentityException ex = catchThrowableOfType(
                () -> adapter(SECRET).codeToSession(APPID, CODE), WechatIdentityException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getFailure()).isEqualTo(WechatLoginFailure.CONFIG_ERROR);
        assertThat(ex.getErrcode()).isEqualTo(40125);
        assertThat(ex.getMessage())
                .contains("40125")
                .doesNotContain(evilErrmsg)
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain(SESSION_KEY);
        assertThat(ex).hasNoCause();
        assertThat(capturedLogs())
                .doesNotContain(evilErrmsg)
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain(SESSION_KEY);
        // 日志不附带任何 throwable（无 stacktrace 泄露含凭据 cause 的路径）
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
