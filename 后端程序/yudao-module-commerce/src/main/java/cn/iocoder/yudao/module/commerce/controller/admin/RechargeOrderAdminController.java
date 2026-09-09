package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.commerce.enums.PermissionConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 充值订单与退款（页面 11）")
@RestController
@RequestMapping("/design/v1")
public class RechargeOrderAdminController {

    private final cn.iocoder.yudao.module.commerce.payment.RechargePaymentService paymentService;

    public RechargeOrderAdminController(cn.iocoder.yudao.module.commerce.payment.RechargePaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/recharge-orders")
    @Operation(summary = "充值订单分页：支付事实与到账事实分别展示，异常订单筛选")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_ORDER_QUERY + "')")
    public CommonResult<PageResult<Map<String, Object>>> getOrderPage(
            @RequestParam(value = "paymentState", required = false) String paymentState,
            @RequestParam(value = "fulfillmentState", required = false) String fulfillmentState,
            @RequestParam(value = "abnormalOnly", required = false) Boolean abnormalOnly,
            @RequestParam(value = "paidNoCredit", required = false) Boolean paidNoCredit,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var where = new java.util.ArrayList<String>(List.of("deleted = FALSE"));
        var args = new java.util.ArrayList<Object>();
        if (paymentState != null && !paymentState.isBlank()) {
            where.add("payment_state = ?");
            args.add(paymentState);
        }
        if (fulfillmentState != null && !fulfillmentState.isBlank()) {
            where.add("fulfillment_state = ?");
            args.add(fulfillmentState);
        }
        if (Boolean.TRUE.equals(abnormalOnly)) {
            where.add("(payment_state IN ('UNKNOWN', 'FAILED') OR fulfillment_state = 'FAILED')");
        }
        if (Boolean.TRUE.equals(paidNoCredit)) {
            where.add("payment_state = 'SUCCEEDED' AND fulfillment_state <> 'CREDITED'");
        }
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        String base = "FROM recharge_order WHERE " + String.join(" AND ", where);
        Integer total = jdbc.queryForObject("SELECT count(*) " + base, Integer.class, args.toArray());
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        var params = new java.util.ArrayList<Object>(args);
        params.add(pageSize);
        params.add((long) Math.max(pageNo - 1, 0) * pageSize);
        var rows = jdbc.queryForList(
                "SELECT id, order_no, user_id, amount_cents, base_points, bonus_points, "
                        + "payment_state, fulfillment_state, create_time, "
                        + "(SELECT channel_state FROM refund_order r WHERE r.order_id = recharge_order.id "
                        + "AND r.deleted = FALSE ORDER BY r.id DESC LIMIT 1) AS refund_state, "
                        + "(SELECT point_reversal_state FROM refund_order r WHERE r.order_id = recharge_order.id "
                        + "AND r.deleted = FALSE ORDER BY r.id DESC LIMIT 1) AS reversal_state "
                        + base + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                params.toArray());
        var list = rows.stream().map(r -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("orderId", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("orderNo", r.get("order_no"));
            item.put("userId", String.valueOf(((Number) r.get("user_id")).longValue()));
            item.put("amountCents", ((Number) r.get("amount_cents")).longValue());
            item.put("basePoints", ((Number) r.get("base_points")).longValue());
            item.put("bonusPoints", ((Number) r.get("bonus_points")).longValue());
            item.put("paymentState", r.get("payment_state"));
            item.put("fulfillmentState", r.get("fulfillment_state"));
            item.put("refundState", r.get("refund_state"));
            item.put("pointReversalState", r.get("reversal_state"));
            item.put("createdAt", formatter.format(((java.sql.Timestamp) r.get("create_time")).toInstant()));
            return item;
        }).toList();
        return success(new PageResult<>(list, total == null ? 0 : total.longValue()));
    }

