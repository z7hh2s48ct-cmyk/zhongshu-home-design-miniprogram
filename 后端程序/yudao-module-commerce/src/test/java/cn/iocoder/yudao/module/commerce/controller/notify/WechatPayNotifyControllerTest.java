package cn.iocoder.yudao.module.commerce.controller.notify;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T13-25 · WechatPayNotifyController 隔离测试（MockMvc standalone + Mockito）。
 *
 * <p>覆盖分派表 §3.2 泳道 B 验收要求：
 * <ul>
 *   <li>合法签名通知 200 + {@code {"code":"SUCCESS"}}</li>
 *   <li>签名错误（{@link ServiceException}）4xx + {@code {"code":"FAIL"}}</li>
 *   <li>请求体超限（Content-Length &gt; 64KB）413 + FAIL</li>
 *   <li>缺 Wechat-* 签名头 4xx + FAIL（handleNotification 未被调用）</li>
 *   <li>Content-Type 非 JSON 4xx + FAIL（ServletUtils.getBodyBytes 返回 null）</li>
 *   <li>退款通知 200 + SUCCESS（T13-25 占位，handleNotification 未被调用）</li>
 *   <li>headers 完整传给 handleNotification（ArgumentCaptor 断言 4 个 Wechat-* 头）</li>
 *   <li>handleNotification 抛 RuntimeException 500 + FAIL</li>
 *   <li>handleNotification 返回 DUPLICATE 200 + SUCCESS</li>
 * </ul>
 *
 * <p>不复用 {@code PaymentP8AContractTest} 的 Testcontainers 上下文：本测试聚焦 Controller 层
 * 前置校验与响应格式契约，Service 层由 mock 隔离，跑得快且不依赖真实 DB。
 */
@ExtendWith(MockitoExtension.class)
class WechatPayNotifyControllerTest {

    private static final String NOTIFY_URL = "/design/v1/payments/wechat/notify";
    private static final String REFUND_NOTIFY_URL = "/design/v1/payments/wechat/refund-notify";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合法的通知 body（微信 v3 加密 JSON 结构；本测试不校验内容，只校验流程） */
    private static final String VALID_BODY = "{\"id\":\"evt-001\",\"resource\":{\"ciphertext\":\"xxx\"}}";
    private static final byte[] VALID_BODY_BYTES = VALID_BODY.getBytes(StandardCharsets.UTF_8);

    /** 4 个微信 v3 签名头（缺任意一个直接拒绝） */
    private static final String TIMESTAMP = "1725984000";
    private static final String NONCE = "nonce-abc";
    private static final String SIGNATURE = "sig-base64==";
    private static final String SERIAL = "serial-xyz";

    @Mock
    private RechargePaymentService rechargePaymentService;

    private WechatPayNotifyController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new WechatPayNotifyController();
        // @Resource 字段注入：standalone MockMvc 不走 Spring 容器，用 ReflectionTestUtils 手动注入 mock
        ReflectionTestUtils.setField(controller, "rechargePaymentService", rechargePaymentService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                // 复用框架 CacheRequestBodyFilter，让 ServletUtils.getBodyBytes 能重复读取
                .addFilters(new CacheRequestBodyFilter())
                .build();
    }

    // ==================== 支付通知：正常路径 ====================

