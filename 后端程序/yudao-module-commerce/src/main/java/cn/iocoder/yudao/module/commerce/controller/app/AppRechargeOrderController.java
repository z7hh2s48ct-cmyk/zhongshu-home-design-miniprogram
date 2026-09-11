package cn.iocoder.yudao.module.commerce.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.commerce.controller.app.vo.AppRechargeOrderRespVO;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 充值订单（页面 17/18）。
 *
 * 支付状态与到账状态分两套下发：渠道支付成功不等于设计点已到账，
 * 成功页必须以服务端的 paymentState + fulfillmentState 为准，不信任页面跳转参数。
 */
@Tag(name = "小程序 - 充值订单（页面 17/18）")
@RestController
@RequestMapping("/design/v1/recharge-orders")
@PermitAll
public class AppRechargeOrderController {

    @Resource
    private RechargePaymentService rechargePaymentService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping
    @Operation(summary = "创建充值订单并拉起支付：冻结方案快照；幂等键 = user_id + Idempotency-Key")
    public CommonResult<AppRechargeOrderRespVO> createOrder(
            @NotNull(message = "方案不能为空") @RequestParam("planId") String planId,
            @Parameter(description = "幂等键")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // T13-22：openid 从会话上下文取，禁止从客户端请求参数取（防止 openid 与 userId 不匹配的越权支付）
        IdentitySessionPort.SessionContext session = requireSession(authorization);
        long userId = session.accountId();
        var order = rechargePaymentService.createOrder(userId, Long.parseLong(planId), idempotencyKey, session.openid());
        // 建单后按 orderId 回读，拿到与列表/详情一致的完整读模型
        return success(rechargePaymentService.getOrderDetail(userId, order.orderId())
                .map(this::toVo)
                .orElseThrow(() -> new IllegalStateException("订单创建后回读失败: " + order.orderId())));
    }

    @GetMapping
    @Operation(summary = "我的充值订单列表")
    public CommonResult<PageResult<AppRechargeOrderRespVO>> getOrderPage(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        PageResult<AppRechargeOrderRespVO> result = new PageResult<>();
        result.setTotal(rechargePaymentService.countOrders(userId));
        result.setList(rechargePaymentService.listOrders(userId, pageNo, pageSize).stream()
                .map(this::toVo).toList());
        return success(result);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "订单详情：支付状态与到账状态分别查询；客户端成功页以此为准")
    public CommonResult<AppRechargeOrderRespVO> getOrder(
            @PathVariable("orderId") String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        // 按 (orderId, userId) 查询而非查完再比对，避免越权探测他人订单是否存在
        return success(rechargePaymentService.getOrderDetail(userId, Long.parseLong(orderId))
                .map(this::toVo)
                .orElseThrow(() -> new AccessDeniedException("订单不存在或无权访问")));
    }

    /**
     * T13-24：独立领取 payParams（恢复流程）。
     * 场景：用户取消支付 / 签名失效 / 网络错误后，前端重新领取 payParams 继续支付，
     * 禁止重复建单（同 orderNo 幂等）。仅当 paymentState IN ('CREATED','PENDING') 时返回；
     * 已支付/已关闭/已失败返回 409（前端引导查看充值记录）。
     */
    @GetMapping("/{orderId}/pay-params")
    @Operation(summary = "重新领取支付参数（恢复流程）；仅 CREATED/PENDING 状态可用")
    public CommonResult<Map<String, String>> getPayParams(
            @PathVariable("orderId") String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return rechargePaymentService.getPayParams(userId, Long.parseLong(orderId))
                .map(params -> success(params))
                .orElseThrow(() -> new IllegalStateException("订单不可支付（已支付/已关闭/已失败或无权访问）"));
    }

    private long requireAccountId(String authorization) {
        return requireSession(authorization).accountId();
    }

    /**
     * T13-22：提取会话上下文（包含 accountId + appid + openid + restricted）。
     * createOrder 需要 openid 传给支付渠道（JSAPI 支付必需）；其余端点仅需 accountId，走 {@link #requireAccountId}。
     */
    private IdentitySessionPort.SessionContext requireSession(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token);
    }

    private AppRechargeOrderRespVO toVo(RechargePaymentService.OrderDetail order) {
        AppRechargeOrderRespVO vo = new AppRechargeOrderRespVO();
        vo.setOrderId(String.valueOf(order.orderId()));
        vo.setPlanId(String.valueOf(order.planId()));
        vo.setAmountCents(order.amountCents());
        vo.setBasePoints((int) order.basePoints());
        vo.setBonusPoints((int) order.bonusPoints());
        vo.setPaymentState(order.paymentState());
        vo.setFulfillmentState(order.fulfillmentState());
        vo.setRefund(order.refund());
        vo.setCreatedAt(order.createdAt() == null ? null
                : LocalDateTime.ofInstant(order.createdAt(), ZoneId.of("Asia/Shanghai")));
        vo.setPaidAt(order.paidAt() == null ? null
                : LocalDateTime.ofInstant(order.paidAt(), ZoneId.of("Asia/Shanghai")));
        vo.setAllowedActions(allowedActions(order));
        // T13-24：payParams 仅当 paymentState IN ('CREATED','PENDING') 时下发（已支付/已关闭不下发，防止重复拉起）
        if ("CREATED".equals(order.paymentState()) || "PENDING".equals(order.paymentState())) {
            vo.setPayParams(order.prepayParams());
        }
        return vo;
    }

    private List<String> allowedActions(RechargePaymentService.OrderDetail order) {
        if ("CREATED".equals(order.paymentState()) || "PENDING".equals(order.paymentState())) {
            return List.of("PAY", "POLL");
        }
        if ("SUCCEEDED".equals(order.paymentState()) && !"CREDITED".equals(order.fulfillmentState())) {
            return List.of("POLL"); // 支付已成功、到账未完成：客户端继续轮询，不要重复下单
        }
        return List.of("VIEW");
    }

}
