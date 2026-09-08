package cn.iocoder.yudao.module.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付通道 Stub（D-10/G0B 前使用；证据上限 AUTOMATED_VERIFIED）
 *
 * 行为：
 * - 预下单返回确定性 prepayId；通知体按约定 JSON 解析；验签直通 PASSED；
 * - 查单/退款剧本可由测试或运维注入（queryScript/refundScript：orderNo → state），未注入时默认
 *   「已创建预支付的订单查单返回 SUCCEEDED、退款返回 SUCCEEDED」。
 */
@Component
public class StubPaymentPortAdapter implements PaymentPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${zhongshu.commerce.payment.merchant-id:stub-merchant}")
    private String merchantId;

    /** 查单剧本：orderNo → state（SUCCEEDED/PENDING/...）；测试与运维注入 */
    private final Map<String, String> queryScript = new ConcurrentHashMap<>();

    /** 退款剧本：orderNo → state */
    private final Map<String, String> refundScript = new ConcurrentHashMap<>();

    /** 查单金额剧本：orderNo → 渠道实付（分） */
    private final Map<String, Long> amountScript = new ConcurrentHashMap<>();

    public void scriptAmount(String orderNo, Long amountCents) {
        amountScript.put(orderNo, amountCents);
    }

    public void scriptQuery(String orderNo, String state) {
        queryScript.put(orderNo, state);
    }

    public void scriptRefund(String orderNo, String state) {
        refundScript.put(orderNo, state);
    }

    public void clearScripts() {
        queryScript.clear();
        refundScript.clear();
        amountScript.clear();
    }

    @Override
    public PrepayResult createPrepay(String orderNo, long amountCents, String description) {
        return new PrepayResult("stub-prepay-" + orderNo,
                Map.of("prepayId", "stub-prepay-" + orderNo, "amount", String.valueOf(amountCents)));
    }

    @Override
    public boolean verifyNotification(Map<String, String> headers, byte[] body) {
        return true; // Stub 直通；真实渠道验签在 D-10 后实现
    }

    @Override
    public NormalizedNotification parseNotification(Map<String, String> headers, byte[] body) {
        try {
            JsonNode node = JSON.readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            return new NormalizedNotification(
                    node.path("eventId").asText(),
                    node.path("orderNo").asText(),
                    node.path("transactionId").asText(),
                    node.path("amountCents").asLong(),
                    Instant.ofEpochSecond(node.path("paidAtEpochSecond").asLong()));
        } catch (Exception e) {
            throw new IllegalStateException("Stub 通知体解析失败", e);
        }
    }

    @Override
    public ChannelQueryResult queryOrder(String orderNo) {
        String scripted = queryScript.get(orderNo);
        String state = scripted == null ? "SUCCEEDED" : scripted;
        Long amount = amountScript.get(orderNo);
        return new ChannelQueryResult(state, "txn-" + orderNo, amount);
    }

    @Override
    public ChannelRefundResult requestRefund(String orderNo, String channelRefundId, long amountCents) {
        String scripted = refundScript.get(orderNo);
        return new ChannelRefundResult(scripted == null ? "SUCCEEDED" : scripted);
    }

    @Override
    public ChannelRefundResult queryRefund(String orderNo, String channelRefundId) {
        // Stub：与请求剧本一致；真实渠道按退款单号查
        String scripted = refundScript.get(orderNo);
        return new ChannelRefundResult(scripted == null ? "SUCCEEDED" : scripted);
    }

}
