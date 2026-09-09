package cn.iocoder.yudao.module.commerce.payment;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.PAYMENT_ORDER_STATE_CONFLICT;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.REFUND_POINTS_ALREADY_USED;

/**
 * 充值订单与到账履约（架构 §6.6 / §8.3、蓝图 P8A）
 *
 * 合同：
 * - 创建订单冻结方案快照（改价不影响历史订单）；幂等键 user_id + Idempotency-Key；
 * - 通知入口先持久化唯一 Inbox（(channel,event_id)）再快速应答；支付事实与权益到账分属独立事务；
 *   到账事务原子写唯一 recharge_credit + 基础/赠送流水 + 余额 + Outbox；
 * - 主动查单兜底通知丢失（同幂等链补偿）；
 * - P0 仅整单全额退款：受理事务锁订单与账户，校验到账后无扣减且可用点足额，全额预留基础/赠送点；
 *   渠道调用在事务外——成功转冲正（总余额减少+两类冲正流水）、失败释放预留、未知保持预留并查单。
 */
@Slf4j
@Service
public class RechargePaymentService {

    public record OrderSnapshot(long orderId, String orderNo, long userId, long amountCents,
                                long basePoints, long bonusPoints,
                                String paymentState, String fulfillmentState) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final PaymentPort paymentPort;

    private final PointLedgerPort ledgerPort;

    private final PointAccountService pointAccountService;

    private final ReliableEventPort reliableEventPort;