    @Test
    @DisplayName("合法支付通知 → 200 + {\"code\":\"SUCCESS\"}；handleNotification 被调用一次")
    void paymentNotifyReturns200AndSuccessBody() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class))).thenReturn("PROCESSED");

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("成功"));

        verify(rechargePaymentService, times(1)).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("handleNotification 返回 DUPLICATE → 200 + SUCCESS（幂等重放，微信停止重试）")
    void duplicateNotificationReturns200() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class))).thenReturn("DUPLICATE");

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("headers 完整传给 handleNotification（4 个 Wechat-* 头 + body 字节）")
    void headersAndBodyPassedToService() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class))).thenReturn("PROCESSED");

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(rechargePaymentService).handleNotification(headersCaptor.capture(), bodyCaptor.capture());

        Map<String, String> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders)
                .as("4 个微信 v3 签名头必须完整透传给 handleNotification")
                .containsEntry("Wechatpay-Timestamp", TIMESTAMP)
                .containsEntry("Wechatpay-Nonce", NONCE)
                .containsEntry("Wechatpay-Signature", SIGNATURE)
                .containsEntry("Wechatpay-Serial", SERIAL)
                .hasSize(4);

        assertThat(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8))
                .as("原始 body 字节必须透传（供下游验签解密）")
                .isEqualTo(VALID_BODY);
    }

    // ==================== 支付通知：异常路径 ====================

    @Test
    @DisplayName("handleNotification 抛 ServiceException（验签失败）→ 400 + FAIL")
    void serviceExceptionReturns400AndFail() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class)))
                .thenThrow(new ServiceException(1_072_000_002, "充值订单状态不允许该操作"));

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("充值订单状态不允许该操作"));
    }

    @Test
    @DisplayName("handleNotification 抛 RuntimeException（系统内部错误）→ 500 + FAIL")
    void runtimeExceptionReturns500AndFail() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("系统异常"));
    }

    // ==================== 前置校验：请求体 ====================

    @Test
    @DisplayName("Content-Length 超 64KB → 413 + FAIL；handleNotification 未被调用")
    void contentLengthOver64KbReturns413() throws Exception {
        // 构造 65KB 的 body，Content-Length 由 MockMvc 自动设置
        byte[] oversized = new byte[65 * 1024];
        for (int i = 0; i < oversized.length; i++) {
            oversized[i] = 'a';
        }

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("请求体超限"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("Content-Length 恰好等于 64KB → 不拒绝（边界）；handleNotification 被调用")
    void contentLengthExactly64KbIsAllowed() throws Exception {
        byte[] exact = new byte[64 * 1024];
        for (int i = 0; i < exact.length; i++) {
            exact[i] = 'a';
        }
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class))).thenReturn("PROCESSED");

        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exact)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(rechargePaymentService, times(1)).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("请求体为空 → 400 + FAIL；handleNotification 未被调用")
    void emptyBodyReturns400() throws Exception {
        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[0])
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("请求体为空或非 JSON"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("Content-Type 非 JSON（text/plain）→ 400 + FAIL（ServletUtils.getBodyBytes 返回 null）")
    void nonJsonContentTypeReturns400() throws Exception {
        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("请求体为空或非 JSON"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    // ==================== 前置校验：签名头 ====================

    @Test
    @DisplayName("缺 Wechatpay-Signature 头 → 400 + FAIL；handleNotification 未被调用")
    void missingSignatureHeaderReturns400() throws Exception {
        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        // 故意缺 Wechatpay-Signature
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("缺少签名头"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("缺 Wechatpay-Serial 头 → 400 + FAIL；handleNotification 未被调用")
    void missingSerialHeaderReturns400() throws Exception {
        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE))
                // 故意缺 Wechatpay-Serial
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("缺少签名头"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("Wechatpay-Signature 为空字符串 → 400 + FAIL（StrUtil.hasBlank 拦截）")
    void blankSignatureHeaderReturns400() throws Exception {
        mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", "")
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("缺少签名头"));

        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    // ==================== 退款通知：T13-28 真实分流 ====================

    @Test
    @DisplayName("退款通知 → 200 + SUCCESS；委派 handleRefundNotification，不误入支付链路")
    void refundNotifyDelegatesToHandleRefundNotification() throws Exception {
        when(rechargePaymentService.handleRefundNotification(anyMap(), any(byte[].class)))
                .thenReturn("REVERSED");

        mvc.perform(post(REFUND_NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("成功"));

        // T13-28：退款通知必须走退款领域方法；误入 handleNotification 会用支付语义写 Inbox
        verify(rechargePaymentService).handleRefundNotification(anyMap(), any(byte[].class));
        verify(rechargePaymentService, never()).handleNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("退款渠道未终态（PENDING）→ 仍 200 + SUCCESS，避免微信 8 次退避重试")
    void refundNotifyPendingStateStillReturnsSuccess() throws Exception {
        // 分派表 §8 红线 5：PROCESSING/UNKNOWN 不做资金动作，但必须回 SUCCESS 终止重试，
        // 终态由 RefundRecoveryJob（T13-29）查单收口
        when(rechargePaymentService.handleRefundNotification(anyMap(), any(byte[].class)))
                .thenReturn("PENDING");

        mvc.perform(post(REFUND_NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("退款通知抛 ServiceException（金额不符/未知退款单）→ 400 + FAIL")
    void refundNotifyServiceExceptionReturns400() throws Exception {
        when(rechargePaymentService.handleRefundNotification(anyMap(), any(byte[].class)))
                .thenThrow(new ServiceException(1_072_000_002, "充值订单状态不允许该操作"));

        mvc.perform(post(REFUND_NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("充值订单状态不允许该操作"));
    }

    @Test
    @DisplayName("退款通知缺签名头 → 400 + FAIL（前置校验一致）")
    void refundNotifyMissingHeadersReturns400() throws Exception {
        mvc.perform(post(REFUND_NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES))
                // 4 个 Wechatpay-* 头全缺
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAIL"))
                .andExpect(jsonPath("$.message").value("缺少签名头"));

        verify(rechargePaymentService, never()).handleRefundNotification(anyMap(), any(byte[].class));
    }

    @Test
    @DisplayName("退款通知请求体超限 → 413 + FAIL（前置校验一致）")
    void refundNotifyOversizedReturns413() throws Exception {
        byte[] oversized = new byte[65 * 1024];
        mvc.perform(post(REFUND_NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FAIL"));

        verify(rechargePaymentService, never()).handleRefundNotification(anyMap(), any(byte[].class));
    }

    // ==================== 响应格式契约（分派表 §8 红线 6） ====================

    @Test
    @DisplayName("响应体必须是 {code, message} 两字段（禁止 CommonResult 的 data 字段）")
    void responseBodyHasOnlyCodeAndMessage() throws Exception {
        when(rechargePaymentService.handleNotification(anyMap(), any(byte[].class))).thenReturn("PROCESSED");

        String response = mvc.perform(post(NOTIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY_BYTES)
                        .header("Wechatpay-Timestamp", TIMESTAMP)
                        .header("Wechatpay-Nonce", NONCE)
                        .header("Wechatpay-Signature", SIGNATURE)
                        .header("Wechatpay-Serial", SERIAL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(response, Map.class);
        assertThat(body.keySet())
                .as("响应体必须只含 code/message，禁止 CommonResult 的 data 字段（分派表 §8 红线 6）")
                .containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("MAX_BODY_SIZE 常量 = 64 * 1024（分派表 §3.2 ④ 硬约束）")
    void maxBodySizeConstantIs64Kb() {
        assertThat(WechatPayNotifyController.MAX_BODY_SIZE)
                .as("请求体上限必须是 64KB")
                .isEqualTo(64 * 1024);
    }

    // ==================== 辅助 ====================

    /** 构造一个非 mock 的 ServiceException（ServiceException 无空构造，需传 code+msg） */
    @SuppressWarnings("unused")
    private static ServiceException serviceException(int code, String message) {
        return new ServiceException(code, message);
    }

    /** 显式声明 mock 类型，避免 IDE 误报"未使用" */
    @SuppressWarnings("unused")
    private static RechargePaymentService unusedMockReference() {
        return mock(RechargePaymentService.class);
    }
}