    @jakarta.annotation.Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @PostMapping("/recharge-orders/{orderId}/refund-requests")
    @Operation(summary = "受理整单退款：到账后存在任一扣点流水或可用点不足直接拒绝（P0 仅整单）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.PAYMENT_RECONCILE + "')")
    public CommonResult<Map<String, Object>> createRefundRequest(@PathVariable("orderId") String orderId,
                                                                 @RequestBody(required = false) Map<String, Object> body) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        String requestKey = "admin-refund-" + orderId;
        Long refundId = paymentService.requestRefund(Long.parseLong(orderId), operator,
                body == null ? null : String.valueOf(body.get("reason")), requestKey);
        return success(Map.of("refundId", String.valueOf(refundId)));
    }

    @GetMapping("/recharge-orders/{orderId}")
    @Operation(summary = "订单详情（支付/到账/退款状态分列）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.RECHARGE_ORDER_QUERY + "')")
    public CommonResult<Map<String, Object>> getOrder(@PathVariable("orderId") String orderId) {
        var orderOpt = paymentService.getOrderById(Long.parseLong(orderId));
        if (orderOpt.isEmpty()) {
            return success(null);
        }
        var o = orderOpt.get();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("orderId", String.valueOf(o.orderId()));
        result.put("orderNo", o.orderNo());
        result.put("paymentState", o.paymentState());
        result.put("fulfillmentState", o.fulfillmentState());
        result.put("amountCents", o.amountCents());
        result.put("basePoints", o.basePoints());
        result.put("bonusPoints", o.bonusPoints());
        paymentService.getOrderDetail(o.userId(), o.orderId()).ifPresent(detail -> {
            var zone = java.time.ZoneId.of("Asia/Shanghai");
            result.put("userId", String.valueOf(detail.userId()));
            result.put("createdAt", java.time.LocalDateTime.ofInstant(detail.createdAt(), zone));
            result.put("paidAt", detail.paidAt() == null ? null : java.time.LocalDateTime.ofInstant(detail.paidAt(), zone));
            result.put("refund", detail.refund());
        });
        return success(result);
    }

    @PostMapping("/recharge-orders/{orderId}/reconciliation")
    @Operation(summary = "主动查单收口（通知丢失/UNKNOWN 兜底）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.PAYMENT_RECONCILE + "')")
    public CommonResult<String> reconcileOrder(@PathVariable("orderId") String orderId) {
        var order = paymentService.getOrderById(Long.parseLong(orderId));
        if (order.isEmpty()) {
            return success("ORDER_NOT_FOUND");
        }
        return success(paymentService.reconcile(order.get().orderNo()));
    }

    @GetMapping("/refund-orders")
    @Operation(summary = "渠道退款单分页")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.PAYMENT_RECONCILE + "')")
    public CommonResult<PageResult<Map<String, Object>>> getRefundOrderPage(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var where = new java.util.ArrayList<String>(List.of("r.deleted = FALSE"));
        var args = new java.util.ArrayList<Object>();
        if (state != null && !state.isBlank()) {
            where.add("r.channel_state = ?");
            args.add(state);
        }
        String base = "FROM refund_order r LEFT JOIN recharge_order o ON o.id = r.order_id WHERE " + String.join(" AND ", where);
        pageSize = Math.min(Math.max(pageSize, 1), 100);
        Integer total = jdbc.queryForObject("SELECT count(*) " + base, Integer.class, args.toArray());
        var params = new java.util.ArrayList<Object>(args);
        params.add(pageSize);
        params.add((long) Math.max(pageNo - 1, 0) * pageSize);
        var rows = jdbc.queryForList(
                "SELECT r.id, r.order_id, r.refund_request_key, r.amount_cents, r.channel_refund_id, r.channel_state, "
                        + "r.point_reversal_state, r.reserved_base, r.reserved_bonus, r.operator_id, r.reason, r.create_time, "
                        + "o.order_no, o.user_id " + base + " ORDER BY r.create_time DESC, r.id DESC LIMIT ? OFFSET ?",
                params.toArray());
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        var list = rows.stream().map(r -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("orderId", String.valueOf(((Number) r.get("order_id")).longValue()));
            item.put("refundRequestKey", r.get("refund_request_key"));
            item.put("refundNo", String.valueOf(r.get("id")));
            item.put("orderNo", r.get("order_no"));
            item.put("userId", r.get("user_id") == null ? null : String.valueOf(r.get("user_id")));
            item.put("reason", r.get("reason"));
            item.put("amountCents", ((Number) r.get("amount_cents")).longValue());
            item.put("channelRefundId", r.get("channel_refund_id") == null ? "" : r.get("channel_refund_id"));
            item.put("channelState", r.get("channel_state"));
            item.put("pointReversalState", r.get("point_reversal_state"));
            item.put("reservedBase", ((Number) r.get("reserved_base")).longValue());
            item.put("reservedBonus", ((Number) r.get("reserved_bonus")).longValue());
            item.put("operatorId", r.get("operator_id"));
            item.put("createdAt", formatter.format(((java.sql.Timestamp) r.get("create_time")).toInstant()));
            return item;
        }).toList();
        return success(new PageResult<>(list, total == null ? 0 : total.longValue()));
    }

