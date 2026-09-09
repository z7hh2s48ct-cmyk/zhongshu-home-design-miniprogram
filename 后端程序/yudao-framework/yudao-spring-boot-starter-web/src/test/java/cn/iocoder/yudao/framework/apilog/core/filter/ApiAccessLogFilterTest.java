package cn.iocoder.yudao.framework.apilog.core.filter;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.SensitiveLogRedactor;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link ApiAccessLogFilter} 的访问日志脱敏回归（T13-04 安全加固，codex 五轮，修 P1）。
 *
 * <p>背景：当下游 filter chain 在 {@code CommonResult} 被记录之前抛出请求体解析异常（畸形 JSON 触发的
 * {@code HttpMessageNotReadableException} / Jackson {@code JsonProcessingException}），{@code buildApiAccessLog} 走
 * {@code else if (ex != null)} 分支，把 {@code ExceptionUtil.getRootCauseMessage(ex)} <b>原文</b>写入访问日志库的
 * {@code resultMsg}。该异常文本会回显原始请求体 token（如 {@code Unrecognized token 'LOGINSECRET'}），构成
 * <b>独立于错误日志库路径</b>的凭据泄露向量——即便 {@code requestParams} 已脱敏也仍会泄露。
 *
 * <p>本测试用<b>真实 Jackson 解析</b>复现该异常，并让 mock 的 {@link FilterChain} 抛出它，验证 codex 五轮修复后：
 * <ul>
 *   <li>① 命中 {@code isBodyParseLeak} 时 {@code resultMsg} 被替换为安全占位符 {@link SensitiveLogRedactor#BODY_PARSE_OMITTED}，
 *       整条落库 DTO 不含凭据；</li>
 *   <li>② 回归守护：<b>非</b>请求体解析异常（普通 {@code RuntimeException}）仍保留其 {@code rootCauseMessage}，不被过度脱敏，
 *       以免损害正常排障能力。</li>
 * </ul>
 *
 * <p>测试置于与被测类同包，直接调用 {@code protected} 的 {@code doFilterInternal}，绕过 {@code OncePerRequestFilter}
 * 调度与 {@code shouldNotFilter} 前缀判定；{@code WebFrameworkUtils} 被静态屏蔽（其 {@code getLoginUserType} 依赖 Spring
 * 注入的静态 {@code properties}，纯单测无上下文会 NPE，且 {@code getCommonResult} 需返回 null 才能命中异常分支）。
 */
class ApiAccessLogFilterTest {

    private static final String SECRET = "LOGINSECRET";
    /** 未加引号的 token → Jackson 抛 JsonParseException，message 回显 'LOGINSECRET'（codex 复现 fixture）。 */
    private static final String MALFORMED_BODY = "{\"code\":" + SECRET + "}";

    private ApiAccessLogCommonApi apiAccessLogApi;
    private ApiAccessLogFilter filter;

    @BeforeEach
    void setUp() {
        apiAccessLogApi = mock(ApiAccessLogCommonApi.class);
        filter = new ApiAccessLogFilter(new WebProperties(), "test-app", apiAccessLogApi);
    }

    @Test
    void filterChainBodyParseExceptionDoesNotLeakSecretToAccessLog() throws Exception {
        HttpMessageNotReadableException ex = newBodyParseException();
        MockHttpServletRequest req = newJsonRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(ex).when(chain).doFilter(any(), any());

        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            // filter chain 抛出请求体解析异常后，doFilterInternal 先记录访问日志再重新抛出
            assertThatThrownBy(() -> filter.doFilterInternal(req, resp, chain))
                    .isInstanceOf(HttpMessageNotReadableException.class);

            ApiAccessLogCreateReqDTO dto = captureAccessLog();
            // ① P1 修复：resultMsg 不再原文回显 rootCauseMessage（含凭据），改用安全占位符
            assertThat(dto.getResultCode()).isEqualTo(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode());
            assertThat(dto.getResultMsg()).isEqualTo(SensitiveLogRedactor.BODY_PARSE_OMITTED);
            // 兜底：requestParams（body 已省略）与整条 DTO 序列化均不含凭据
            assertThat(dto.getRequestParams()).doesNotContain(SECRET);
            assertThat(JsonUtils.toJsonString(dto)).doesNotContain(SECRET);
        }
    }

    @Test
    void filterChainOrdinaryExceptionKeepsRootCauseMessage() throws Exception {
        RuntimeException ex = new RuntimeException("downstream boom");
        MockHttpServletRequest req = newJsonRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(ex).when(chain).doFilter(any(), any());

        try (MockedStatic<WebFrameworkUtils> ignored = mockStatic(WebFrameworkUtils.class)) {
            assertThatThrownBy(() -> filter.doFilterInternal(req, resp, chain))
                    .isInstanceOf(RuntimeException.class);

            ApiAccessLogCreateReqDTO dto = captureAccessLog();
            assertThat(dto.getResultCode()).isEqualTo(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode());
            // ② 回归守护：非请求体解析异常仍保留 rootCauseMessage（不被过度脱敏），且本就不含凭据
            assertThat(dto.getResultMsg()).contains("boom");
            assertThat(dto.getResultMsg()).doesNotContain(SECRET);
        }
    }

    // ---------- 辅助方法 ----------

    /** 用真实 Jackson 解析畸形 body 得到 {@link JsonProcessingException}（message 回显原始 token）。 */
    private JsonProcessingException newJacksonParseException() {
        try {
            new ObjectMapper().readTree(MALFORMED_BODY);
        } catch (JsonProcessingException e) {
            // fixture 自检：确保 rootCauseMessage 确实含凭据，否则后续 doesNotContain 断言无意义
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

    /** 捕获传入 {@code createApiAccessLogAsync} 的访问日志 DTO。 */
    private ApiAccessLogCreateReqDTO captureAccessLog() {
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(apiAccessLogApi).createApiAccessLogAsync(captor.capture());
        return captor.getValue();
    }
}
