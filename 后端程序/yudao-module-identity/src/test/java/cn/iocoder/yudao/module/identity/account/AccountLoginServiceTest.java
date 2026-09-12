package cn.iocoder.yudao.module.identity.account;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityException;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityPort;
import cn.iocoder.yudao.module.identity.wechat.WechatLoginFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T13-05 登录失败翻译隔离测试（纯 mock，<b>无需 PG</b>）。
 *
 * <p>{@link AccountLoginService#login} 先调 {@code codeToSession} 再访问数据库：当微信身份适配抛出
 * {@link WechatIdentityException} 时，翻译发生在<b>任何 DB 访问之前</b>，故用 mock 的
 * {@code DataSource / PlatformTransactionManager / WechatIdentityPort / UserSessionService} 即可完整验证翻译路径，
 * 不启动 Spring、不连数据库。
 *
 * <p>验收对齐（T13-05）：
 * <ul>
 *   <li><b>四类失败各译为独立认证层错误码</b>（{@code 1-070-001-01x}），而非一律通用 500；</li>
 *   <li><b>不盲目重试一次性 code</b>：{@code codeToSession} 只被调用一次；</li>
 *   <li><b>失败登录绝不签发会话、绝不触库</b>（{@code DataSource} 零交互）；</li>
 *   <li><b>配置错误以 ERROR 级告警运维、其余 WARN</b>；日志只记失败分类与数值 errcode，
 *       <b>绝不记录异常消息</b>（消息虽已在 T13-04 净化，此处再从登录链路侧兜底不泄露）；</li>
 *   <li><b>客户端只收到泛化文案</b>，绝不含 secret/code/URL 等内部细节。</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AccountLoginServiceTest {

    private static final String APPID = "wx-appid";
    private static final String CODE = "temp-login-code";
    private static final String SECRET = "appsecret-DO-NOT-LEAK";

    private DataSource dataSource;
    private PlatformTransactionManager transactionManager;
    private WechatIdentityPort wechatIdentityPort;
    private UserSessionService sessionService;
    private AccountLoginService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        transactionManager = mock(PlatformTransactionManager.class);
        wechatIdentityPort = mock(WechatIdentityPort.class);
        sessionService = mock(UserSessionService.class);
        service = new AccountLoginService(dataSource, transactionManager, wechatIdentityPort, sessionService);

        serviceLogger = (Logger) LoggerFactory.getLogger(AccountLoginService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    /** 令 code2session 抛出携带指定分类 / errcode / 消息的适配异常。 */
    private void stubCodeToSessionThrows(WechatLoginFailure failure, Integer errcode, String message) {
        when(wechatIdentityPort.codeToSession(any(), any()))
                .thenThrow(new WechatIdentityException(message, errcode, failure));
    }

    /** 触发 login 并捕获翻译后的 {@link ServiceException}。 */
    private ServiceException loginExpectingServiceException() {
        return catchThrowableOfType(() -> service.login(APPID, CODE, null), ServiceException.class);
    }

    private String capturedLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
            // 若日志事件附带 throwable（stacktrace）一并渲染纳入断言：确保 login 绝不把异常对象交给 logger，
            // 否则毒化消息会经 stacktrace 泄露，而仅读 getFormattedMessage() 的断言将漏判（codex round-1 P2）。
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                sb.append(ThrowableProxyUtil.asString(throwable)).append('\n');
            }
        }
        return sb.toString();
    }

    // ========== 四类失败各译为独立错误码 ==========

    @Test
    void invalidCodeTranslatesToAuthLayerServiceExceptionWithoutRetry() {
        stubCodeToSessionThrows(WechatLoginFailure.INVALID_CODE, 40029,
                "微信 code2session 失败：errcode=40029");

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_010);
        assertThat(ex.getMessage()).isEqualTo(WechatLoginFailure.INVALID_CODE.clientMessage());
        // 一次性 code 绝不重试：codeToSession 只被调用一次
        verify(wechatIdentityPort, times(1)).codeToSession(any(), any());
        // 失败登录绝不签发会话
        verify(sessionService, never()).issue(anyLong(), any(), any(), anyBoolean(), any());
        // 非配置错误以 WARN 记录（单条）
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void codeAlreadyUsedTranslatesToServiceException() {
        stubCodeToSessionThrows(WechatLoginFailure.CODE_ALREADY_USED, 40163,
                "微信 code2session 失败：errcode=40163");

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_011);
        assertThat(ex.getMessage()).isEqualTo(WechatLoginFailure.CODE_ALREADY_USED.clientMessage());
        verify(wechatIdentityPort, times(1)).codeToSession(any(), any());
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void wechatServiceErrorTranslatesToServiceException() {
        stubCodeToSessionThrows(WechatLoginFailure.WECHAT_SERVICE_ERROR, -1,
                "微信 code2session 失败：errcode=-1");

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_012);
        assertThat(ex.getMessage()).isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR.clientMessage());
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void configErrorTranslatesAndLogsAtErrorLevel() {
        stubCodeToSessionThrows(WechatLoginFailure.CONFIG_ERROR, 40013,
                "微信 code2session 失败：errcode=40013");

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_013);
        // 配置错误对客户端只返回泛化文案（不泄露 appid/secret 细节）
        assertThat(ex.getMessage())
                .isEqualTo(WechatLoginFailure.CONFIG_ERROR.clientMessage())
                .doesNotContain(SECRET)
                .doesNotContain("40013");
        // 配置错误以 ERROR 级告警运维（单条）
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    void configErrorWithNullErrcodeStillTranslatesAndLogsError() {
        // 本地配置缺失（appid/appsecret 空）→ errcode 为 null；日志以 "null" 渲染不崩溃，仍 ERROR 级
        stubCodeToSessionThrows(WechatLoginFailure.CONFIG_ERROR, null,
                "微信 code2session 配置缺失：appid/appsecret 必须非空");

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_013);
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(capturedLogs()).contains("CONFIG_ERROR").contains("null");
    }

    // ========== 日志卫生：只记 kind + errcode，绝不记异常消息 ==========

    @Test
    void translationLogsOnlyKindAndErrcodeNeverExceptionMessage() {
        // 对抗性：适配异常消息内嵌凭据与 URL（尽管 T13-04 已净化，此处从登录链路侧再兜底）。
        // login 只应把「失败分类名 + 数值 errcode」写日志，绝不写入 e.getMessage()。
        String poisoned = "SENSITIVE-MSG " + SECRET + " " + CODE + " url=https://api.weixin.qq.com?secret=" + SECRET;
        stubCodeToSessionThrows(WechatLoginFailure.WECHAT_SERVICE_ERROR, -1, poisoned);

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        String logs = capturedLogs();
        assertThat(logs)
                .contains("WECHAT_SERVICE_ERROR")   // 失败分类名被记录
                .contains("-1")                      // 数值 errcode 被记录
                .doesNotContain("SENSITIVE-MSG")     // 异常消息绝不被记录
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("https://");
        // 客户端只收到泛化文案，绝不含内部消息 / 凭据
        assertThat(ex.getMessage())
                .isEqualTo(WechatLoginFailure.WECHAT_SERVICE_ERROR.clientMessage())
                .doesNotContain(SECRET)
                .doesNotContain(CODE);
        // 绝不把异常对象交给 logger：所有事件 throwableProxy 均为 null（否则毒化消息经 stacktrace 泄露，capturedLogs 会捕获）
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void configErrorLogsOnlyKindAndErrcodeNeverExceptionMessageAtErrorLevel() {
        // 对抗性（ERROR 路径）：CONFIG_ERROR 走 ERROR 级告警；异常消息内嵌凭据与 URL。
        // login 只应把「失败分类名 + 数值 errcode」写日志，绝不写入 e.getMessage()、绝不把异常对象交给 logger。
        String poisoned = "CFG-LEAK " + SECRET + " " + CODE + " url=https://api.weixin.qq.com?secret=" + SECRET;
        stubCodeToSessionThrows(WechatLoginFailure.CONFIG_ERROR, 40125, poisoned);

        ServiceException ex = loginExpectingServiceException();

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_070_001_013);
        // CONFIG_ERROR 以 ERROR 级告警运维（单条）
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        String logs = capturedLogs();
        assertThat(logs)
                .contains("CONFIG_ERROR")     // 失败分类名被记录
                .contains("40125")            // 数值 errcode 被记录
                .doesNotContain("CFG-LEAK")   // 异常消息绝不被记录
                .doesNotContain(SECRET)
                .doesNotContain(CODE)
                .doesNotContain("https://");
        // 绝不把异常对象交给 logger（throwableProxy 为 null，杜绝 stacktrace 泄露）
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        // 客户端只收到泛化文案，绝不含配置细节 / 凭据
        assertThat(ex.getMessage())
                .isEqualTo(WechatLoginFailure.CONFIG_ERROR.clientMessage())
                .doesNotContain(SECRET)
                .doesNotContain("40125");
    }

    // ========== 失败登录不触库、不签发会话 ==========

    @Test
    void failedLoginNeverTouchesDatabaseNorIssuesSession() {
        stubCodeToSessionThrows(WechatLoginFailure.INVALID_CODE, 40029,
                "微信 code2session 失败：errcode=40029");

        loginExpectingServiceException();

        // codeToSession 先于任何 DB 访问，抛异常即返回：数据源零交互（不触库、不解析/新建账号）
        verifyNoInteractions(dataSource);
        verify(sessionService, never()).issue(anyLong(), any(), any(), anyBoolean(), any());
        verify(wechatIdentityPort, times(1)).codeToSession(any(), any());
    }
}
