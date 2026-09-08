package cn.iocoder.yudao.module.infra.zhongshu.event;

/**
 * 可靠事件端口（架构 §6.10 / P1C）
 *
 * 合同：
 * 1. append 必须在与业务写同一数据库事务内调用——事务回滚则事件一并回滚，提交则事件至少一次投递；
 * 2. 业务模块只经本端口写事件，禁止直接读写 outbox_event 表；
 * 3. 消费方必须幂等：dispatcher 语义为 at-least-once。
 */
public interface ReliableEventPort {

    /**
     * 在当前事务内追加一条待投递事件，返回事件 ID
     */
    long append(OutboxEventMessage message);

}
