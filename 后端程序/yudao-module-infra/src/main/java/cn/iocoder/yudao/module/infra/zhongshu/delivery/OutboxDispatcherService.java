package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * Outbox dispatcher：租约领取 + at-least-once 投递。
 *
 * 合同：
 * 1. 领取使用 FOR UPDATE SKIP LOCKED + 事件级租约（claim_expires_at），多实例不重复领取，
 *    崩溃实例的租约到期后事件可被其他实例重领；
 * 2. Sink 投递在事务外：成功标记 DISPATCHED，失败退避重试，超过 5 次进入 DEAD 人工处置；
 * 3. Sink 必须幂等；无 Sink 声明支持的事件按失败退避，不阻塞其他事件。
 */
@Slf4j
@Service
public class OutboxDispatcherService {

    private static final int MAX_RETRY_BEFORE_DEAD = 5;

    private final JdbcTemplate jdbcTemplate;

    private final List<OutboxEventSink> sinks;

    public OutboxDispatcherService(DataSource dataSource, List<OutboxEventSink> sinks) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.sinks = sinks == null ? List.of() : sinks;
    }

    /** 登记并心跳 dispatcher 实例租约（可观测；事件级抢占由 outbox_event.claim_* 承担） */
    public void heartbeat(String dispatcherName, String instanceId, long leaseSeconds) {
        jdbcTemplate.update(
                "INSERT INTO dispatcher_lease (dispatcher_name, instance_id, lease_expires_at) "
                        + "VALUES (?, ?, now() + (? * interval '1 second')) "
                        + "ON CONFLICT (dispatcher_name, instance_id) "
                        + "DO UPDATE SET lease_expires_at = now() + (? * interval '1 second'), heartbeat_at = now()",
                dispatcherName, instanceId, leaseSeconds, leaseSeconds);
    }

    /**
     * 领取一批事件：FOR UPDATE SKIP LOCKED + 过期租约回收
     */
    @SuppressWarnings("DataFlowIssue")
    public List<OutboxEventRecord> claim(String dispatcherName, String instanceId,
                                         long leaseSeconds, int maxEvents) {
        return jdbcTemplate.query(
                "UPDATE outbox_event SET claimed_by = ?, claim_expires_at = now() + (? * interval '1 second') "
                        + "WHERE id IN ("
                        + "  SELECT id FROM outbox_event "
                        + "  WHERE status = 'PENDING' AND next_retry_at <= now() "
                        + "    AND (claim_expires_at IS NULL OR claim_expires_at < now()) "
                        + "  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"
                        + ") RETURNING id, event_type, biz_type, biz_id, payload::text, claimed_by",
                (rs, i) -> new OutboxEventRecord(
                        rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("biz_type"), rs.getString("biz_id"),
                        rs.getString("payload"), rs.getString("claimed_by")),
                instanceId + "@" + dispatcherName, leaseSeconds, maxEvents);
    }

    public boolean complete(long eventId, String claimedBy) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'DISPATCHED', dispatched_at = now() "
                        + "WHERE id = ? AND status = 'PENDING' AND claimed_by = ?",
                eventId, claimedBy) == 1;
    }

    public boolean fail(long eventId, String claimedBy, String error, long backoffSeconds) {
        return jdbcTemplate.update(
                "UPDATE outbox_event SET retry_count = retry_count + 1, last_error = ?, "
                        + "next_retry_at = now() + (? * interval '1 second'), "
                        + "status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE status END, "
                        + "claimed_by = NULL, claim_expires_at = NULL "
                        + "WHERE id = ? AND status = 'PENDING' AND claimed_by = ?",
                error, backoffSeconds, MAX_RETRY_BEFORE_DEAD, eventId, claimedBy) == 1;
    }

    /**
     * 执行一轮投递：领取 + 分发给第一个声明支持的 Sink。
     * 必须在无事务上下文中调用（领取/确认各自独立短事务）；若被误包进业务事务，
     * 租约与确认会延迟提交并放大重复投递（仍属 at-least-once，但不可取）。
     *
     * @return 本轮领取的事件数
     */
    public int dispatchOnce(String dispatcherName, String instanceId, long leaseSeconds,
                            int maxEvents, long backoffSeconds) {
        String claimedBy = instanceId + "@" + dispatcherName;
        List<OutboxEventRecord> events = claim(dispatcherName, instanceId, leaseSeconds, maxEvents);
        for (OutboxEventRecord event : events) {
            OutboxEventSink sink = sinks.stream().filter(s -> s.supports(event.getEventType())).findFirst().orElse(null);
            if (sink == null) {
                // 「无订阅者」不是投递失败：该事件类型没有任何 Sink 声明支持时按已处理收敛，
                // 否则每次发布无消费方的事件（如 AI_JOB_CREATED）都会重试至 DEAD 污染 businessBacklog。
                log.info("[dispatchOnce][事件 {}({}) 无 Sink 订阅，按已处理收敛]", event.getEventId(), event.getEventType());
                complete(event.getEventId(), claimedBy);
                continue;
            }
            try {
                sink.deliver(event);
                complete(event.getEventId(), claimedBy);
            } catch (Exception e) {
                log.warn("[dispatchOnce][事件 {} 投递失败: {}]", event.getEventId(), e.getMessage());
                fail(event.getEventId(), claimedBy, e.getMessage(), backoffSeconds);
            }
        }
        return events.size();
    }

}
