package cn.iocoder.yudao.framework.web.core.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.web.core.util.SensitiveLogRedactor;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link GlobalExceptionHandler} 的请求体解析异常脱敏回归（T13-04 安全加固，codex 四/五轮）。
 *
 * <p>背景：畸形 JSON 请求体（如登录接口的 <code>{"code":LOGINSECRET}</code> 未加引号）会触发 Jackson 抛出
 * {@code HttpMessageNotReadableException}，其 {@code message} / {@code rootCauseMessage} / {@code stackTrace} 文本
 * 会回显原始请求体 token（{@code Unrecognized token 'LOGINSECRET'}）。此前这些文本经 {@code buildExceptionLog} 落入
 * 错误日志库（全环境），并被 {@code log.warn}/{@code log.error} 打印原始 throwable，构成 requestParams 之外的独立泄露向量。
 *
 * <p>本测试用<b>真实 Jackson 解析</b>复现该异常，验证 codex 四轮修复后：
 * <ul>
 *   <li>① 落库的 {@link ApiErrorLogCreateReqDTO} 的 message / rootCauseMessage / stackTrace / requestParams 均<b>不含</b>凭据，
 *       仅保留异常类型名与安全占位符；</li>
 *   <li>② 捕获的日志事件（格式化消息 + throwable 代理链）<b>不含</b>凭据——即不再把原始 throwable 交给 logger；</li>
 *   <li>③ 返回给客户端的响应体<b>不回显</b>凭据。</li>
 * </ul>
 * 覆盖两条路径：{@code @ExceptionHandler} 派发的 {@code methodArgumentTypeInvalidFormatExceptionHandler}，以及 Filter / 原始
 * {@code JsonProcessingException} 落入的 {@code defaultExceptionHandler}。另覆盖 codex 五轮 P2：清空栈帧后错误日志
 * 仍安全落库（占位类名 / unknown 文件方法 + 行号 -1），不因 {@code Assert.notEmpty} 抛异常而丢失审计记录。
 */
class GlobalExceptionHandlerTest {

    private static final String SECRET = "LOGINSECRET";
    /** 未加引号的 token → Jackson 抛 JsonParseException，message 回显 'LOGINSECRET'（codex 复现 fixture）。 */
    private static final String MALFORMED_BODY = "{\"code\":" + SECRET + "}";

    private ApiErrorLogCommonApi apiErrorLogApi;
    private GlobalExceptionHandler handler;

