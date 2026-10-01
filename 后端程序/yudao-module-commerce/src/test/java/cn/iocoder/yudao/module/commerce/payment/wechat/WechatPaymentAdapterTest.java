package cn.iocoder.yudao.module.commerce.payment.wechat;

import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import com.github.binarywang.wxpay.bean.notify.OriginNotifyResponse;
import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.notify.WxPayRefundNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayRefundQueryV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayRefundV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T13-23 泳道 A 隔离测试：{@link WechatPaymentAdapter}。
 *
 * <p>测试策略：Mockito mock {@link WxPayService}（T13-03 三类隔离手段之「SDK mock」），
 * 不触达任何真实微信端点、不产生任何真实消费。覆盖分派表 §3.1 T13-23 要求的四类场景：
 * 正常预支付 / 超时后查单已存在（OUT_TRADE_NO_EXIST 兜底）/ 幂等键重放 / 金额与订单快照一致。
 *
 * <p>额外覆盖：channel()/merchantId() 来源、trade_state 五态映射、refund 四态映射、
 * 通知验签头检查、通知解析规范化、异常消息不回显密钥。
 */
@ExtendWith(MockitoExtension.class)
class WechatPaymentAdapterTest {

    private static final String APP_ID = "wx-test-appid";
    private static final String MCH_ID = "mch-test-001";
    private static final String NOTIFY_URL = "https://test.example.com/design/v1/payments/wechat/notify";

    @Mock
    private WxPayService wxPayService;