    @GetMapping("/refund-orders/{refundOrderId}")
    @Operation(summary = "渠道退款单详情：含预留/冲正进度")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.PAYMENT_RECONCILE + "')")
    public CommonResult<Map<String, Object>> getRefundOrder(@PathVariable("refundOrderId") String refundOrderId) {
        var rows = jdbc.queryForList(
                "SELECT id, order_id, refund_request_key, amount_cents, channel_refund_id, channel_state, "
                        + "point_reversal_state, reserved_base, reserved_bonus, operator_id, reason, create_time "
                        + "FROM refund_order WHERE id = ? AND deleted = FALSE", Long.parseLong(refundOrderId));
        if (rows.isEmpty()) {
            return success(null);
        }
        var r = rows.get(0);
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
        item.put("orderId", String.valueOf(((Number) r.get("order_id")).longValue()));
        item.put("refundRequestKey", r.get("refund_request_key"));
        item.put("amountCents", ((Number) r.get("amount_cents")).longValue());
        item.put("channelRefundId", r.get("channel_refund_id") == null ? "" : r.get("channel_refund_id"));
        item.put("channelState", r.get("channel_state"));
        item.put("pointReversalState", r.get("point_reversal_state"));
        item.put("reservedBase", ((Number) r.get("reserved_base")).longValue());
        item.put("reservedBonus", ((Number) r.get("reserved_bonus")).longValue());
        item.put("operatorId", r.get("operator_id"));
        item.put("reason", r.get("reason") == null ? "" : r.get("reason"));
        item.put("createdAt", formatter.format(((java.sql.Timestamp) r.get("create_time")).toInstant()));
        return success(item);
    }

    @PostMapping("/recharge-orders/{orderId}/reconciliation-actions")
    @Operation(summary = "对账动作（主动查单、补偿到账、死信处置）：受控命令，全审计")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.PAYMENT_RECONCILE + "')")
    public CommonResult<String> reconciliationAction(@PathVariable("orderId") String orderId,
                                                     @RequestBody Map<String, Object> command) {
        String action = String.valueOf(command.getOrDefault("action", "QUERY_CHANNEL"));
        var order = paymentService.getOrderById(Long.parseLong(orderId));
        if (order.isEmpty()) {
            return success("ORDER_NOT_FOUND");
        }
        // V1 支持主动查单收口；补偿到账/死信处置在 P8C 渠道适配落地后开放
        if (!"QUERY_CHANNEL".equals(action)) {
            return success("ACTION_NOT_SUPPORTED");
        }
        return success(paymentService.reconcile(order.get().orderNo()));
    }

}
