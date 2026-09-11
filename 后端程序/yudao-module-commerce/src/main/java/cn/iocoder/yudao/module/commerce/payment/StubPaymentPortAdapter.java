package cn.iocoder.yudao.module.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>T13-22 新增：{@link #channel()} 固定返回 {@code "STUB"}；{@link #merchantId()} 返回配置项
 * {@code zhongshu.commerce.payment.merchant-id}（默认 {@code "stub-merchant"}）；{@link #createPrepay}
 * 新增 {@code openid} 参数，Stub 不消费但记录到 {@code openidByOrder}（供合同测试断言 openid 已透传）。
 */
@Component
// T13-01 装配条件：zhongshu.commerce.payment.provider=stub；真实微信支付由 B4 的 real 实现替换。
// 生产禁止 stub，由 ZhongshuWiringEnvironmentPostProcessor 启动守卫强制。
@ConditionalOnProperty(prefix = "zhongshu.commerce.payment", name = "provider", havingValue = "stub")
public class StubPaymentPortAdapter implements PaymentPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** T13-22：渠道标识固定为 {@code STUB}，与历史写入 {@code payment_notification_inbox.channel='STUB'} 一致 */
    private static final String STUB_CHANNEL = "STUB";

    /**
     * T13-22：商户号。Spring 装配时由 {@code @Value} 注入配置值（默认 {@code stub-merchant}）；
     * 手动 {@code new StubPaymentPortAdapter()}（如 {@code PaymentP8AContractTest}）不走 Spring 容器，
     * {@code @Value} 不生效，字段保留初始值 {@code "stub-merchant"}，避免 {@code payment_transaction.merchant_id}
     * 写入 null 触发 NOT NULL 约束。两条路径最终值一致。
     */
    @Value("${zhongshu.commerce.payment.merchant-id:stub-merchant}")
    private String merchantId = "stub-merchant";

    /** 查单剧本：orderNo → state（SUCCEEDED/PENDING/...）；测试与运维注入 */
    private final Map<String, String> queryScript = new ConcurrentHashMap<>();

    /** 退款剧本：orderNo → state */
    private final Map<String, String> refundScript = new ConcurrentHashMap<>();

    /** 查单金额剧本：orderNo → 渠道实付（分） */
    private final Map<String, Long> amountScript = new ConcurrentHashMap<>();

    /** T13-22：openid 透传记录：orderNo → openid；仅用于合同测试断言 openid 已从会话上下文到达 Adapter */
    private final Map<String, String> openidByOrder = new ConcurrentHashMap<>();

    public void scriptAmount(String orderNo, Long amountCents) {
        amountScript.put(orderNo, amountCents);
    }

    public void scriptQuery(String orderNo, String state) {
        queryScript.put(orderNo, state);
    }

    public void scriptRefund(String orderNo, String state) {
        refundScript.put(orderNo, state);
    }

    /** T13-22：测试断言 openid 透传；返回指定 orderNo 最后一次 createPrepay 的 openid，未记录时 null */
    public String openidOf(String orderNo) {
        return openidByOrder.get(orderNo);
    }

    public void clearScripts() {
        queryScript.clear();
        refundScript.clear();
        amountScript.clear();
        openidByOrder.clear();
    }

    @Override
    public String channel() {
        return STUB_CHANNEL;
    }

    @Override
    public String merchantId() {
        return merchantId;
    }

    @Override
    public PrepayResult createPrepay(String orderNo, long amountCents, String description, String openid) {
        // Stub 不消费 openid（无真实微信端点），仅记录供合同测试断言「openid 已从会话上下文透传到 Adapter」。
        // 真实 WechatPaymentAdapter（T13-23）将 openid 传入 WxPayUnifiedOrderV3Request.setPayerOpenid()。
        if (openid != null) {
            openidByOrder.put(orderNo, openid);
        }
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

    /**
     * T13-28：Stub 退款通知解析。约定 JSON：
     * {@code {eventId, orderNo, channelRefundId, state, refundAmountCents, refundedAtEpochSecond}}。
     *
     * <p>{@code state} 缺失或空时归为 {@code UNKNOWN}（与真实渠道未知状态映射一致）；
     * {@code refundedAtEpochSecond} 缺失或 0 时 {@code refundedAt} 为 null，供上层走 PAID_AT_MISSING 类审计分支。
     */
    @Override
    public NormalizedRefundNotification parseRefundNotification(Map<String, String> headers, byte[] body) {
        try {
            JsonNode node = JSON.readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            String state = node.path("state").asText("");
            long epochSecond = node.path("refundedAtEpochSecond").asLong(0L);
            return new NormalizedRefundNotification(
                    node.path("eventId").asText(),
                    node.path("orderNo").asText(),
                    node.path("channelRefundId").asText(),
                    state.isBlank() ? "UNKNOWN" : state,
                    node.path("refundAmountCents").asLong(),
                    epochSecond == 0L ? null : Instant.ofEpochSecond(epochSecond));
        } catch (Exception e) {
            throw new IllegalStateException("Stub 退款通知体解析失败", e);
        }
    }

}
