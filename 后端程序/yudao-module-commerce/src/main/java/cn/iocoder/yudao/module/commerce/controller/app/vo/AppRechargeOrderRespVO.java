package cn.iocoder.yudao.module.commerce.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    @Schema(description = "客户端成功页必须查询服务端，不信任页面 URL 参数；未 CREDITED 前展示“到账处理中”")
    private List<String> allowedActions;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "支付成功时间")
    private LocalDateTime paidAt;

}
