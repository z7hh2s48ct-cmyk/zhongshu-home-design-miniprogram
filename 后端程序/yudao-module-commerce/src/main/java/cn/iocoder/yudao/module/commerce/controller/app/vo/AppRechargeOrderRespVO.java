package cn.iocoder.yudao.module.commerce.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "小程序 - 充值订单 Response VO（页面 18：支付状态与到账状态分开返回）")
@Data
public class AppRechargeOrderRespVO {

    @Schema(description = "订单编号")
    private String orderId;

    @Schema(description = "方案编号（快照）")
    private String planId;

    @Schema(description = "支付金额（分）")
    private Long amountCents;

    @Schema(description = "基础点（快照）")
    private Integer basePoints;

    @Schema(description = "赠送点（快照）")
    private Integer bonusPoints;

    @Schema(description = "支付状态：CREATED/PENDING/SUCCEEDED/CLOSED/FAILED/UNKNOWN")
    private String paymentState;

    @Schema(description = "到账履约状态：NOT_READY/PENDING/CREDITED/FAILED")
    private String fulfillmentState;

    @Schema(description = "最近退款事实；无退款时为空，不以订单关闭推断退款成功")
    private cn.iocoder.yudao.module.commerce.payment.RechargePaymentService.RefundSummary refund;

    @Schema(description = "客户端成功页必须查询服务端，不信任页面 URL 参数；未 CREDITED 前展示“到账处理中”")
    private List<String> allowedActions;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "支付成功时间")
    private LocalDateTime paidAt;

    /**
     * T13-24：小程序 wx.requestPayment 拉起参数（JSAPI 六参数：appId/timeStamp/nonceStr/package/signType/paySign）。
     * 仅当订单归属当前 userId 且 paymentState IN ('CREATED','PENDING') 时下发；
     * 已支付/已关闭/已失败订单为 null（前端引导查看充值记录，不重复拉起支付）。
     * Stub 通道返回 {prepayId, amount}；WechatPaymentAdapter 返回已签名的 JSAPI 六参数。
     */
    @Schema(description = "wx.requestPayment 拉起参数；仅 CREATED/PENDING 状态下下发")
    private Map<String, String> payParams;

}
