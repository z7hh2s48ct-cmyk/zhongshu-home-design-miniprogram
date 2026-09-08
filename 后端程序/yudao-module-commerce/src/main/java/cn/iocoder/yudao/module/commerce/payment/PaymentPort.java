package cn.iocoder.yudao.module.commerce.payment;

import lombok.Value;

/**
 * 通道无关支付端口（架构 §6.6 / D-10 前 Stub 实现）
 *
 * 合同：通知入口先持久化 Inbox 再快速应答；支付事实与权益到账分属独立事务；
 * 主动查单兜底通知丢失；P0 只支持整单全额退款。
 */
public interface PaymentPort {

    /** 预下单：返回拉起支付所需参数（Stub 返回模拟 prepayId） */
    PrepayResult createPrepay(String orderNo, long amountCents, String description);

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

}
