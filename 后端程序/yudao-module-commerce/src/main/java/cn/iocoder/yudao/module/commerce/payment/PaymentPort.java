package cn.iocoder.yudao.module.commerce.payment;

import lombok.Value;

/**
 * 通道无关支付端口（架构 §6.6 / T13-22 定型；B4 real 由 WechatPaymentAdapter 实现）
 *
 * 合同：通知入口先持久化 Inbox 再快速应答；支付事实与权益到账分属独立事务；
 * 主动查单兜底通知丢失；P0 只支持整单全额退款。
 *
 * <p>T13-22 新增 {@link #channel()} / {@link #merchantId()}：把「渠道标识 + 商户号」的所有权
 * 收敛到 Adapter 内部，业务侧（{@link RechargePaymentService}）不再硬编码 {@code 'STUB'} /
 * {@code 'stub-merchant'}；写入 {@code payment_notification_inbox.channel} /
 * {@code payment_transaction.channel} / {@code payment_transaction.merchant_id} 三列时统一取自本接口，
 * 使 stub → real 切换零业务改动（分派表 §2 T13-22 硬约束）。
 *
 * <p>{@link #createPrepay(String, long, String, String)} 新增 {@code openid} 参数：微信 JSAPI 支付
 * 必需用户 openid；来源必须是 {@code IdentitySessionPort.SessionContext#openid()}（会话上下文），
 * 禁止从客户端请求参数直接取（防止 openid 与 userId 不匹配的越权支付）。
 */
public interface PaymentPort {

    /**
     * 渠道标识（写入 {@code payment_notification_inbox.channel} / {@code payment_transaction.channel}）。
     * Stub 返回 {@code "STUB"}；WechatPaymentAdapter 返回 {@code "WECHAT"}。
     */
    String channel();

    /**
     * 商户号（写入 {@code payment_transaction.merchant_id}）。
     * Stub 返回配置项 {@code zhongshu.commerce.payment.merchant-id}（默认 {@code "stub-merchant"}）；
     * WechatPaymentAdapter 返回 {@code zhongshu.commerce.payment.wechat.merchant-id}（B4 已登记为 REAL_MODE_REQUIREMENTS 必需键）。
     */
    String merchantId();

    /**
     * 预下单：返回拉起支付所需参数。
     *
     * @param orderNo     本地商户订单号（幂等键，同 orderNo 稳定返回同 prepayId）
     * @param amountCents 订单金额（分）
     * @param description 商品描述
     * @param openid      微信小程序会话 openid（JSAPI 支付必需；来源 {@code IdentitySessionPort}，禁止客户端传入）
     */
    PrepayResult createPrepay(String orderNo, long amountCents, String description, String openid);

    /** 校验通知真实性（Stub 直通；真实渠道做验签/解密） */
    boolean verifyNotification(java.util.Map<String, String> headers, byte[] body);

    /** 从通知体提取规范化事件（Stub 约定 JSON：{eventId, orderNo, transactionId, amountCents, paidAt}） */
    NormalizedNotification parseNotification(java.util.Map<String, String> headers, byte[] body);

    /** 主动查单：返回渠道视角的支付状态（Stub 按注入的剧本返回） */
    ChannelQueryResult queryOrder(String orderNo);

    /** 渠道退款（整单全额） */
    ChannelRefundResult requestRefund(String orderNo, String channelRefundId, long amountCents);

    /** 渠道退款单查询（审查 H1：UNKNOWN 收口依赖） */
    ChannelRefundResult queryRefund(String orderNo, String channelRefundId);

    /**
     * T13-28：从退款通知体提取规范化事件。
     *
     * <p>验签复用 {@link #verifyNotification}（微信 v3 支付/退款通知共用同一套签名头与 APIv3 密钥）；
     * 本方法负责解密与字段映射。Stub 按约定 JSON 解析。
     */
    NormalizedRefundNotification parseRefundNotification(java.util.Map<String, String> headers, byte[] body);

    @Value
    class PrepayResult {
        String prepayId;
        java.util.Map<String, String> callParams;
    }

    @Value
    class NormalizedNotification {
        String eventId;
        String orderNo;
        String channelTransactionId;
        long amountCents;
        java.time.Instant paidAt;
    }

    @Value
    class ChannelQueryResult {
        String state; // SUCCEEDED / PENDING / CLOSED / FAILED / UNKNOWN
        String channelTransactionId;
        /** 渠道实付金额（分）；渠道未提供时为 null，调用方回退声明值并记审计 */
        Long amountCents;
    }

    @Value
    class ChannelRefundResult {
        String state; // SUCCEEDED / PROCESSING / UNKNOWN / FAILED
    }

    /**
     * T13-28：退款通知规范化事件。
     *
     * <p>{@code state} 取值同 {@link ChannelRefundResult}；调用方必须遵守分派表 §8 红线 5：
     * {@code PROCESSING}/{@code UNKNOWN} 既不视为成功也不视为失败，仅保持
     * {@code refund_order.channel_state} 与预留点数不变，交由查单收口。
     */
    @Value
    class NormalizedRefundNotification {
        /** 渠道通知全局唯一 ID（{@code payment_notification_inbox} UK 幂等键） */
        String eventId;
        /** 本地商户订单号（微信 {@code out_trade_no}） */
        String orderNo;
        /** 本地退款单号（微信 {@code out_refund_no}，形如 {@code refund-{refundId}}） */
        String channelRefundId;
        /** SUCCEEDED / PROCESSING / UNKNOWN / FAILED */
        String state;
        /** 本次退款金额（分）；用于与 {@code refund_order.amount_cents} 做一致性校验 */
        long refundAmountCents;
        /** 退款成功时间；渠道未提供时为 null */
        java.time.Instant refundedAt;
    }

}
