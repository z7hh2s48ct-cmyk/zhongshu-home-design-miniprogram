package cn.iocoder.yudao.module.infra.zhongshu.delivery;

/**
 * Outbox 事件 Sink：每种投递形态一个实现（站内消息、微信订阅消息、AI 结果通知等）。
 *
 * 合同：Sink 必须幂等（dispatcher 是 at-least-once 语义）；
 * Sink 只做投递动作，禁止回写业务事实。
 */
public interface OutboxEventSink {

    /**
     * @return 是否声明消费该事件类型；false 表示本 Sink 不处理（由其他 Sink 处理）
     */
    boolean supports(String eventType);

    void deliver(OutboxEventRecord event) throws Exception;

}
