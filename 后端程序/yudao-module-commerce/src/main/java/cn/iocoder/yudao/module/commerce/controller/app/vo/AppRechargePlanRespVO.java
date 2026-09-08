package cn.iocoder.yudao.module.commerce.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 充值方案 Response VO（页面 17）")
@Data
public class AppRechargePlanRespVO {

    @Schema(description = "方案编号")
    private String planId;

    @Schema(description = "方案名称（页面展示文案，由后台配置）")
    private String name;

    @Schema(description = "金额（分）")
    private Long amountCents;

    @Schema(description = "基础设计点")
    private Integer basePoints;

    @Schema(description = "赠送设计点")
    private Integer bonusPoints;

    @Schema(description = "是否推荐")
    private Boolean recommended;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "当前对象允许执行的动作集合（如 PURCHASE）")
    private List<String> allowedActions;

}
