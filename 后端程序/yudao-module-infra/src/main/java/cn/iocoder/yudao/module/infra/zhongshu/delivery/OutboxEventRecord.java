package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.Value;

/**
 * dispatcher 领取到的事件（at-least-once 投递给 Sink）
 */
@Value
public class OutboxEventRecord {

    long eventId;

    String eventType;

    String bizType;

    String bizId;

    /** JSONB 文本 */
    String payload;

    String claimedBy;

}
