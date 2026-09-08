package cn.iocoder.yudao.module.infra.zhongshu.audit;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 审计事件消息
 */
@Value
@Builder
public class AuditEventMessage {

    public enum ActorType {USER, ADMIN, SYSTEM, WORKER}

    public enum AuditResult {SUCCESS, FAILURE, DENIED}

    /** 事件类型，如 ACCESS_CODE_ISSUED、POINTS_MANUAL_ADJUSTED */
    String eventType;

    ActorType actorType;

    /** 操作者标识（用户 ID / 管理员 ID / worker 实例名） */
    String actorId;

    /** 动作，如 CREATE、DISABLE、CONSUME、REVOKE */
    String action;

    String bizType;

    String bizId;

    AuditResult result;

    /** 审计明细（不含敏感明文：掩码、Hash、过滤条件等） */
    Map<String, Object> detail;

    Long tenantId;

}
