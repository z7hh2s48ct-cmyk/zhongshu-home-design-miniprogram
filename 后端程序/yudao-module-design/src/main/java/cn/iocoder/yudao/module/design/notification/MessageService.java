package cn.iocoder.yudao.module.design.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 站内消息（架构 §6.10）：顶部铃铛唯一入口；Outbox Sink 落库，重复投递幂等（biz 去重交由 Outbox 幂等链）
 */
@Service
public class MessageService implements cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventSink {

    private final JdbcTemplate jdbcTemplate;

    public MessageService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public boolean supports(String eventType) {
        // 任务完成/取消、审核结论、到账、发布——站内信只收白名单事件
        // 审查 H7：补齐合同 §6.10 最小事件集（任务失败/退款/支付到账异常/授权失效）
        return List.of("AI_JOB_SETTLED", "AI_JOB_CANCELLED", "SUBMISSION_REVIEWED",
                "ORDER_CREDITED", "SUBMISSION_PUBLISHED",
                "AI_JOB_FAILED", "ORDER_REFUND_REVERSED", "PAYMENT_ALERT", "RIGHTS_EXPIRED")
                .contains(eventType);
    }

    /** Outbox Sink：at-least-once 投递；payload 必须携带 userId（或 ownerUserId） */
    @Override
    public void deliver(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        long userId = extractUserId(event);
        jdbcTemplate.update(
                "INSERT INTO user_message (id, user_id, message_type, title, biz_type, biz_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                IdWorker.getId(), userId,
                event.getEventType(), "通知：" + event.getEventType(),
                event.getBizType(), event.getBizId());
    }

    private long extractUserId(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getPayload());
            if (node.hasNonNull("userId")) {
                return node.get("userId").asLong();
            }
            if (node.hasNonNull("ownerUserId")) {
                return node.get("ownerUserId").asLong();
            }
        } catch (Exception ignored) {
            // 落入下方统一异常
        }
        throw new IllegalStateException("事件 payload 缺少 userId: " + event.getEventType());
    }

    public long unreadCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_message m WHERE m.user_id = ? AND m.deleted = FALSE "
                        + "AND NOT EXISTS (SELECT 1 FROM message_receipt r WHERE r.user_id = m.user_id "
                        + "AND r.message_id = m.id AND r.deleted = FALSE)",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    /** 已读回执幂等 */
    public boolean markRead(long userId, long messageId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO message_receipt (id, user_id, message_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, message_id) DO NOTHING",
                IdWorker.getId(), userId, messageId);
        return inserted == 1;
    }

    public List<Map<String, Object>> list(long userId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT m.id, m.message_type, m.title, m.content, m.biz_type, m.biz_id, m.create_time, "
                        + "(r.id IS NOT NULL) AS read FROM user_message m "
                        + "LEFT JOIN message_receipt r ON r.message_id = m.id AND r.user_id = m.user_id "
                        + "WHERE m.user_id = ? AND m.deleted = FALSE ORDER BY m.id DESC LIMIT ?",
                userId, Math.min(limit, 100));
    }

}
