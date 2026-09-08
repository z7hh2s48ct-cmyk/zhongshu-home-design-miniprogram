package cn.iocoder.yudao.module.infra.zhongshu.event;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Outbox 事件消息（业务事务内追加，随业务事务一起提交/回滚）
 */
@Value
@Builder
public class OutboxEventMessage {

    /** 事件类型，如 USER_MESSAGE_SEND、AI_JOB_SETTLED */
    String eventType;

    /** 关联业务类型，如 design_project、recharge_order */
    String bizType;

    /** 关联业务编号 */
    String bizId;

    /** 事件载荷（消费者按事件类型解释） */
    Map<String, Object> payload;

    /** 投递附带头（如订阅消息模板参数提示），可为 null */
    Map<String, String> headers;

    /** 租户（D-09 冻结前默认 0） */
    Long tenantId;

}