    private WechatPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        WechatPayProperties props = new WechatPayProperties();
        props.setMerchantId(MCH_ID);
        props.setMerchantSerialNo("test-serial-no");
        props.setApiV3Key("test-api-v3-key-32bytes-long!!!!");
        props.setMerchantPrivateKeyPath("classpath:test-only/apiclient_key.pem");
        props.setNotifyUrl(NOTIFY_URL);
        adapter = new WechatPaymentAdapter(props, APP_ID, wxPayService);
    }

    // ========== channel / merchantId ==========

    @Test
    @DisplayName("channel() 固定返回 WECHAT")
    void channelReturnsWechat() {
        assertThat(adapter.channel()).isEqualTo("WECHAT");
    }

    @Test
    @DisplayName("merchantId() 取自 WechatPayProperties")
    void merchantIdFromProperties() {
        assertThat(adapter.merchantId()).isEqualTo(MCH_ID);
    }

    // ========== createPrepay 正常路径 ==========

    @Test
    @DisplayName("createPrepay 正常返回 JsapiResult 六参数")
    void createPrepayReturnsPayParams() throws Exception {
        WxPayUnifiedOrderV3Result.JsapiResult jsapi = new WxPayUnifiedOrderV3Result.JsapiResult()
                .setAppId(APP_ID)
                .setTimeStamp("1694400000")
                .setNonceStr("nonce-abc")
                .setPackageValue("prepay_id=wx-prepay-001")
                .setSignType("RSA")
                .setPaySign("sign-xyz")
                .setPrepayId("wx-prepay-001");
        when(wxPayService.createOrderV3(eq(TradeTypeEnum.JSAPI), any(WxPayUnifiedOrderV3Request.class)))
                .thenReturn(jsapi);

        PaymentPort.PrepayResult result = adapter.createPrepay("R1001", 1000L, "充值", "openid-user-1");

        assertThat(result.getPrepayId()).isEqualTo("wx-prepay-001");
        assertThat(result.getCallParams())
                .containsEntry("appId", APP_ID)
                .containsEntry("timeStamp", "1694400000")
                .containsEntry("nonceStr", "nonce-abc")
                .containsEntry("package", "prepay_id=wx-prepay-001")
                .containsEntry("signType", "RSA")
                .containsEntry("paySign", "sign-xyz");

        // 断言出站请求体：金额与订单快照一致（分派表 §3.1 T13-23 隔离测试要求）
        ArgumentCaptor<WxPayUnifiedOrderV3Request> captor =
                ArgumentCaptor.forClass(WxPayUnifiedOrderV3Request.class);
        verify(wxPayService).createOrderV3(eq(TradeTypeEnum.JSAPI), captor.capture());
        WxPayUnifiedOrderV3Request sent = captor.getValue();
        assertThat(sent.getOutTradeNo()).isEqualTo("R1001");
        assertThat(sent.getAmount().getTotal()).isEqualTo(1000);
        assertThat(sent.getAmount().getCurrency()).isEqualTo("CNY");
        assertThat(sent.getPayer().getOpenid()).isEqualTo("openid-user-1");
        assertThat(sent.getNotifyUrl()).isEqualTo(NOTIFY_URL);
        assertThat(sent.getAppid()).isEqualTo(APP_ID);
        assertThat(sent.getMchid()).isEqualTo(MCH_ID);
    }

    // ========== createPrepay 幂等兜底 ==========

    @Test
    @DisplayName("createPrepay OUT_TRADE_NO_EXIST 且已支付 → 查单兜底返回 ALREADY_PAID")
    void createPrepayOnOutTradeNoExistFallsBackToQuery() throws Exception {
        // 第一次 createOrderV3 抛 OUT_TRADE_NO_EXIST
        WxPayException existException = new WxPayException("订单已存在");
        existException.setErrCode("OUT_TRADE_NO_EXIST");
        when(wxPayService.createOrderV3(eq(TradeTypeEnum.JSAPI), any(WxPayUnifiedOrderV3Request.class)))
                .thenThrow(existException);
        // 查单返回 SUCCESS
        WxPayOrderQueryV3Result queryResult = new WxPayOrderQueryV3Result();
        queryResult.setTradeState("SUCCESS");
        queryResult.setTransactionId("wx-txn-001");
        WxPayOrderQueryV3Result.Amount amount = new WxPayOrderQueryV3Result.Amount();
        amount.setTotal(1000);
        queryResult.setAmount(amount);
        when(wxPayService.queryOrderV3(eq("R1002"), eq(null))).thenReturn(queryResult);

        PaymentPort.PrepayResult result = adapter.createPrepay("R1002", 1000L, "充值", "openid-user-2");

        assertThat(result.getCallParams()).containsEntry("state", "ALREADY_PAID");
        assertThat(result.getCallParams()).containsEntry("transactionId", "wx-txn-001");
        // 确认走了查单兜底
        verify(wxPayService).queryOrderV3("R1002", null);
    }

    @Test
    @DisplayName("createPrepay ORDERPAID 且已支付 → 查单兜底返回 ALREADY_PAID")
    void createPrepayOnOrderPaidFallsBackToQuery() throws Exception {
        WxPayException paidException = new WxPayException("订单已支付");
        paidException.setErrCode("ORDERPAID");
        when(wxPayService.createOrderV3(eq(TradeTypeEnum.JSAPI), any(WxPayUnifiedOrderV3Request.class)))
                .thenThrow(paidException);
        WxPayOrderQueryV3Result queryResult = new WxPayOrderQueryV3Result();
        queryResult.setTradeState("SUCCESS");
        queryResult.setTransactionId("wx-txn-002");
        when(wxPayService.queryOrderV3(eq("R1003"), eq(null))).thenReturn(queryResult);

        PaymentPort.PrepayResult result = adapter.createPrepay("R1003", 2000L, "充值", "openid-user-3");

        assertThat(result.getCallParams()).containsEntry("state", "ALREADY_PAID");
    }

    @Test
    @DisplayName("createPrepay OUT_TRADE_NO_EXIST 但未支付 → 返回 PENDING 标记")
    void createPrepayOnOutTradeNoExistNotPaidReturnsPending() throws Exception {
        WxPayException existException = new WxPayException("订单已存在");
        existException.setErrCode("OUT_TRADE_NO_EXIST");
        when(wxPayService.createOrderV3(eq(TradeTypeEnum.JSAPI), any(WxPayUnifiedOrderV3Request.class)))
                .thenThrow(existException);
        WxPayOrderQueryV3Result queryResult = new WxPayOrderQueryV3Result();
        queryResult.setTradeState("NOTPAY");
        when(wxPayService.queryOrderV3(eq("R1004"), eq(null))).thenReturn(queryResult);

        PaymentPort.PrepayResult result = adapter.createPrepay("R1004", 500L, "充值", "openid-user-4");

        assertThat(result.getCallParams()).containsEntry("state", "PENDING");
    }

    @Test
    @DisplayName("createPrepay 其他 WxPayException → 抛净化异常（不回显密钥）")
    void createPrepayOtherExceptionThrowsSanitized() throws Exception {
        WxPayException otherException = new WxPayException("签名错误");
        otherException.setErrCode("SIGN_ERROR");
        when(wxPayService.createOrderV3(eq(TradeTypeEnum.JSAPI), any(WxPayUnifiedOrderV3Request.class)))
                .thenThrow(otherException);

        assertThatThrownBy(() -> adapter.createPrepay("R1005", 100L, "充值", "openid-user-5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGN_ERROR")
                .hasMessageNotContaining("test-api-v3-key")
                .hasMessageNotContaining("apiclient_key.pem");
    }

    // ========== queryOrder 状态映射 ==========

    @Test
    @DisplayName("queryOrder SUCCESS → SUCCEEDED，金额与渠道一致")
    void queryOrderMapsSuccessToSucceeded() throws Exception {
        WxPayOrderQueryV3Result result = new WxPayOrderQueryV3Result();
        result.setTradeState("SUCCESS");
        result.setTransactionId("wx-txn-100");
        WxPayOrderQueryV3Result.Amount amount = new WxPayOrderQueryV3Result.Amount();
        amount.setTotal(1000);
        result.setAmount(amount);
        when(wxPayService.queryOrderV3("R2001", null)).thenReturn(result);

        PaymentPort.ChannelQueryResult query = adapter.queryOrder("R2001");

        assertThat(query.getState()).isEqualTo("SUCCEEDED");
        assertThat(query.getChannelTransactionId()).isEqualTo("wx-txn-100");
        assertThat(query.getAmountCents()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("queryOrder NOTPAY/USERPAYING → PENDING")
    void queryOrderMapsNotPayToPending() throws Exception {
        WxPayOrderQueryV3Result result = new WxPayOrderQueryV3Result();
        result.setTradeState("NOTPAY");
        when(wxPayService.queryOrderV3("R2002", null)).thenReturn(result);
        assertThat(adapter.queryOrder("R2002").getState()).isEqualTo("PENDING");

        result.setTradeState("USERPAYING");
        assertThat(adapter.queryOrder("R2002").getState()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("queryOrder CLOSED/REVOKED → CLOSED")
    void queryOrderMapsClosedToClosed() throws Exception {
        WxPayOrderQueryV3Result result = new WxPayOrderQueryV3Result();
        result.setTradeState("CLOSED");
        when(wxPayService.queryOrderV3("R2003", null)).thenReturn(result);
        assertThat(adapter.queryOrder("R2003").getState()).isEqualTo("CLOSED");

        result.setTradeState("REVOKED");
        assertThat(adapter.queryOrder("R2003").getState()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("queryOrder PAYERROR → FAILED")
    void queryOrderMapsPayErrorToFailed() throws Exception {
        WxPayOrderQueryV3Result result = new WxPayOrderQueryV3Result();
        result.setTradeState("PAYERROR");
        when(wxPayService.queryOrderV3("R2004", null)).thenReturn(result);
        assertThat(adapter.queryOrder("R2004").getState()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("queryOrder 未知 trade_state → UNKNOWN")
    void queryOrderMapsUnknownStateToUnknown() throws Exception {
        WxPayOrderQueryV3Result result = new WxPayOrderQueryV3Result();
        result.setTradeState("SOME_FUTURE_STATE");
        when(wxPayService.queryOrderV3("R2005", null)).thenReturn(result);
        assertThat(adapter.queryOrder("R2005").getState()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("queryOrder ORDER_NOT_EXIST → UNKNOWN（审查 H1 收口）")
    void queryOrderOrderNotExistReturnsUnknown() throws Exception {
        WxPayException notExist = new WxPayException("订单不存在");
        notExist.setErrCode("ORDER_NOT_EXIST");
        when(wxPayService.queryOrderV3("R2006", null)).thenThrow(notExist);

        PaymentPort.ChannelQueryResult query = adapter.queryOrder("R2006");

        assertThat(query.getState()).isEqualTo("UNKNOWN");
        assertThat(query.getChannelTransactionId()).isNull();
    }

    // ========== verifyNotification ==========

    @Test
    @DisplayName("verifyNotification 四头齐备 → true")
    void verifyNotificationWithAllHeadersReturnsTrue() {
        Map<String, String> headers = Map.of(
                "Wechatpay-Signature", "sig-abc",
                "Wechatpay-Timestamp", "1694400000",
                "Wechatpay-Nonce", "nonce-xyz",
                "Wechatpay-Serial", "serial-001");
        assertThat(adapter.verifyNotification(headers, "{}".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    @DisplayName("verifyNotification 缺签名头 → false")
    void verifyNotificationMissingSignatureReturnsFalse() {
        Map<String, String> headers = Map.of(
                "Wechatpay-Timestamp", "1694400000",
                "Wechatpay-Nonce", "nonce-xyz",
                "Wechatpay-Serial", "serial-001");
        assertThat(adapter.verifyNotification(headers, "{}".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    @DisplayName("verifyNotification null headers → false")
    void verifyNotificationNullHeadersReturnsFalse() {
        assertThat(adapter.verifyNotification(null, "{}".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    // ========== parseNotification ==========

    @Test
    @DisplayName("parseNotification 正常解析 → NormalizedNotification 五字段")
    void parseNotificationExtractsNormalizedEvent() throws Exception {
        // 构造 SDK 返回的 WxPayNotifyV3Result
        WxPayNotifyV3Result notifyResult = new WxPayNotifyV3Result();
        OriginNotifyResponse rawData = new OriginNotifyResponse();
        rawData.setId("notify-id-001");
        notifyResult.setRawData(rawData);
        WxPayNotifyV3Result.DecryptNotifyResult decrypt = new WxPayNotifyV3Result.DecryptNotifyResult();
        decrypt.setOutTradeNo("R3001");
        decrypt.setTransactionId("wx-txn-300");
        decrypt.setTradeState("SUCCESS");
        decrypt.setSuccessTime("2026-09-11T10:00:00+08:00");
        WxPayNotifyV3Result.Amount amount = new WxPayNotifyV3Result.Amount();
        amount.setTotal(1000);
        decrypt.setAmount(amount);
        notifyResult.setResult(decrypt);

        when(wxPayService.parseOrderNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(notifyResult);

        Map<String, String> headers = Map.of(
                "Wechatpay-Signature", "sig-abc",
                "Wechatpay-Timestamp", "1694400000",
                "Wechatpay-Nonce", "nonce-xyz",
                "Wechatpay-Serial", "serial-001");
        byte[] body = "{\"id\":\"notify-id-001\"}".getBytes(StandardCharsets.UTF_8);

        PaymentPort.NormalizedNotification notification = adapter.parseNotification(headers, body);

        assertThat(notification.getEventId()).isEqualTo("notify-id-001");
        assertThat(notification.getOrderNo()).isEqualTo("R3001");
        assertThat(notification.getChannelTransactionId()).isEqualTo("wx-txn-300");
        assertThat(notification.getAmountCents()).isEqualTo(1000L);
        assertThat(notification.getPaidAt()).isNotNull();

        // 断言 SignatureHeader 正确传递
        ArgumentCaptor<SignatureHeader> sigCaptor = ArgumentCaptor.forClass(SignatureHeader.class);
        verify(wxPayService).parseOrderNotifyV3Result(any(String.class), sigCaptor.capture());
        SignatureHeader sig = sigCaptor.getValue();
        assertThat(sig.getTimeStamp()).isEqualTo("1694400000");
        assertThat(sig.getNonce()).isEqualTo("nonce-xyz");
        assertThat(sig.getSignature()).isEqualTo("sig-abc");
        assertThat(sig.getSerial()).isEqualTo("serial-001");
    }

    @Test
    @DisplayName("parseNotification SDK 验签失败 → 抛净化异常（不回显密钥）")
    void parseNotificationVerificationFailureThrowsSanitized() throws Exception {
        WxPayException sigException = new WxPayException("验签失败");
        sigException.setErrCode("SIGN_ERROR");
        when(wxPayService.parseOrderNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenThrow(sigException);

        Map<String, String> headers = Map.of(
                "Wechatpay-Signature", "bad-sig",
                "Wechatpay-Timestamp", "1694400000",
                "Wechatpay-Nonce", "nonce-xyz",
                "Wechatpay-Serial", "serial-001");

        assertThatThrownBy(() -> adapter.parseNotification(headers, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGN_ERROR")
                .hasMessageNotContaining("test-api-v3-key");
    }

    // ========== requestRefund ==========

    @Test
    @DisplayName("requestRefund SUCCESS → SUCCEEDED，金额与订单一致")
    void requestRefundReturnsSucceeded() throws Exception {
        WxPayRefundV3Result refundResult = new WxPayRefundV3Result();
        refundResult.setStatus("SUCCESS");
        when(wxPayService.refundV3(any(WxPayRefundV3Request.class))).thenReturn(refundResult);

        PaymentPort.ChannelRefundResult result = adapter.requestRefund("R4001", "refund-001", 1000L);

        assertThat(result.getState()).isEqualTo("SUCCEEDED");

        // 断言出站请求体：整单全额退款（refund = total = amountCents）
        ArgumentCaptor<WxPayRefundV3Request> captor = ArgumentCaptor.forClass(WxPayRefundV3Request.class);
        verify(wxPayService).refundV3(captor.capture());
        WxPayRefundV3Request sent = captor.getValue();
        assertThat(sent.getOutTradeNo()).isEqualTo("R4001");
        assertThat(sent.getOutRefundNo()).isEqualTo("refund-001");
        assertThat(sent.getAmount().getRefund()).isEqualTo(1000);
        assertThat(sent.getAmount().getTotal()).isEqualTo(1000);
        assertThat(sent.getAmount().getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("requestRefund PROCESSING → PROCESSING（红线 5：不得视为成功或失败）")
    void requestRefundReturnsProcessing() throws Exception {
        WxPayRefundV3Result refundResult = new WxPayRefundV3Result();
        refundResult.setStatus("PROCESSING");
        when(wxPayService.refundV3(any(WxPayRefundV3Request.class))).thenReturn(refundResult);

        assertThat(adapter.requestRefund("R4002", "refund-002", 500L).getState())
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("requestRefund ABNORMAL remains pending")
    void requestRefundAbnormalRemainsPending() throws Exception {
        WxPayRefundV3Result refundResult = new WxPayRefundV3Result();
        refundResult.setStatus("ABNORMAL");
        when(wxPayService.refundV3(any(WxPayRefundV3Request.class))).thenReturn(refundResult);

        assertThat(adapter.requestRefund("R4003", "refund-003", 500L).getState())
                .isEqualTo("ABNORMAL");
    }

    // ========== queryRefund ==========

    @Test
    @DisplayName("queryRefund SUCCESS → SUCCEEDED")
    void queryRefundReturnsSucceeded() throws Exception {
        WxPayRefundQueryV3Result queryResult = new WxPayRefundQueryV3Result();
        queryResult.setStatus("SUCCESS");
        when(wxPayService.refundQueryV3("refund-005")).thenReturn(queryResult);

        assertThat(adapter.queryRefund("R5001", "refund-005").getState())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("queryRefund PROCESSING → PROCESSING（T13-29 定时查单直到终态）")
    void queryRefundReturnsProcessing() throws Exception {
        WxPayRefundQueryV3Result queryResult = new WxPayRefundQueryV3Result();
        queryResult.setStatus("PROCESSING");
        when(wxPayService.refundQueryV3("refund-006")).thenReturn(queryResult);

        assertThat(adapter.queryRefund("R5002", "refund-006").getState())
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("queryRefund 未知 status → UNKNOWN")
    void queryRefundUnknownStatusMapsToUnknown() throws Exception {
        WxPayRefundQueryV3Result queryResult = new WxPayRefundQueryV3Result();
        queryResult.setStatus("SOME_FUTURE_STATUS");
        when(wxPayService.refundQueryV3("refund-007")).thenReturn(queryResult);

        assertThat(adapter.queryRefund("R5003", "refund-007").getState())
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("queryRefund WxPayException → 抛净化异常")
    void queryRefundExceptionThrowsSanitized() throws Exception {
        WxPayException ex = new WxPayException("退款单不存在");
        ex.setErrCode("REFUND_NOT_EXIST");
        when(wxPayService.refundQueryV3("refund-008")).thenThrow(ex);

        assertThatThrownBy(() -> adapter.queryRefund("R5004", "refund-008"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUND_NOT_EXIST");
    }

    // ========== parseRefundNotification（T13-28） ==========

    /** 退款通知的 4 个签名头（与支付通知同名，微信 v3 共用一套） */
    private static final Map<String, String> REFUND_HEADERS = Map.of(
            "Wechatpay-Signature", "sig-refund",
            "Wechatpay-Timestamp", "1694400000",
            "Wechatpay-Nonce", "nonce-refund",
            "Wechatpay-Serial", "serial-refund");

    /** 构造 SDK 退款通知结果夹具；传 null 的字段模拟渠道未返回 */
    private WxPayRefundNotifyV3Result refundNotify(String notifyId, String status,
                                                  Integer refundCents, String successTime) {
        WxPayRefundNotifyV3Result result = new WxPayRefundNotifyV3Result();
        if (notifyId != null) {
            OriginNotifyResponse rawData = new OriginNotifyResponse();
            rawData.setId(notifyId);
            result.setRawData(rawData);
        }
        WxPayRefundNotifyV3Result.DecryptNotifyResult decrypt = new WxPayRefundNotifyV3Result.DecryptNotifyResult();
        decrypt.setOutTradeNo("R6001");
        decrypt.setOutRefundNo("refund-601");
        decrypt.setRefundId("wx-refund-601");
        decrypt.setRefundStatus(status);
        decrypt.setSuccessTime(successTime);
        if (refundCents != null) {
            WxPayRefundNotifyV3Result.Amount amount = new WxPayRefundNotifyV3Result.Amount();
            amount.setRefund(refundCents);
            decrypt.setAmount(amount);
        }
        result.setResult(decrypt);
        return result;
    }

    @Test
    @DisplayName("parseRefundNotification SUCCESS → 六字段规范化（含 out_refund_no 与退款金额）")
    void parseRefundNotificationExtractsNormalizedEvent() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("refund-notify-001", "SUCCESS", 1000, "2026-09-11T10:00:00+08:00"));

        byte[] body = "{\"id\":\"refund-notify-001\"}".getBytes(StandardCharsets.UTF_8);
        PaymentPort.NormalizedRefundNotification n = adapter.parseRefundNotification(REFUND_HEADERS, body);

        assertThat(n.getEventId()).isEqualTo("refund-notify-001");
        assertThat(n.getOrderNo()).isEqualTo("R6001");
        assertThat(n.getChannelRefundId()).isEqualTo("refund-601");
        assertThat(n.getState()).isEqualTo("SUCCEEDED");
        assertThat(n.getRefundAmountCents()).isEqualTo(1000L);
        assertThat(n.getRefundedAt()).isNotNull();

        // 断言 SignatureHeader 正确传递（验签材料不得丢字段）
        ArgumentCaptor<SignatureHeader> sigCaptor = ArgumentCaptor.forClass(SignatureHeader.class);
        verify(wxPayService).parseRefundNotifyV3Result(any(String.class), sigCaptor.capture());
        assertThat(sigCaptor.getValue().getSignature()).isEqualTo("sig-refund");
        assertThat(sigCaptor.getValue().getSerial()).isEqualTo("serial-refund");
        assertThat(sigCaptor.getValue().getTimeStamp()).isEqualTo("1694400000");
        assertThat(sigCaptor.getValue().getNonce()).isEqualTo("nonce-refund");
    }

    @Test
    @DisplayName("parseRefundNotification 通知 id 缺失 → eventId 回退 refundId（Inbox 幂等键不得为空）")
    void parseRefundNotificationFallsBackToRefundIdWhenNotifyIdMissing() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify(null, "SUCCESS", 1000, "2026-09-11T10:00:00+08:00"));

        var n = adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(n.getEventId()).isEqualTo("wx-refund-601");
    }

    @Test
    @DisplayName("parseRefundNotification PROCESSING → PROCESSING（红线 5：不得视为成功或失败）")
    void parseRefundNotificationProcessingKeepsNonFinalState() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("rn-proc", "PROCESSING", 1000, null));

        var n = adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(n.getState()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("parseRefundNotification ABNORMAL remains pending；未知状态 → UNKNOWN")
    void parseRefundNotificationMapsAbnormalAndUnknownStates() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("rn-abn", "ABNORMAL", 1000, null));
        assertThat(adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8)).getState())
                .isEqualTo("ABNORMAL");

        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("rn-weird", "SOME_NEW_STATE", 1000, null));
        assertThat(adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8)).getState())
                .as("未知状态必须归 UNKNOWN，不得默认成功/失败").isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("parseRefundNotification 缺 success_time → refundedAt 为 null（不用 now() 伪造）")
    void parseRefundNotificationLeavesRefundedAtNullWhenSuccessTimeMissing() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("rn-notime", "PROCESSING", 1000, null));

        var n = adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(n.getRefundedAt()).isNull();
    }

    @Test
    @DisplayName("parseRefundNotification 缺 amount → 退款金额 0（由上层金额一致性校验拒绝）")
    void parseRefundNotificationMissingAmountYieldsZero() throws Exception {
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenReturn(refundNotify("rn-noamt", "SUCCESS", null, "2026-09-11T10:00:00+08:00"));

        var n = adapter.parseRefundNotification(REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(n.getRefundAmountCents()).isZero();
    }

    @Test
    @DisplayName("parseRefundNotification SDK 验签失败 → 抛净化异常（不回显 APIv3 密钥）")
    void parseRefundNotificationVerificationFailureThrowsSanitized() throws Exception {
        WxPayException ex = new WxPayException("验签失败");
        ex.setErrCode("SIGN_ERROR");
        when(wxPayService.parseRefundNotifyV3Result(any(String.class), any(SignatureHeader.class)))
                .thenThrow(ex);

        assertThatThrownBy(() -> adapter.parseRefundNotification(
                REFUND_HEADERS, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGN_ERROR")
                .hasMessageNotContaining("test-api-v3-key");
    }

    // ========== buildWxPayService 静态方法 ==========

    @Test
    @DisplayName("buildWxPayService 正确装配 WxPayConfig 六字段")
    void buildWxPayServiceConfiguresCorrectly() {
        WechatPayProperties props = new WechatPayProperties();
        props.setMerchantId("mch-build-001");
        props.setMerchantSerialNo("serial-build-001");
        props.setApiV3Key("build-api-v3-key-32bytes-long!!!");
        props.setMerchantPrivateKeyPath("classpath:build-test/key.pem");
        props.setNotifyUrl("https://build.example.com/notify");
        props.setRefundNotifyUrl("https://build.example.com/refund-notify");

        WxPayService service = WechatPaymentAdapter.buildWxPayService(props, "wx-build-appid");

        assertThat(service).isNotNull();
        // 通过 getConfig() 验证装配（WxPayServiceImpl 继承 BaseWxPayServiceImpl.getConfig()）
        var config = ((com.github.binarywang.wxpay.service.impl.BaseWxPayServiceImpl) service).getConfig();
        assertThat(config.getAppId()).isEqualTo("wx-build-appid");
        assertThat(config.getMchId()).isEqualTo("mch-build-001");
        assertThat(config.getCertSerialNo()).isEqualTo("serial-build-001");
        assertThat(config.getApiV3Key()).isEqualTo("build-api-v3-key-32bytes-long!!!");
        assertThat(config.getPrivateKeyPath()).isEqualTo("classpath:build-test/key.pem");
        assertThat(config.getNotifyUrl()).isEqualTo("https://build.example.com/notify");
        assertThat(config.getRefundNotifyUrl()).isEqualTo("https://build.example.com/refund-notify");
        assertThat(config.getCertAutoUpdateTime()).isEqualTo(12);
    }

    @Test
    @DisplayName("buildWxPayService refundNotifyUrl 为空时不设置")
    void buildWxPayServiceSkipsBlankRefundNotifyUrl() {
        WechatPayProperties props = new WechatPayProperties();
        props.setMerchantId("mch-build-002");
        props.setMerchantSerialNo("serial-build-002");
        props.setApiV3Key("build-api-v3-key-32bytes-long!!!");
        props.setMerchantPrivateKeyPath("classpath:build-test/key.pem");
        props.setNotifyUrl("https://build.example.com/notify");
        // refundNotifyUrl 不设置（null）

        WxPayService service = WechatPaymentAdapter.buildWxPayService(props, "wx-build-appid-2");

        var config = ((com.github.binarywang.wxpay.service.impl.BaseWxPayServiceImpl) service).getConfig();
        assertThat(config.getRefundNotifyUrl()).isNull();
    }
    @Test
    void refundQueryAuthoritativeMissingIsDistinctFromUncertainFailure() throws Exception {
        var absent = new WxPayException("fixture"); absent.setErrCode("RESOURCE_NOT_EXISTS");
        absent.setErrCodeDes("退款单不存在");
        when(wxPayService.refundQueryV3("refund-stable")).thenThrow(absent);
        assertThat(adapter.queryRefund("order", "refund-stable").getState()).isEqualTo("NOT_FOUND");
        verify(wxPayService, never()).refundV3(any());
    }

    @Test
    void certificateMissingAndTemporaryErrorsNeverMeanRefundAbsent() throws Exception {
        var certificate = new WxPayException("fixture"); certificate.setErrCode("RESOURCE_NOT_EXISTS");
        certificate.setErrCodeDes("平台证书不存在");
        var temporary = new WxPayException("fixture"); temporary.setErrCode("SYSTEM_ERROR");
        when(wxPayService.refundQueryV3("refund-stable")).thenThrow(certificate).thenThrow(temporary);
        assertThatThrownBy(() -> adapter.queryRefund("order", "refund-stable")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.queryRefund("order", "refund-stable")).isInstanceOf(IllegalStateException.class);
        verify(wxPayService, never()).refundV3(any());
    }

}