    public RechargePaymentService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  PaymentPort paymentPort, PointLedgerPort ledgerPort,
                                  PointAccountService pointAccountService, ReliableEventPort reliableEventPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.paymentPort = paymentPort;
        this.ledgerPort = ledgerPort;
        this.pointAccountService = pointAccountService;
        this.reliableEventPort = reliableEventPort;
    }

    // ========== 方案 ==========

    public long createPlan(String name, long amountCents, long basePoints, long bonusPoints,
                           boolean recommended, int sort) {
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO recharge_plan (id, name, amount_cents, base_points, bonus_points, recommended, sort) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, name, amountCents, basePoints, bonusPoints, recommended, sort);
        return id;
    }

    public List<Map<String, Object>> listEnabledPlans() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, amount_cents, base_points, bonus_points, recommended, sort "
                        + "FROM recharge_plan WHERE enabled = TRUE AND deleted = FALSE ORDER BY sort, id");
    }

    // ========== 创建订单 ==========

    public OrderSnapshot createOrder(long userId, long planId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Long> existing = jdbcTemplate.query(
                    "SELECT id FROM recharge_order WHERE user_id = ? AND idempotency_key = ?",
                    (rs, i) -> rs.getLong("id"), userId, idempotencyKey);
            if (!existing.isEmpty()) {
                return getOrderById(existing.get(0)).orElseThrow();
            }
        }
        // 快照方案（事务内）
        OrderSnapshot created = txTemplate.execute(status -> {
            Map<String, Object> plan;
            try {
                plan = jdbcTemplate.queryForMap(
                        "SELECT id, name, amount_cents, base_points, bonus_points FROM recharge_plan "
                                + "WHERE id = ? AND enabled = TRUE AND deleted = FALSE", planId);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw exception(PAYMENT_ORDER_STATE_CONFLICT); // 方案不存在或已停用
            }
            long orderId = IdWorker.getId();
            String orderNo = "R" + orderId;
            jdbcTemplate.update(
                    "INSERT INTO recharge_order (id, order_no, user_id, plan_id, plan_snapshot, amount_cents, "
                            + "base_points, bonus_points, payment_state, fulfillment_state, idempotency_key) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, 'CREATED', 'NOT_READY', ?)",
                    orderId, orderNo, userId, planId,
                    planJson(plan), ((Number) plan.get("amount_cents")).longValue(),
                    ((Number) plan.get("base_points")).longValue(),
                    ((Number) plan.get("bonus_points")).longValue(),
                    idempotencyKey);
            return new OrderSnapshot(orderId, orderNo, userId,
                    ((Number) plan.get("amount_cents")).longValue(),
                    ((Number) plan.get("base_points")).longValue(),
                    ((Number) plan.get("bonus_points")).longValue(), "CREATED", "NOT_READY");
        });

        // 预下单在事务外（合同：事务内不得等待渠道）
        var prepay = paymentPort.createPrepay(created.orderNo(), created.amountCents(), "充值");
        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE recharge_order SET payment_state = 'PENDING', update_time = now() "
                            + "WHERE id = ? AND payment_state = 'CREATED'", created.orderId());
            return null;
        });
        return new OrderSnapshot(created.orderId(), created.orderNo(), created.userId(),
                created.amountCents(), created.basePoints(), created.bonusPoints(), "PENDING", "NOT_READY");
    }

    private String planJson(Map<String, Object> plan) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<OrderSnapshot> getOrderById(long orderId) {
        List<OrderSnapshot> rows = jdbcTemplate.query(
                "SELECT id, order_no, user_id, amount_cents, base_points, bonus_points, "
                        + "payment_state, fulfillment_state FROM recharge_order WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new OrderSnapshot(rs.getLong("id"), rs.getString("order_no"),
                        rs.getLong("user_id"), rs.getLong("amount_cents"), rs.getLong("base_points"),
                        rs.getLong("bonus_points"), rs.getString("payment_state"),
                        rs.getString("fulfillment_state")),
                orderId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ========== 小程序读模型（页面 17/18 与「我的 → 充值记录」）==========

    /**
     * 订单读模型：在 OrderSnapshot 基础上补 planId 与真实到账时间。
     * paidAt 取自 payment_transaction 的渠道确认时间，而非订单行的 update_time——
     * 后者会被任何后续状态推进覆盖，不能作为支付时点展示。
     */
    public record OrderDetail(long orderId, String orderNo, long userId, long planId, long amountCents,
                              long basePoints, long bonusPoints, String paymentState,
                              String fulfillmentState, Instant createdAt, Instant paidAt, RefundSummary refund) {
    }

    public record RefundSummary(String refundId, String channelState, String pointReversalState,
                                long amountCents, String reason) { }

    private static final String ORDER_DETAIL_COLUMNS =
            "o.id, o.order_no, o.user_id, o.plan_id, o.amount_cents, o.base_points, o.bonus_points, "
                    + "o.payment_state, o.fulfillment_state, o.create_time, t.paid_at, "
                    + "r.id AS refund_id, r.channel_state, r.point_reversal_state, r.amount_cents AS refund_amount_cents, r.reason "
                    + "FROM recharge_order o "
                    + "LEFT JOIN payment_transaction t ON t.order_no = o.order_no AND t.deleted = FALSE "
                    + "LEFT JOIN LATERAL (SELECT id, channel_state, point_reversal_state, amount_cents, reason "
                    + "FROM refund_order WHERE order_id = o.id AND deleted = FALSE ORDER BY id DESC LIMIT 1) r ON TRUE ";

    public Optional<OrderDetail> getOrderDetail(long userId, long orderId) {
        List<OrderDetail> rows = jdbcTemplate.query(
                "SELECT " + ORDER_DETAIL_COLUMNS + "WHERE o.id = ? AND o.user_id = ? AND o.deleted = FALSE",
                this::mapOrderDetail, orderId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countOrders(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM recharge_order WHERE user_id = ? AND deleted = FALSE",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    public List<OrderDetail> listOrders(long userId, int pageNo, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) Math.max(pageNo - 1, 0) * size;
        return jdbcTemplate.query(
                "SELECT " + ORDER_DETAIL_COLUMNS + "WHERE o.user_id = ? AND o.deleted = FALSE "
                        + "ORDER BY o.id DESC LIMIT ? OFFSET ?",
                this::mapOrderDetail, userId, size, offset);
    }

    private OrderDetail mapOrderDetail(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderDetail(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                rs.getLong("plan_id"), rs.getLong("amount_cents"), rs.getLong("base_points"),
                rs.getLong("bonus_points"), rs.getString("payment_state"),
                rs.getString("fulfillment_state"),
                rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toInstant(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
                rs.getObject("refund_id") == null ? null : new RefundSummary(rs.getString("refund_id"),
                        rs.getString("channel_state"), rs.getString("point_reversal_state"),
                        rs.getLong("refund_amount_cents"), rs.getString("reason")));
    }

    // ========== 后台管理读模型（管理端页面 09~11）==========

    /** 方案全量分页（含停用）；历史订单引用版本快照，改价/停用不影响已建订单 */
    public long countPlans(Boolean enabled) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (enabled != null) {
            where.append(" AND enabled = ?");
            args.add(enabled);
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM recharge_plan" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public java.util.List<java.util.Map<String, Object>> pagePlans(Boolean enabled, int pageNo, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (enabled != null) {
            where.append(" AND enabled = ?");
            args.add(enabled);
        }
        args.add(Math.min(Math.max(pageSize, 1), 100));
        args.add((long) Math.max(pageNo - 1, 0) * Math.min(Math.max(pageSize, 1), 100));
        return jdbcTemplate.queryForList(
                "SELECT id, name, amount_cents, base_points, bonus_points, recommended, sort, enabled, "
                        + "version, create_time FROM recharge_plan" + where + " ORDER BY sort, id LIMIT ? OFFSET ?",
                args.toArray());
    }

    /** 编辑方案：version 乐观锁，过期编辑返回 STATE_VERSION_CONFLICT；只更新界面实际暴露的字段 */
    public long updatePlan(long planId, String name, Long amountCents, Long basePoints, Long bonusPoints,
                           Boolean recommended, Integer sort, Boolean enabled, long expectedVersion) {
        return txTemplate.execute(status -> {
            java.util.Map<String, Object> current;
            try {
                current = jdbcTemplate.queryForMap(
                        "SELECT version FROM recharge_plan WHERE id = ? AND deleted = FALSE FOR UPDATE", planId);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN);
            }
            long version = ((Number) current.get("version")).longValue();
            if (version != expectedVersion) {
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
            }
            StringBuilder sql = new StringBuilder("UPDATE recharge_plan SET version = version + 1, update_time = now()");
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (name != null) {
                sql.append(", name = ?");
                args.add(name);
            }
            if (amountCents != null) {
                sql.append(", amount_cents = ?");
                args.add(amountCents);
            }
            if (basePoints != null) {
                sql.append(", base_points = ?");
                args.add(basePoints);
            }
            if (bonusPoints != null) {
                sql.append(", bonus_points = ?");
                args.add(bonusPoints);
            }
            if (recommended != null) {
                sql.append(", recommended = ?");
                args.add(recommended);
            }
            if (sort != null) {
                sql.append(", sort = ?");
                args.add(sort);
            }
            if (enabled != null) {
                sql.append(", enabled = ?");
                args.add(enabled);
            }
            sql.append(" WHERE id = ? AND version = ?");
            args.add(planId);
            args.add(expectedVersion);
            return (long) jdbcTemplate.update(sql.toString(), args.toArray());
        });
    }

    /** 订单后台分页：支付/到账双状态独立过滤；abnormalOnly 圈出需人工跟进的订单 */
    public long countAdminOrders(String paymentState, String fulfillmentState, boolean abnormalOnly) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM recharge_order o " + adminOrderWhere(paymentState, fulfillmentState, abnormalOnly),
                Long.class, adminOrderArgs(paymentState, fulfillmentState, abnormalOnly));
    }

    public java.util.List<java.util.Map<String, Object>> pageAdminOrders(String paymentState,
            String fulfillmentState, boolean abnormalOnly, int pageNo, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 100);
        java.util.List<Object> args = new java.util.ArrayList<>(
                adminOrderArgs(paymentState, fulfillmentState, abnormalOnly));
        args.add(size);
        args.add((long) Math.max(pageNo - 1, 0) * size);
        return jdbcTemplate.queryForList(
                "SELECT o.id, o.order_no, o.user_id, o.plan_id, o.plan_snapshot::text, o.amount_cents, "
                        + "o.base_points, o.bonus_points, o.payment_state, o.fulfillment_state, "
                        + "o.channel_transaction_id, o.create_time, t.paid_at "
                        + "FROM recharge_order o LEFT JOIN payment_transaction t "
                        + "ON t.order_no = o.order_no AND t.deleted = FALSE "
                        + adminOrderWhere(paymentState, fulfillmentState, abnormalOnly)
                        + "ORDER BY o.id DESC LIMIT ? OFFSET ?",
                args.toArray());
    }

    private String adminOrderWhere(String paymentState, String fulfillmentState, boolean abnormalOnly) {
        StringBuilder where = new StringBuilder(" WHERE o.deleted = FALSE");
        if (paymentState != null && !paymentState.isBlank()) {
            where.append(" AND o.payment_state = ?");
        }
        if (fulfillmentState != null && !fulfillmentState.isBlank()) {
            where.append(" AND o.fulfillment_state = ?");
        }
        if (abnormalOnly) {
            // 异常 = 已付款未到账、到账失败、支付未知——对账页面优先处理的队列
            where.append(" AND ((o.payment_state IN ('SUCCEEDED','UNKNOWN') "
                    + "AND o.fulfillment_state NOT IN ('CREDITED')) OR o.fulfillment_state = 'FAILED')");
        }
        return where.toString();
    }

    private java.util.List<Object> adminOrderArgs(String paymentState, String fulfillmentState,
                                                  boolean abnormalOnly) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (paymentState != null && !paymentState.isBlank()) {
            args.add(paymentState);
        }
        if (fulfillmentState != null && !fulfillmentState.isBlank()) {
            args.add(fulfillmentState);
        }
        return args;
    }

    public long countRefunds(String channelState) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (channelState != null && !channelState.isBlank()) {
            where.append(" AND channel_state = ?");
            args.add(channelState);
        }
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM refund_order" + where,
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public java.util.List<java.util.Map<String, Object>> pageRefunds(String channelState,
                                                                     int pageNo, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (channelState != null && !channelState.isBlank()) {
            where.append(" AND channel_state = ?");
            args.add(channelState);
        }
        args.add(Math.min(Math.max(pageSize, 1), 100));
        args.add((long) Math.max(pageNo - 1, 0) * Math.min(Math.max(pageSize, 1), 100));
        return jdbcTemplate.queryForList(
                "SELECT id, order_id, refund_request_key, amount_cents, channel_refund_id, channel_state, "
                        + "point_reversal_state, reserved_base, reserved_bonus, operator_id, reason, create_time "
                        + "FROM refund_order" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                args.toArray());
    }

    public java.util.Optional<java.util.Map<String, Object>> getRefund(long refundId) {
        java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, order_id, refund_request_key, amount_cents, channel_refund_id, channel_state, "
                        + "point_reversal_state, reserved_base, reserved_bonus, operator_id, reason, create_time "
                        + "FROM refund_order WHERE id = ? AND deleted = FALSE", refundId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    // ========== 通知入口：先 Inbox 再处理 ==========

    public String handleNotification(java.util.Map<String, String> headers, byte[] body) {
        PaymentPort.NormalizedNotification notification;
        boolean verified = paymentPort.verifyNotification(headers, body);
        if (!verified) {
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }
        notification = paymentPort.parseNotification(headers, body);
        try {
            jdbcTemplate.update(
                    "INSERT INTO payment_notification_inbox (id, channel, event_id, request_headers, body_hash, "
                            + "normalized_payload, verify_status) VALUES (?, 'STUB', ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)",
                    IdWorker.getId(), notification.getEventId(),
                    toJsonOrNull(Map.of("count", headers.size())), sha256Hex(body), toJsonOrNull(Map.of(
                            "orderNo", notification.getOrderNo(),
                            "amountCents", notification.getAmountCents())), "PASSED");
        } catch (DuplicateKeyException e) {
            return "DUPLICATE";
        }
        try {
            boolean paid = processPaymentFact(notification.getOrderNo(), notification.getEventId(),
                    notification.getChannelTransactionId(), notification.getAmountCents(),
                    notification.getPaidAt());
            if (paid) {
                fulfillOrder(notification.getOrderNo());
            }
            markInbox(notification.getEventId(), paid ? "PROCESSED" : "REJECTED", null);
            return paid ? "PROCESSED" : "REJECTED";
        } catch (Exception e) {
            // 处理失败：Inbox 留痕可重投（recoverPendingInbox / recoverHangingOrders 补偿）
            markInbox(notification.getEventId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    private void markInbox(String eventId, String status, String error) {
        jdbcTemplate.update(
                "UPDATE payment_notification_inbox SET process_status = ?, last_error = ?, "
                        + "retry_count = retry_count + 1, update_time = now() WHERE channel = 'STUB' AND event_id = ?",
                status, error, eventId);
    }

    /** 补偿一：重投失败 Inbox（重放事件；同幂等链不重复改变事实） */
    public int recoverPendingInbox() {
        var failed = jdbcTemplate.queryForList(
                "SELECT event_id, normalized_payload FROM payment_notification_inbox "
                        + "WHERE process_status IN ('RECEIVED','FAILED') AND channel = 'STUB' "
                        + "ORDER BY id LIMIT 50");
        int recovered = 0;
        for (var row : failed) {
            String eventId = (String) row.get("event_id");
            String orderNo = jdbcTemplate.queryForObject(
                    "SELECT normalized_payload->>'orderNo' FROM payment_notification_inbox "
                            + "WHERE channel = 'STUB' AND event_id = ?", String.class, eventId);
            try {
                fulfillOrder(orderNo);
                markInbox(eventId, "PROCESSED", null);
                recovered++;
            } catch (Exception e) {
                markInbox(eventId, "FAILED", e.getMessage());
            }
        }
        return recovered;
    }

    /** 补偿二：主动查单收口「已支付未到账」悬挂订单（合同：通知丢失主动查单兜底） */
    public int recoverHangingOrders() {
        var hanging = jdbcTemplate.queryForList(
                "SELECT order_no, amount_cents FROM recharge_order "
                        + "WHERE payment_state IN ('PENDING','UNKNOWN') AND fulfillment_state <> 'CREDITED' "
                        + "AND deleted = FALSE ORDER BY id LIMIT 50");
        int recovered = 0;
        for (var row : hanging) {
            String orderNo = (String) row.get("order_no");
            if (!"REJECTED".equals(reconcile(orderNo))) {
                recovered++;
            }
        }
        return recovered;
    }

    // ========== 支付事实（独立事务） ==========

    /** 校验金额并 CAS 推进支付事实；返回是否发生状态迁移（重复通知返回 false） */
    public boolean processPaymentFact(String orderNo, String eventId, String channelTransactionId,
                                      long amountCents, Instant paidAt) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                    "SELECT id, amount_cents, payment_state FROM recharge_order "
                            + "WHERE order_no = ? AND deleted = FALSE FOR UPDATE", orderNo);
            if (orders.isEmpty()) {
                log.warn("[processPaymentFact][未知订单号 {}]", orderNo);
                return false;
            }
            Map<String, Object> order = orders.get(0);
            long orderId = ((Number) order.get("id")).longValue();
            long declaredAmount = ((Number) order.get("amount_cents")).longValue();
            if (declaredAmount != amountCents) {
                throw exception(PAYMENT_ORDER_STATE_CONFLICT); // 金额不符：拒绝并留 Inbox 供人工
            }
            String current = (String) order.get("payment_state");
            if ("SUCCEEDED".equals(current)) {
                return false;
            }
            if (!List.of("CREATED", "PENDING", "UNKNOWN").contains(current)) {
                throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            }
            jdbcTemplate.update(
                    "UPDATE recharge_order SET payment_state = 'SUCCEEDED', channel_transaction_id = ?, "
                            + "update_time = now() WHERE id = ? AND payment_state IN ('CREATED','PENDING','UNKNOWN')",
                    channelTransactionId, orderId);
            try {
                jdbcTemplate.update(
                        "INSERT INTO payment_transaction (id, channel, merchant_id, channel_transaction_id, "
                                + "order_no, amount_cents, paid_at) VALUES (?, 'STUB', 'stub-merchant', ?, ?, ?, ?)",
                        IdWorker.getId(), channelTransactionId, orderNo, amountCents,
                        java.sql.Timestamp.from(paidAt));
            } catch (DuplicateKeyException e) {
                // 渠道交易已映射过本地订单：幂等
            }
            return true;
        }));
    }

    // ========== 权益到账（独立事务，原子） ==========

    public boolean fulfillOrder(String orderNo) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Map<String, Object> order = jdbcTemplate.queryForMap(
                    "SELECT id, user_id, base_points, bonus_points, payment_state, fulfillment_state "
                            + "FROM recharge_order WHERE order_no = ? AND deleted = FALSE FOR UPDATE", orderNo);
            if (!"SUCCEEDED".equals(order.get("payment_state"))) {
                return false;
            }
            String fulfillment = (String) order.get("fulfillment_state");
            if ("CREDITED".equals(fulfillment)) {
                return true;
            }
            long orderId = ((Number) order.get("id")).longValue();
            long userId = ((Number) order.get("user_id")).longValue();
            long basePoints = ((Number) order.get("base_points")).longValue();
            long bonusPoints = ((Number) order.get("bonus_points")).longValue();

            long baseLedgerId = 0;
            Long bonusLedgerId = null;
            if (basePoints > 0) {
                baseLedgerId = ledgerPort.credit(userId, "RECHARGE_BASE_CREDIT", basePoints,
                        "recharge_order", String.valueOf(orderId),
                        "RECHARGE_CREDIT:" + orderId + ":base", null, null);
            }
            if (bonusPoints > 0) {
                bonusLedgerId = ledgerPort.credit(userId, "RECHARGE_BONUS_CREDIT", bonusPoints,
                        "recharge_order", String.valueOf(orderId),
                        "RECHARGE_CREDIT:" + orderId + ":bonus", null, null);
            }
            jdbcTemplate.update(
                    "INSERT INTO recharge_credit (id, order_id, base_points, bonus_points, "
                            + "ledger_base_id, ledger_bonus_id) VALUES (?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), orderId, basePoints, bonusPoints, baseLedgerId, bonusLedgerId);
            jdbcTemplate.update(
                    "UPDATE recharge_order SET fulfillment_state = 'CREDITED', update_time = now() "
                            + "WHERE id = ? AND fulfillment_state IN ('NOT_READY','PENDING','FAILED')", orderId);
            reliableEventPort.append(OutboxEventMessage.builder()
                    .eventType("ORDER_CREDITED").bizType("recharge_order").bizId(String.valueOf(orderId))
                    .payload(Map.of("orderId", orderId, "userId", userId,
                            "basePoints", basePoints, "bonusPoints", bonusPoints)).build());
            log.info("[fulfillOrder][order={} 到账 基础={} 赠送={}]", orderId, basePoints, bonusPoints);
            return true;
        }));
    }

    /** 主动查单兜底（通知丢失/UNKNOWN 时调用；同幂等链补偿；金额以渠道返回为准） */
    public String reconcile(String orderNo) {
        var query = paymentPort.queryOrder(orderNo);
        if (!"SUCCEEDED".equals(query.getState())) {
            return query.getState();
        }
        long declaredAmount = declaredAmount(orderNo);
        long channelAmount = query.getAmountCents() == null ? declaredAmount : query.getAmountCents();
        if (query.getAmountCents() == null) {
            log.warn("[reconcile][order={} 渠道未返回实付金额，回退声明值 {}]", orderNo, declaredAmount);
        }
        boolean moved = processPaymentFact(orderNo, "reconcile-" + orderNo,
                query.getChannelTransactionId(), channelAmount, Instant.now());
        fulfillOrder(orderNo);
        return moved ? "RECOVERED" : "ALREADY_RECONCILED";
    }

    private long declaredAmount(String orderNo) {
        Long amount = jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM recharge_order WHERE order_no = ?", Long.class, orderNo);
        return amount == null ? -1L : amount;
    }

    // ========== P0 整单退款 ==========

    public Long requestRefund(long orderId, String operator, String reason, String requestKey) {
        // 受理事务：锁订单与账户 → 校验 → 全额预留
        Long refundId = txTemplate.execute(status -> {
            // 幂等重放优先：同 requestKey 直接返回既有退款单（在订单状态守卫之前）
            List<Long> existingRows = jdbcTemplate.query(
                    "SELECT id FROM refund_order WHERE refund_request_key = ?",
                    (rs, i) -> rs.getLong("id"), requestKey);
            if (!existingRows.isEmpty()) {
                return existingRows.get(0);
            }
            Map<String, Object> order = jdbcTemplate.queryForMap(
                    "SELECT id, order_no, user_id, amount_cents, base_points, bonus_points, "
                            + "payment_state, fulfillment_state FROM recharge_order "
                            + "WHERE id = ? AND deleted = FALSE FOR UPDATE", orderId);
            if (!"SUCCEEDED".equals(order.get("payment_state"))
                    || !"CREDITED".equals(order.get("fulfillment_state"))) {
                throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            }
            // P0 整单语义：该订单已存在未失败退款单（含预留中/冲正中/已成功）即拒绝，防渠道重复打款
            Integer priorRefunds = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM refund_order WHERE order_id = ? AND channel_state <> 'FAILED'",
                    Integer.class, orderId);
            if (priorRefunds != null && priorRefunds > 0) {
                throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            }
            long userId = ((Number) order.get("user_id")).longValue();
            long basePoints = ((Number) order.get("base_points")).longValue();
            long bonusPoints = ((Number) order.get("bonus_points")).longValue();
            long totalPoints = basePoints + bonusPoints;

            // P0 保守规则：到账后存在任一扣减流水 → 拒绝
            Integer debits = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM design_point_ledger WHERE user_id = ? "
                            + "AND type IN ('FLAT_GENERATION_DEBIT','ELEVATION_GENERATION_DEBIT','MANUAL_DEBIT') "
                            + "AND create_time > COALESCE((SELECT credited_at FROM recharge_credit "
                            + "  WHERE order_id = ?), now())",
                    Integer.class, userId, orderId);
            if (debits != null && debits > 0) {
                throw exception(REFUND_POINTS_ALREADY_USED);
            }
            var account = pointAccountService.findAccount(userId)
                    .orElseThrow(() -> exception(REFUND_POINTS_ALREADY_USED));
            if (account.availablePoints() < totalPoints) {
                throw exception(REFUND_POINTS_ALREADY_USED);
            }
            // 全额预留（可用 → 预留；不写冲正流水）
            pointAccountService.reserve(userId, totalPoints, "refund_order", String.valueOf(orderId));

            long id = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO refund_order (id, order_id, refund_request_key, amount_cents, "
                            + "channel_state, point_reversal_state, reserved_base, reserved_bonus, "
                            + "operator_id, reason) VALUES (?, ?, ?, ?, 'CREATED', 'RESERVED', ?, ?, ?, ?)",
                    id, orderId, requestKey, ((Number) order.get("amount_cents")).longValue(),
                    basePoints, bonusPoints, operator, reason);
            return id;
        });

        // 渠道调用在事务外
        var order = getOrderById(orderId).orElseThrow();
        String channelRefundId = "refund-" + refundId;
        var refund = paymentPort.requestRefund(order.orderNo(), channelRefundId, order.amountCents());
        switch (refund.getState()) {
            case "SUCCEEDED" -> confirmReversal(refundId, order.userId(), order.basePoints(), order.bonusPoints());
            case "FAILED" -> releaseReservation(refundId, order.userId(), order.basePoints() + order.bonusPoints());
            default -> {
                // 审查 H1：UNKNOWN/PROCESSING 落渠道退款号，交由收口调度（reconcileRefunds）查单
                jdbcTemplate.update(
                        "UPDATE refund_order SET channel_state = 'UNKNOWN', channel_refund_id = ?, "
                                + "update_time = now() WHERE id = ?", channelRefundId, refundId);
                log.info("[requestRefund][refund={} 渠道状态 {}，保持预留并进入查单收口]", refundId, refund.getState());
            }
        }
        return refundId;
    }

    /**
     * 退款 UNKNOWN 收口（审查 H1）：对 channel_state=UNKNOWN/PROCESSING 且仍 RESERVED 的退款单查单，
     * 按渠道结果推进冲正/释放/继续等待。由 ZhongshuJobDriver 定时调用。
     */
    public int reconcileRefunds() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                "SELECT id, order_id, channel_refund_id FROM refund_order "
                        + "WHERE channel_state IN ('UNKNOWN','PROCESSING','CREATED') "
                        + "AND point_reversal_state = 'RESERVED' AND deleted = FALSE "
                        + "ORDER BY id LIMIT 20");
        int resolved = 0;
        for (Map<String, Object> row : pending) {
            long refundId = ((Number) row.get("id")).longValue();
            long orderId = ((Number) row.get("order_id")).longValue();
            String channelRefundId = (String) row.get("channel_refund_id");
            if (channelRefundId == null) {
                continue; // CREATED 未提交渠道：等下一轮（受理方重试）
            }
            var order = getOrderById(orderId).orElse(null);
            if (order == null) {
                continue;
            }
            var query = paymentPort.queryRefund(order.orderNo(), channelRefundId);
            switch (query.getState()) {
                case "SUCCEEDED" -> {
                    confirmReversal(refundId, order.userId(), order.basePoints(), order.bonusPoints());
                    resolved++;
                }
                case "FAILED" -> {
                    releaseReservation(refundId, order.userId(), order.basePoints() + order.bonusPoints());
                    resolved++;
                }
                default -> log.info("[reconcileRefunds][refund={} 渠道仍 {}，保持预留]", refundId, query.getState());
            }
        }
        return resolved;
    }

    /** 渠道退款成功：冲正事务（预留扣减+两类冲正流水+退款单 CAS） */
    private void confirmReversal(Long refundId, long userId, long basePoints, long bonusPoints) {
        txTemplate.execute(status -> {
            Map<String, Object> refund = jdbcTemplate.queryForMap(
                    "SELECT order_id, point_reversal_state, reserved_base, reserved_bonus FROM refund_order "
                            + "WHERE id = ? FOR UPDATE", refundId);
            if (!"RESERVED".equals(refund.get("point_reversal_state"))) {
                return null; // 幂等：已冲正/已释放
            }
            long orderId = ((Number) refund.get("order_id")).longValue();
            pointAccountService.consumeReserveWithReversal(userId, basePoints, bonusPoints,
                    "refund_order", String.valueOf(refundId),
                    "REFUND_REVERSAL:" + refundId + ":base", "REFUND_REVERSAL:" + refundId + ":bonus");
            jdbcTemplate.update(
                    "UPDATE refund_order SET channel_state = 'SUCCEEDED', point_reversal_state = 'REVERSED', "
                            + "update_time = now() WHERE id = ?", refundId);
            // 订单推进终态：状态机兜底防再次受理
            jdbcTemplate.update(
                    "UPDATE recharge_order SET payment_state = 'CLOSED', fulfillment_state = 'NOT_READY', "
                            + "update_time = now() WHERE id = ?", orderId);
            reliableEventPort.append(OutboxEventMessage.builder()
                    .eventType("ORDER_REFUND_REVERSED").bizType("refund_order").bizId(String.valueOf(refundId))
                    .payload(Map.of("refundId", refundId, "orderId", orderId)).build());
            return null;
        });
    }

    /** 渠道退款失败：释放预留 */
    private void releaseReservation(Long refundId, long userId, long totalPoints) {
        txTemplate.execute(status -> {
            Map<String, Object> refund = jdbcTemplate.queryForMap(
                    "SELECT order_id, point_reversal_state, reserved_base, reserved_bonus FROM refund_order "
                            + "WHERE id = ? FOR UPDATE", refundId);
            if (!"RESERVED".equals(refund.get("point_reversal_state"))) {
                return null;
            }
            pointAccountService.releaseReserve(userId, totalPoints);
            jdbcTemplate.update(
                    "UPDATE refund_order SET channel_state = 'FAILED', point_reversal_state = 'RELEASED', "
                            + "update_time = now() WHERE id = ?", refundId);
            return null;
        });
    }

    // ========== 工具 ==========

    private String toJsonOrNull(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