    private Logger handlerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        apiErrorLogApi = mock(ApiErrorLogCommonApi.class);
        handler = new GlobalExceptionHandler("test-app", apiErrorLogApi);
        // 挂载 ListAppender 捕获 GlobalExceptionHandler 的日志输出；强制放开级别，确保 warn/error 事件必被捕获
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.ALL);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
        handlerLogger.setLevel(originalLevel);
    }

    @Test
    void methodArgumentTypeInvalidFormatDoesNotLeakSecretToDtoOrLogs() {
        HttpMessageNotReadableException ex = newBodyParseException();
        MockHttpServletRequest req = newJsonRequest();

        // WebFrameworkUtils 的 getLoginUserType 依赖 Spring 注入的静态 properties，纯单测无上下文会 NPE，故静态屏蔽
        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            CommonResult<?> result = handler.methodArgumentTypeInvalidFormatExceptionHandler(req, ex);

            // ③ 响应不回显凭据
            assertThat(JsonUtils.toJsonString(result)).doesNotContain(SECRET);

            // ① 落库 DTO 全部字段脱敏，仅保留异常类型名 + 安全占位符
            ApiErrorLogCreateReqDTO dto = captureErrorLog();
            assertThat(dto.getExceptionName()).isEqualTo(HttpMessageNotReadableException.class.getName());
            assertThat(dto.getExceptionMessage()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertThat(dto.getExceptionRootCauseMessage()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertThat(dto.getExceptionStackTrace()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertDtoHasNoSecret(dto);
        }

        // ② 捕获日志不含凭据（格式化消息 + throwable 代理链）
        assertCapturedLogsHaveNoSecret();
    }

    @Test
    void defaultExceptionHandlerDoesNotLeakSecretForBodyParseException() {
        // 覆盖 Filter 路径 / 原始 JsonProcessingException 落 defaultExceptionHandler（line 343 守卫 + buildExceptionLog 脱敏）
        JsonProcessingException jacksonEx = newJacksonParseException();
        MockHttpServletRequest req = newJsonRequest();

        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            CommonResult<?> result = handler.defaultExceptionHandler(req, jacksonEx);

            assertThat(JsonUtils.toJsonString(result)).doesNotContain(SECRET);

            ApiErrorLogCreateReqDTO dto = captureErrorLog();
            assertThat(dto.getExceptionMessage()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertThat(dto.getExceptionStackTrace()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertDtoHasNoSecret(dto);
        }

        assertCapturedLogsHaveNoSecret();
    }

    @Test
    void emptyStackTraceStillPersistsSanitizedErrorLog() {
        // codex 五轮 P2：清空异常栈帧（setStackTrace(new StackTraceElement[0])）时，原 buildExceptionLog 的
        // Assert.notEmpty 会抛异常被 createExceptionLog 的 catch 吞掉、导致整条错误日志 DTO 不落库（丢失审计记录）。
        // 修复后：无栈帧用安全占位（类名回退为异常类名、file/method=unknown、行号=-1）继续落库，且异常文本仍脱敏、不含凭据。
        HttpMessageNotReadableException ex = newBodyParseException();
        ex.setStackTrace(new StackTraceElement[0]);
        MockHttpServletRequest req = newJsonRequest();

        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            handler.defaultExceptionHandler(req, ex);

            // 关键：createApiErrorLogAsync 仍被调用（captureErrorLog 的 verify 断言 DTO 落库，未因缺栈帧被丢弃）
            ApiErrorLogCreateReqDTO dto = captureErrorLog();
            assertThat(dto.getExceptionName()).isEqualTo(HttpMessageNotReadableException.class.getName());
            // 无栈帧 → 安全占位
            assertThat(dto.getExceptionClassName()).isEqualTo(HttpMessageNotReadableException.class.getName());
            assertThat(dto.getExceptionFileName()).isEqualTo("unknown");
            assertThat(dto.getExceptionMethodName()).isEqualTo("unknown");
            assertThat(dto.getExceptionLineNumber()).isEqualTo(-1);
            // 仍脱敏：异常文本为占位符，整条 DTO 不含凭据
            assertThat(dto.getExceptionMessage()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertThat(dto.getExceptionStackTrace()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            assertDtoHasNoSecret(dto);
        }

        assertCapturedLogsHaveNoSecret();
    }

    // ---------- 辅助方法 ----------

    /** 用真实 Jackson 解析畸形 body 得到 {@link JsonProcessingException}（message 回显原始 token）。 */
    private JsonProcessingException newJacksonParseException() {
        try {
            new ObjectMapper().readTree(MALFORMED_BODY);
        } catch (JsonProcessingException e) {
            // fixture 自检：确保 message 确实含凭据，否则后续 doesNotContain 断言无意义
            assertThat(e.getMessage()).contains(SECRET);
            return e;
        }
        throw new IllegalStateException("expected a Jackson parse failure for body: " + MALFORMED_BODY);
    }

    /** 模拟 Spring MVC 对畸形 {@code @RequestBody} 的封装：{@link HttpMessageNotReadableException} 包裹 Jackson 异常。 */
    private HttpMessageNotReadableException newBodyParseException() {
        JsonProcessingException cause = newJacksonParseException();
        return new HttpMessageNotReadableException("JSON parse error: " + cause.getMessage(),
                cause, new MockHttpInputMessage(MALFORMED_BODY.getBytes(StandardCharsets.UTF_8)));
    }

    private MockHttpServletRequest newJsonRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/app-api/identity/wechat/login");
        req.setContentType("application/json");
        req.setContent(MALFORMED_BODY.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /** 捕获传入 {@code createApiErrorLogAsync} 的错误日志 DTO。 */
    private ApiErrorLogCreateReqDTO captureErrorLog() {
        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(apiErrorLogApi).createApiErrorLogAsync(captor.capture());
        return captor.getValue();
    }

    /** 断言 DTO 的所有文本字段均不含凭据（含 round-3 已修的 requestParams.body）。 */
    private void assertDtoHasNoSecret(ApiErrorLogCreateReqDTO dto) {
        assertThat(dto.getRequestParams()).doesNotContain(SECRET);
        assertThat(dto.getExceptionMessage()).doesNotContain(SECRET);
        assertThat(dto.getExceptionRootCauseMessage()).doesNotContain(SECRET);
        assertThat(dto.getExceptionStackTrace()).doesNotContain(SECRET);
        // 序列化整体再兜底校验一次
        assertThat(JsonUtils.toJsonString(dto)).doesNotContain(SECRET);
    }

    /** 断言所有捕获日志事件的格式化消息与 throwable 代理链（含 cause）均不含凭据。 */
    private void assertCapturedLogsHaveNoSecret() {
        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
            IThrowableProxy proxy = event.getThrowableProxy();
            while (proxy != null) {
                assertThat(proxy.getMessage()).doesNotContain(SECRET);
                assertThat(proxy.getClassName()).doesNotContain(SECRET);
                proxy = proxy.getCause();
            }
        }
    }
}
