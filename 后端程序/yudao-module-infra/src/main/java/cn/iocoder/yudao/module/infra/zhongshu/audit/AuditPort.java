package cn.iocoder.yudao.module.infra.zhongshu.audit;

/**
 * 审计端口（架构 §6.10）
 *
 * 合同：审计写入不得携带敏感明文（授权码明文、签名 URL、session_key 等）；
 * 审计失败不阻塞业务主流程的实现允许在实现层选择，但接口语义是同步落库。
 */
public interface AuditPort {

    /**
     * 落一条审计事件，返回事件 ID；actorType 与 result 为必填（null 会中止当前事务）
     */
    long record(AuditEventMessage message);

}
