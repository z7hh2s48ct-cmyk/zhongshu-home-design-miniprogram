package cn.iocoder.yudao.module.commerce.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 设计点流水项 Response VO")
@Data
public class AppPointLedgerItemRespVO {

    @Schema(description = "流水编号")
    private String ledgerId;

    @Schema(description = "流水所属用户编号，字符串避免雪花 ID 精度损失")
    private String userId;

    @Schema(description = "流水类型：RECHARGE_BASE_CREDIT/RECHARGE_BONUS_CREDIT/FLAT_GENERATION_DEBIT/ELEVATION_GENERATION_DEBIT/TASK_SETTLEMENT_REFUND/MANUAL_CREDIT/MANUAL_DEBIT")
    private String type;

    @Schema(description = "变化值（正入负出）")
    private Integer delta;

    @Schema(description = "变化后总余额")
    private Integer balanceAfter;

    @Schema(description = "关联业务类型")
    private String bizType;

    @Schema(description = "关联业务编号")
    private String bizId;

    @Schema(description = "时间")
    private LocalDateTime createdAt;

}
