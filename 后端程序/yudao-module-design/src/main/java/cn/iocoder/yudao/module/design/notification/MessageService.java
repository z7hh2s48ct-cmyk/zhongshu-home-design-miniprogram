package cn.iocoder.yudao.module.design.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 站内消息（架构 §6.10）：我的/消息中心入口；按 Outbox 事件和收件人去重。
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
        var target = target(event);
        jdbcTemplate.update(
                "INSERT INTO user_message (id, user_id, message_type, title, content, biz_type, biz_id, source_event_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_event_id, user_id) WHERE source_event_id IS NOT NULL DO NOTHING",
                IdWorker.getId(), target.userId(), event.getEventType(), title(event.getEventType()),
                "点击查看最新业务状态", target.bizType(), target.bizId(), event.getEventId());
    }

    private record Target(long userId, String bizType, String bizId) { }

    private Target target(cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord event) {
        if ("SUBMISSION_PUBLISHED".equals(event.getEventType())) {
            try {
                var rows = jdbcTemplate.query("SELECT user_id,id FROM case_submission WHERE id=? AND deleted=FALSE",
                        (rs,i) -> new Target(rs.getLong("user_id"), "case_submission", rs.getString("id")), Long.parseLong(event.getBizId()));
                if (!rows.isEmpty()) return rows.get(0);
            } catch (NumberFormatException ignored) { }
            throw new IllegalStateException("发布事件无法解析投稿人");
        }
        // Legacy refund events omitted userId. Resolve recipient and navigation from authoritative rows.
        if ("ORDER_REFUND_REVERSED".equals(event.getEventType())) {
            try {
                long refundId = Long.parseLong(event.getBizId());
                var rows = jdbcTemplate.query("SELECT o.user_id, o.id FROM refund_order r JOIN recharge_order o ON o.id=r.order_id "
                        + "WHERE r.id=? AND r.deleted=FALSE AND o.deleted=FALSE", (rs,i) -> new Target(rs.getLong("user_id"), "recharge_order", rs.getString("id")), refundId);
                if (!rows.isEmpty()) return rows.get(0);
            } catch (NumberFormatException ignored) { }
            throw new IllegalStateException("退款事件无法解析原订单");
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getPayload());
            var value = node.hasNonNull("userId") ? node.get("userId") : node.get("ownerUserId");
            if (value != null && value.asText().matches("[1-9][0-9]{0,18}"))
                return new Target(Long.parseLong(value.asText()), event.getBizType(), event.getBizId());
        } catch (Exception ignored) {
            // 落入下方统一异常
        }
        throw new IllegalStateException("事件 payload 缺少 userId: " + event.getEventType());
    }

    private String title(String type) {
        return switch (type) {
            case "AI_JOB_SETTLED" -> "设计方案生成完成";
            case "AI_JOB_CANCELLED" -> "设计任务已取消";
            case "AI_JOB_FAILED" -> "设计任务未完成，请查看处理结果";
            case "SUBMISSION_REVIEWED" -> "投稿审核结果已更新";
            case "SUBMISSION_PUBLISHED" -> "您的作品已发布";
            case "ORDER_CREDITED" -> "充值设计点已到账";
            case "ORDER_REFUND_REVERSED" -> "退款已完成";
            case "PAYMENT_ALERT" -> "充值订单需要关注";
            case "RIGHTS_EXPIRED" -> "素材授权状态已变化";
            default -> "业务状态已更新";
        };
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
        Integer owned = jdbcTemplate.queryForObject("SELECT count(*) FROM user_message WHERE id=? AND user_id=? AND deleted=FALSE",
                Integer.class, messageId, userId);
        if (owned == null || owned == 0) throw new org.springframework.security.access.AccessDeniedException("消息不存在或无权访问");
        int inserted = jdbcTemplate.update(
                "INSERT INTO message_receipt (id, user_id, message_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, message_id) DO NOTHING",
                IdWorker.getId(), userId, messageId);
        return inserted == 1;
    }

    public List<Map<String, Object>> list(long userId, int limit) {
        return listPage(userId, null, limit).list();
    }

    public record MessagePage(List<Map<String, Object>> list, String nextCursor) { }

    public MessagePage listPage(long userId, String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        long before = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try { before = Long.parseLong(cursor); if (before <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "消息游标无效"); }
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT m.id, m.message_type, m.title, m.content, m.biz_type, m.biz_id, m.create_time, "
                        + "(r.id IS NOT NULL) AS read FROM user_message m "
                        + "LEFT JOIN message_receipt r ON r.message_id = m.id AND r.user_id = m.user_id "
                        + "WHERE m.user_id = ? AND m.deleted = FALSE AND m.id < ? ORDER BY m.id DESC LIMIT ?",
                userId, before, size + 1);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        return new MessagePage(List.copyOf(page), more ? String.valueOf(page.get(page.size() - 1).get("id")) : null);
    }

}
