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

    private final PaymentFactValidator factValidator;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort accountStatePort;

    /**
     * T13-29：退款卡单审计宽限期（分钟）。非终态退款单（PROCESSING/UNKNOWN/CREATED）超过此时长
     * 仍未收口，重启补偿 {@link #recoverPendingRefunds()} 落一条 {@code REFUND_STUCK} 审计供人工核对，
     * 避免退款永久悬挂而无人知晓（分派表 §3.2 T13-29 ④ + 可观测性）。
     */
    private static final int REFUND_STUCK_GRACE_MINUTES = 30;

    public RechargePaymentService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  PaymentPort paymentPort, PointLedgerPort ledgerPort,
                                  PointAccountService pointAccountService, ReliableEventPort reliableEventPort,
                                  PaymentFactValidator factValidator) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.paymentPort = paymentPort;
        this.ledgerPort = ledgerPort;
        this.pointAccountService = pointAccountService;
        this.reliableEventPort = reliableEventPort;
        this.factValidator = factValidator;
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

    public OrderSnapshot createOrder(long userId, long planId, String idempotencyKey, String openid) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Long> existing = jdbcTemplate.query(
                    "SELECT id FROM recharge_order WHERE user_id = ? AND idempotency_key = ?",
                    (rs, i) -> rs.getLong("id"), userId, idempotencyKey);
            if (!existing.isEmpty()) {
                var detail = getOrderDetail(userId, existing.get(0)).orElseThrow();
                if (detail.planId() != planId) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
                ensurePrepay(userId, detail.orderId(), openid, false);
                return getOrderById(detail.orderId()).orElseThrow();
            }
        }
        // 快照方案（事务内）
        OrderSnapshot created = txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
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
            int inserted = jdbcTemplate.update(
                    "INSERT INTO recharge_order (id, order_no, user_id, plan_id, plan_snapshot, amount_cents, "
                            + "base_points, bonus_points, payment_state, fulfillment_state, idempotency_key, "
                            + "payer_openid, payment_channel, payment_merchant) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, 'CREATED', 'NOT_READY', ?, ?, ?, ?) "
                            + "ON CONFLICT (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING",
                    orderId, orderNo, userId, planId,
                    planJson(plan), ((Number) plan.get("amount_cents")).longValue(),
                    ((Number) plan.get("base_points")).longValue(),
                    ((Number) plan.get("bonus_points")).longValue(),
                    idempotencyKey, openid, paymentPort.channel(), paymentPort.merchantId());
            if (inserted == 0) {
                Long existingId = jdbcTemplate.queryForObject(
                        "SELECT id FROM recharge_order WHERE user_id=? AND idempotency_key=?",
                        Long.class, userId, idempotencyKey);
                var detail = getOrderDetail(userId, existingId).orElseThrow();
                if (detail.planId() != planId) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
                return getOrderById(existingId).orElseThrow();
            }
            return new OrderSnapshot(orderId, orderNo, userId,
                    ((Number) plan.get("amount_cents")).longValue(),
                    ((Number) plan.get("base_points")).longValue(),
                    ((Number) plan.get("bonus_points")).longValue(), "CREATED", "NOT_READY");
        });

        ensurePrepay(userId, created.orderId(), openid, false);
        return getOrderById(created.orderId()).orElseThrow();
    }

    private record PrepayClaim(OrderSnapshot order, String openid, String token) { }

    /** Short lease transaction, external call, fenced update. Retries keep the same merchant order. */
    private void ensurePrepay(long userId, long orderId, String sessionOpenid, boolean refresh) {
        PrepayClaim claim = txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            var rows = jdbcTemplate.queryForList("SELECT *, (prepay_expires_at > now()) AS fresh, "
                    + "(prepay_lease_until > now()) AS busy FROM recharge_order "
                    + "WHERE id=? AND user_id=? AND deleted=FALSE FOR UPDATE", orderId, userId);
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            if (!List.of("CREATED", "PENDING", "UNKNOWN").contains(row.get("payment_state"))) return null;
            String payer = (String) row.get("payer_openid");
            if (payer != null && sessionOpenid != null && !payer.equals(sessionOpenid)) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            if (payer == null) payer = sessionOpenid;
            if (payer == null || payer.isBlank()) return null;
            if (row.get("payment_channel") != null && (!paymentPort.channel().equals(row.get("payment_channel"))
                    || !paymentPort.merchantId().equals(row.get("payment_merchant")))) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            if ((!refresh && Boolean.TRUE.equals(row.get("fresh")) && row.get("prepay_params") != null)
                    || Boolean.TRUE.equals(row.get("busy"))) return null;
            String token = java.util.UUID.randomUUID().toString();
            jdbcTemplate.update("UPDATE recharge_order SET payer_openid=?, payment_channel=?, payment_merchant=?, "
                    + "prepay_lease_token=?, prepay_lease_until=now()+interval '60 seconds', prepay_expires_at=NULL "
                    + "WHERE id=?", payer, paymentPort.channel(), paymentPort.merchantId(), token, orderId);
            return new PrepayClaim(getOrderById(orderId).orElseThrow(), payer, token);
        });
        if (claim == null) return;
        try {
            var prepay = paymentPort.createPrepay(claim.order().orderNo(), claim.order().amountCents(), "充值", claim.openid());
            var params = prepay.getCallParams();
            if (params != null && "ALREADY_PAID".equals(params.get("state"))) {
                reconcile(claim.order().orderNo());
                return;
            }
            if (params == null || params.isEmpty() || params.containsKey("state")) {
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409, "支付参数尚未就绪，请稍后重试原订单");
            }
            if ("WECHAT".equals(paymentPort.channel()) && List.of("appId", "timeStamp", "nonceStr", "package", "signType", "paySign")
                    .stream().anyMatch(k -> params.get(k) == null || params.get(k).isBlank())) {
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409, "支付参数不完整，请稍后重试原订单");
            }
            jdbcTemplate.update("UPDATE recharge_order SET payment_state='PENDING', prepay_params=CAST(? AS jsonb), "
                    + "prepay_expires_at=now()+interval '5 minutes', update_time=now() "
                    + "WHERE id=? AND prepay_lease_token=? AND payment_state IN ('CREATED','PENDING','UNKNOWN')",
                    toJson(params), orderId, claim.token());
        } finally {
            jdbcTemplate.update("UPDATE recharge_order SET prepay_lease_token=NULL, prepay_lease_until=NULL "
                    + "WHERE id=? AND prepay_lease_token=?", orderId, claim.token());
        }
    }

    private String planJson(Map<String, Object> plan) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** T13-24：Map → JSON 字符串（prepay_params 持久化用） */
    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("prepay_params 序列化失败", e);
        }
    }

    /**
     * T13-24：独立领取 payParams（恢复流程端点 GET /recharge-orders/{orderId}/pay-params）。
     * 仅当订单归属当前 userId 且 paymentState IN ('CREATED','PENDING') 时返回；
     * 已支付/已关闭/已失败订单返回 empty（前端引导查看充值记录，不重复拉起支付）。
     */
    public Optional<Map<String, String>> getPayParams(long userId, long orderId) {
        return getPayParams(userId, orderId, null);
    }

    /** sessionOpenid is obtained from the authenticated identity port, never from client input. */
    public Optional<Map<String, String>> getPayParams(long userId, long orderId, String sessionOpenid) {
        ensurePrepay(userId, orderId, sessionOpenid, false);
        Integer fresh = jdbcTemplate.queryForObject("SELECT count(*) FROM recharge_order WHERE id=? AND user_id=? "
                + "AND prepay_expires_at > now() AND deleted=FALSE", Integer.class, orderId, userId);
        if (fresh == null || fresh == 0) return Optional.empty();
        return getOrderDetail(userId, orderId)
                .filter(detail -> "CREATED".equals(detail.paymentState()) || "PENDING".equals(detail.paymentState()))
                .map(OrderDetail::prepayParams)
                .filter(params -> params != null && !params.isEmpty());
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
                              String fulfillmentState, Instant createdAt, Instant paidAt, RefundSummary refund,
                              Map<String, String> prepayParams) {
    }

    public record RefundSummary(String refundId, String channelState, String pointReversalState,
                                long amountCents, String reason) { }

    private static final String ORDER_DETAIL_COLUMNS =
            "o.id, o.order_no, o.user_id, o.plan_id, o.amount_cents, o.base_points, o.bonus_points, "
                    + "o.payment_state, o.fulfillment_state, o.create_time, "
                    + "CASE WHEN o.prepay_expires_at > now() THEN o.prepay_params END AS prepay_params, t.paid_at, "
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
                        rs.getLong("refund_amount_cents"), rs.getString("reason")),
                parsePrepayParams(rs.getString("prepay_params")));
    }

    /** T13-24：解析 prepay_params JSONB → Map；null/空/解析失败均返回 null（前端按无 payParams 处理） */
    private Map<String, String> parsePrepayParams(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            log.warn("[parsePrepayParams] prepay_params 解析失败，按 null 处理: {}", e.getMessage());
            return null;
        }
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

        // T13-26 Phase 1：可靠接收 — INSERT inbox 独立事务提交。
        // 即使 Phase 2 到账处理失败/进程崩溃，inbox 记录已持久化，
        // recoverPendingInbox 补偿扫描可重放（分派表 §3.2 T13-26 ①）。
        String insertOutcome = txTemplate.execute(status -> {
            try {
                // T13-22：channel 从 paymentPort.channel() 取，禁止硬编码 'STUB'（分派表 §8 红线 2）
                jdbcTemplate.update(
                        "INSERT INTO payment_notification_inbox (id, channel, event_id, request_headers, body_hash, "
                                + "normalized_payload, verify_status) VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)",
                        IdWorker.getId(), paymentPort.channel(), notification.getEventId(),
                        toJsonOrNull(Map.of("count", headers.size())), sha256Hex(body), toJsonOrNull(Map.of(
                                "orderNo", notification.getOrderNo(),
                                "amountCents", notification.getAmountCents())), "PASSED");
                return "INSERTED";
            } catch (DuplicateKeyException e) {
                return "DUPLICATE";
            }
        });
        if ("DUPLICATE".equals(insertOutcome)) {
            return "DUPLICATE";
        }

        // T13-26 Phase 2：到账处理 — processPaymentFact / fulfillOrder 各自持有独立 txTemplate，
        // 与 Phase 1 的 inbox 事务解耦（分派表 §3.2 T13-26 ①“再独立事务处理到账”）。
        try {
            // T13-27：前置校验 + 异常审计（transactionId / 金额 / 实付回退 / paidAt）
            long declaredAmount = declaredAmount(notification.getOrderNo());
            long effectiveAmount = factValidator.validateAndAudit(
                    notification.getOrderNo(), notification.getEventId(),
                    notification.getChannelTransactionId(), declaredAmount,
                    notification.getAmountCents(), notification.getPaidAt());
            boolean paid = processPaymentFact(notification.getOrderNo(), notification.getEventId(),
                    notification.getChannelTransactionId(), effectiveAmount,
                    notification.getPaidAt());
            if (paid) {
                fulfillOrder(notification.getOrderNo());
            }
            // paid==false 表示「已受理但无状态迁移」（同 orderNo 不同 eventId 的重发通知，或订单已 SUCCEEDED），
            // 属幂等正常完成。ck_payment_inbox_status 只允许 RECEIVED/PROCESSED/FAILED，写 'REJECTED'
            // 会触发约束违反 → 500 → 渠道无限重试，故 inbox 一律记 PROCESSED，
            // 仅对调用方返回值区分 REJECTED 以便观测。
            markInbox(notification.getEventId(), "PROCESSED", null);
            return paid ? "PROCESSED" : "REJECTED";
        } catch (Exception e) {
            // 处理失败：Inbox 留痕可重投（recoverPendingInbox / recoverHangingOrders 补偿）
            markInbox(notification.getEventId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    /**
     * T13-28：处理渠道退款结果通知（入站）。
     *
     * <p>与 {@link #handleNotification} 对称的两阶段：Phase 1 可靠接收（INSERT inbox 独立事务，
     * UK(channel, event_id) 幂等）；Phase 2 状态推进（{@code confirmReversal} / {@code releaseReservation}
     * 各自持有独立事务，且内部有 {@code point_reversal_state='RESERVED'} CAS 守卫，重复调用安全）。
     *
     * <p>分派表 §8 红线 5：{@code PROCESSING}/{@code UNKNOWN} 既不冲正也不释放，仅落
     * {@code refund_order.channel_state} 并保持预留，交由 {@link #reconcileRefunds()} 查单收口。
     *
     * @return REVERSED（已冲正）/ RELEASED（已释放预留）/ PENDING（渠道未终态，保持预留）/ DUPLICATE（重复通知）
     */
    public String handleRefundNotification(java.util.Map<String, String> headers, byte[] body) {
        if (!paymentPort.verifyNotification(headers, body)) {
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }
        var notification = paymentPort.parseRefundNotification(headers, body);

        // Phase 1：可靠接收（与支付通知共用 payment_notification_inbox，UK(channel, event_id) 幂等）
        String insertOutcome = txTemplate.execute(status -> {
            try {
                jdbcTemplate.update(
                        "INSERT INTO payment_notification_inbox (id, channel, event_id, request_headers, body_hash, "
                                + "normalized_payload, verify_status) VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)",
                        IdWorker.getId(), paymentPort.channel(), notification.getEventId(),
                        toJsonOrNull(Map.of("count", headers.size())), sha256Hex(body),
                        toJsonOrNull(Map.of(
                                "orderNo", notification.getOrderNo(),
                                "channelRefundId", notification.getChannelRefundId(),
                                "state", notification.getState())), "PASSED");
                return "INSERTED";
            } catch (DuplicateKeyException e) {
                return "DUPLICATE";
            }
        });
        if ("DUPLICATE".equals(insertOutcome)) {
            return "DUPLICATE";
        }

        // Phase 2：状态推进
        try {
            String outcome = applyRefundNotification(notification);
            markInbox(notification.getEventId(), "PROCESSED", null);
            return outcome;
        } catch (Exception e) {
            markInbox(notification.getEventId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    /**
     * 按渠道退款终态推进本地退款单。P0 仅整单全额退款，故通知金额必须与
     * {@code refund_order.amount_cents} 一致（分派表 §3.1 T13-28「退款金额与原订单一致性校验」）；
     * 不符则落审计并拒绝，绝不静默吞掉。
     */
    private String applyRefundNotification(PaymentPort.NormalizedRefundNotification notification) {
        // channel_refund_id 无 UK，用 order_no + channel_refund_id 联合定位并取最新一条
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.id, r.amount_cents, r.point_reversal_state, r.reserved_base, r.reserved_bonus, "
                        + "o.user_id FROM refund_order r JOIN recharge_order o ON o.id = r.order_id "
                        + "WHERE o.order_no = ? AND r.channel_refund_id = ? AND r.deleted = FALSE "
                        + "ORDER BY r.id DESC LIMIT 1",
                notification.getOrderNo(), notification.getChannelRefundId());
        if (rows.isEmpty()) {
            factValidator.audit(notification.getOrderNo(), notification.getEventId(),
                    "REFUND_ORDER_NOT_FOUND", "REJECT", "existing refund_order",
                    notification.getChannelRefundId(), "退款通知指向未知退款单，拒绝推进");
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }
        Map<String, Object> refund = rows.get(0);
        long refundId = ((Number) refund.get("id")).longValue();
        long userId = ((Number) refund.get("user_id")).longValue();
        long expectedAmount = ((Number) refund.get("amount_cents")).longValue();
        long basePoints = ((Number) refund.get("reserved_base")).longValue();
        long bonusPoints = ((Number) refund.get("reserved_bonus")).longValue();

        // 金额一致性（REJECT）：渠道退款金额必须等于本地整单全额
        if (notification.getRefundAmountCents() != expectedAmount) {
            factValidator.audit(notification.getOrderNo(), notification.getEventId(),
                    "REFUND_AMOUNT_MISMATCH", "REJECT", String.valueOf(expectedAmount),
                    String.valueOf(notification.getRefundAmountCents()),
                    "退款通知金额与退款单不符，拒绝推进冲正/释放");
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }

        return switch (notification.getState()) {
            case "SUCCEEDED" -> {
                if (!confirmReversal(refundId, userId, basePoints, bonusPoints)) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
                yield "REVERSED";
            }
            case "FAILED" -> {
                boolean released = releaseReservation(refundId, userId, basePoints + bonusPoints);
                yield released ? "RELEASED" : "REVERSED"; // Late failure cannot undo confirmed success.
            }
            // 红线 5：PROCESSING / UNKNOWN / CREATED 不冲正不释放，仅落 channel_state 等查单收口。
            // 白名单归一：非 ck_refund_channel_state 允许值一律归 UNKNOWN，避免约束违反。
            default -> {
                String persisted = List.of("PROCESSING", "UNKNOWN", "CREATED", "ABNORMAL").contains(notification.getState())
                        ? notification.getState() : "UNKNOWN";
                jdbcTemplate.update(
                        "UPDATE refund_order SET channel_state = ?, update_time = now() WHERE id = ? AND point_reversal_state = 'RESERVED'",
                        persisted, refundId);
                log.info("[handleRefundNotification][refund={} 渠道状态 {}，保持预留并进入查单收口]",
                        refundId, notification.getState());
                yield "PENDING";
            }
        };
    }

    private void markInbox(String eventId, String status, String error) {
        // T13-22：channel 过滤条件同样取自 paymentPort.channel()，防止 stub/real 切换时误改他渠道 Inbox
        jdbcTemplate.update(
                "UPDATE payment_notification_inbox SET process_status = ?, last_error = ?, "
                        + "retry_count = retry_count + 1, update_time = now() WHERE channel = ? AND event_id = ?",
                status, error, paymentPort.channel(), eventId);
    }

    /** 补偿一：重投失败 Inbox（重放事件；同幂等链不重复改变事实）。
     *  T13-26 ⑤：retry_count < 10 上限，避免毒丸事件无限重试拖垮补偿线程。 */
    public int recoverPendingInbox() {
        // T13-22：补偿扫描同样按当前 channel 隔离，避免 stub 补偿误捞 real Inbox（反之亦然）
        var failed = jdbcTemplate.queryForList(
                "SELECT event_id, normalized_payload FROM payment_notification_inbox "
                        + "WHERE process_status IN ('RECEIVED','FAILED') AND channel = ? "
                        + "AND retry_count < 10 ORDER BY id LIMIT 50", paymentPort.channel());
        int recovered = 0;
        for (var row : failed) {
            String eventId = (String) row.get("event_id");
            String orderNo = jdbcTemplate.queryForObject(
                    "SELECT normalized_payload->>'orderNo' FROM payment_notification_inbox "
                            + "WHERE channel = ? AND event_id = ?", String.class, paymentPort.channel(), eventId);
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
        var hanging = txTemplate.execute(status -> jdbcTemplate.queryForList(
                "WITH due AS (SELECT id FROM recharge_order WHERE payment_state IN ('CREATED','PENDING','UNKNOWN','SUCCEEDED') "
                        + "AND fulfillment_state <> 'CREDITED' AND deleted=FALSE AND recovery_after <= now() "
                        + "AND payment_channel=? AND payment_merchant=? ORDER BY recovery_after,id "
                        + "LIMIT 50 FOR UPDATE SKIP LOCKED) UPDATE recharge_order o SET "
                        + "recovery_after=now()+interval '60 seconds', recovery_attempts=recovery_attempts+1 "
                        + "FROM due WHERE o.id=due.id RETURNING o.id,o.order_no,o.user_id,o.payment_state",
                paymentPort.channel(), paymentPort.merchantId()));
        int recovered = 0;
        for (var row : hanging) {
            String orderNo = (String) row.get("order_no");
            try {
                if ("SUCCEEDED".equals(row.get("payment_state"))) {
                    if (fulfillOrder(orderNo)) recovered++;
                    continue;
                }
                String outcome = reconcile(orderNo);
                if (List.of("RECOVERED", "ALREADY_RECONCILED", "CLOSED", "FAILED").contains(outcome)) recovered++;
                else if ("CREATED".equals(row.get("payment_state"))) {
                    ensurePrepay(((Number) row.get("user_id")).longValue(), ((Number) row.get("id")).longValue(), null, false);
                }
            } catch (Exception e) {
                // Per-order isolation: a poison record never skips the rest of this batch.
                log.warn("[recoverHangingOrders][order={} retry scheduled, failureType={}]", orderNo, e.getClass().getSimpleName());
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
                    "SELECT id, amount_cents, payment_state, deleted FROM recharge_order "
                            + "WHERE order_no = ? FOR UPDATE", orderNo);
            if (orders.isEmpty()) {
                log.warn("[processPaymentFact][未知订单号 {}]", orderNo);
                return false;
            }
            Map<String, Object> order = orders.get(0);
            if (Boolean.TRUE.equals(order.get("deleted"))) {
                // Keep a late channel notification in the error/retry path for investigation.
                throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            }
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
                // T13-22：channel + merchant_id 均从 paymentPort 取，写入 payment_transaction 的
                // UK(channel, merchant_id, channel_transaction_id) 幂等键在 stub/real 之间自然隔离
                jdbcTemplate.update(
                        "INSERT INTO payment_transaction (id, channel, merchant_id, channel_transaction_id, "
                                + "order_no, amount_cents, paid_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(), paymentPort.channel(), paymentPort.merchantId(),
                        channelTransactionId, orderNo, amountCents,
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
            if (List.of("CLOSED", "FAILED").contains(query.getState())) {
                jdbcTemplate.update("UPDATE recharge_order SET payment_state=?, prepay_params=NULL, prepay_expires_at=NULL, "
                        + "update_time=now() WHERE order_no=? AND payment_state IN ('CREATED','PENDING','UNKNOWN') "
                        + "AND fulfillment_state <> 'CREDITED'", query.getState(), orderNo);
            }
            return query.getState();
        }
        long declaredAmount = declaredAmount(orderNo);
        // T13-27：前置校验 + 异常审计（缺实付金额时回退声明值 + AUDIT 标记）
        long effectiveAmount = factValidator.validateAndAudit(orderNo, "reconcile-" + orderNo,
                query.getChannelTransactionId(), declaredAmount, query.getAmountCents(), Instant.now());
        boolean moved = processPaymentFact(orderNo, "reconcile-" + orderNo,
                query.getChannelTransactionId(), effectiveAmount, Instant.now());
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
            var lockedReplay = jdbcTemplate.queryForList("SELECT id FROM refund_order WHERE refund_request_key=?",Long.class,requestKey);
            if (!lockedReplay.isEmpty()) return lockedReplay.get(0);
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
                            + "operator_id, reason, channel_refund_id) VALUES (?, ?, ?, ?, 'CREATED', 'RESERVED', ?, ?, ?, ?, ?)",
                    id, orderId, requestKey, ((Number) order.get("amount_cents")).longValue(),
                    basePoints, bonusPoints, operator, reason, "refund-" + id);
            return id;
        });

        var persisted = jdbcTemplate.queryForMap(
                "SELECT order_id, channel_refund_id, point_reversal_state FROM refund_order WHERE id = ?", refundId);
        if (((Number) persisted.get("order_id")).longValue() != orderId) {
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }
        if (!"RESERVED".equals(persisted.get("point_reversal_state"))) return refundId;
        var order = getOrderById(orderId).orElseThrow();
        String channelRefundId = (String) persisted.get("channel_refund_id");
        try {
            applyRefundState(refundId, orderId, paymentPort.requestRefund(
                    order.orderNo(), channelRefundId, order.amountCents()).getState());
        } catch (RuntimeException uncertain) {
            // The channel may already have accepted this stable merchant refund identity.
            persistPendingRefund(refundId, "UNKNOWN");
            log.warn("[requestRefund][refund={} channel result uncertain type={}]", refundId,
                    uncertain.getClass().getSimpleName());
        }
        return refundId;
    }

    /**
     * 退款 UNKNOWN 收口（审查 H1 / 分派表 §3.2 T13-29 ②）：对 channel_state=UNKNOWN/PROCESSING/CREATED
     * 且仍 RESERVED 的退款单查单，按渠道结果推进冲正/释放/继续等待。由 {@code RefundRecoveryJob} 定时调用。
     *
     * <p>分派表 §8 红线 5：PROCESSING/UNKNOWN 既不冲正也不释放，仅保持预留等下一轮查单，直到渠道终态。
     */
    public int reconcileRefunds() {
        // New refunds can become stuck long after ApplicationReadyEvent. Both drivers
        // must use the same deduplicated audit as well as the same terminal settlement.
        return recoverPendingRefunds();
    }

    /**
     * T13-29 ④ 重启补偿：进程重启后重新驱动所有未终态退款单查单收口。与 {@link #reconcileRefunds()}
     * 共用查单驱动逻辑；额外对超过 {@link #REFUND_STUCK_GRACE_MINUTES} 仍卡在非终态的退款单落
     * {@code REFUND_STUCK} 审计（去重），供人工核对渠道退款结果。由 {@code RefundRecoveryJob}
     * 在 {@code ApplicationReadyEvent} 时调用一次。
     *
     * @return 本轮收口（冲正/释放）的退款单数
     */
    public int recoverPendingRefunds() {
        int resolved = 0;
        for (Map<String, Object> row : queryPendingRefunds()) {
            long refundId = ((Number) row.get("id")).longValue();
            long orderId = ((Number) row.get("order_id")).longValue();
            String channelRefundId = (String) row.get("channel_refund_id");
            if (resolveRefundByQuery(refundId, orderId, channelRefundId)) {
                resolved++;
                continue;
            }
            // 仍未终态：若已超宽限期则落卡单审计（红线 5——审计不改变状态、不冲正不释放）
            var order = getOrderById(orderId).orElse(null);
            if (order != null) {
                auditStuckRefundIfNeeded(refundId, order.orderNo());
            }
        }
        return resolved;
    }

    /** 扫描未终态且仍 RESERVED 的退款单（PROCESSING/UNKNOWN/CREATED），最多 20 笔一轮。 */
    private List<Map<String, Object>> queryPendingRefunds() {
        return jdbcTemplate.queryForList(
                "SELECT id, order_id, channel_refund_id FROM refund_order "
                        + "WHERE channel_state IN ('UNKNOWN','PROCESSING','CREATED','ABNORMAL') "
                        + "AND point_reversal_state = 'RESERVED' AND deleted = FALSE AND next_reconcile_at <= now() "
                        + "ORDER BY next_reconcile_at, id LIMIT 20");
    }

    /**
     * 查单驱动单个退款单收口。
     *
     * @return true=已到终态（SUCCEEDED 冲正 / FAILED 释放）；false=仍未终态或不可处理（保持预留）
     */
    private boolean resolveRefundByQuery(long refundId, long orderId, String channelRefundId) {
        var order = getOrderById(orderId).orElse(null);
        if (order == null || channelRefundId == null) return false;
        // Reserve a future slot first so one failing row cannot starve the remaining scan.
        jdbcTemplate.update("UPDATE refund_order SET reconcile_attempts=reconcile_attempts+1, "
                + "next_reconcile_at=now() + (LEAST(300, 5 * (reconcile_attempts+1)) * interval '1 second') "
                + "WHERE id=? AND point_reversal_state='RESERVED'", refundId);
        try {
            String state = paymentPort.queryRefund(order.orderNo(), channelRefundId).getState();
            if ("NOT_FOUND".equals(state)) {
                // Authoritative absence: CREATED or UNKNOWN can both mean the first send never arrived.
                // Always replay the persisted merchant number, never allocate another refund.
                state = paymentPort.requestRefund(order.orderNo(), channelRefundId, order.amountCents()).getState();
            }
            return applyRefundState(refundId, orderId, state);
        } catch (RuntimeException uncertain) {
            log.warn("[reconcileRefunds][refund={} query uncertain type={}]", refundId,
                    uncertain.getClass().getSimpleName());
            return false;
        }
    }

    private boolean applyRefundState(long refundId, long orderId, String state) {
        var order = getOrderById(orderId).orElseThrow();
        if ("SUCCEEDED".equals(state)) {
            if (!confirmReversal(refundId, order.userId(), order.basePoints(), order.bonusPoints())) throw exception(PAYMENT_ORDER_STATE_CONFLICT);
            return true;
        }
        if ("FAILED".equals(state)) {
            releaseReservation(refundId, order.userId(), order.basePoints() + order.bonusPoints());
            return true;
        }
        persistPendingRefund(refundId, state);
        return false;
    }

    private void persistPendingRefund(long refundId, String state) {
        String pending = List.of("PROCESSING", "UNKNOWN", "CREATED", "ABNORMAL").contains(state) ? state : "UNKNOWN";
        jdbcTemplate.update("UPDATE refund_order SET channel_state=?, update_time=now() "
                + "WHERE id=? AND point_reversal_state='RESERVED'", pending, refundId);
    }

    /**
     * 卡单审计：仅对超过宽限期仍未终态的退款单落一次 {@code REFUND_STUCK} 审计（AUDIT 级，去重）。
     * 语义为软告警——不改变退款单状态、不冲正不释放，只为人工提供可观测入口。
     */
    private void auditStuckRefundIfNeeded(long refundId, String orderNo) {
        Integer stuck = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refund_order WHERE id = ? "
                        + "AND channel_state IN ('PROCESSING','UNKNOWN','CREATED','ABNORMAL') "
                        + "AND point_reversal_state = 'RESERVED' "
                        + "AND create_time < now() - (? * interval '1 minute')",
                Integer.class, refundId, REFUND_STUCK_GRACE_MINUTES);
        if (stuck == null || stuck == 0) {
            return; // 未超宽限期：正常在途，不告警
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'REFUND_STUCK'",
                Integer.class, orderNo);
        if (existing != null && existing > 0) {
            return; // 已告警过：去重，避免每轮补偿重复落审计
        }
        factValidator.audit(orderNo, "refund-stuck-" + refundId, "REFUND_STUCK", "AUDIT",
                "terminal within " + REFUND_STUCK_GRACE_MINUTES + "min", "still non-terminal",
                "退款长时间卡在非终态（PROCESSING/UNKNOWN），需人工核对渠道退款结果");
    }

    /** 渠道退款成功：冲正事务（预留扣减+两类冲正流水+退款单 CAS） */
    private boolean confirmReversal(Long refundId, long userId, long basePoints, long bonusPoints) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Map<String, Object> refund = jdbcTemplate.queryForMap(
                    "SELECT order_id, point_reversal_state, reserved_base, reserved_bonus FROM refund_order "
                            + "WHERE id = ? FOR UPDATE", refundId);
            if (!"RESERVED".equals(refund.get("point_reversal_state"))) {
                return "REVERSED".equals(refund.get("point_reversal_state")); // Only real reversal is successful
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
                    .payload(Map.of("refundId", refundId, "orderId", orderId, "userId", userId)).build());
            return true;
        }));
    }

    /** 渠道退款失败：释放预留 */
    private boolean releaseReservation(Long refundId, long userId, long totalPoints) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Map<String, Object> refund = jdbcTemplate.queryForMap(
                    "SELECT order_id, point_reversal_state, reserved_base, reserved_bonus FROM refund_order "
                            + "WHERE id = ? FOR UPDATE", refundId);
            if (!"RESERVED".equals(refund.get("point_reversal_state"))) {
                return "RELEASED".equals(refund.get("point_reversal_state"));
            }
            pointAccountService.releaseReserve(userId, totalPoints);
            jdbcTemplate.update(
                    "UPDATE refund_order SET channel_state = 'FAILED', point_reversal_state = 'RELEASED', "
                            + "update_time = now() WHERE id = ?", refundId);
            return true;
        }));
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
