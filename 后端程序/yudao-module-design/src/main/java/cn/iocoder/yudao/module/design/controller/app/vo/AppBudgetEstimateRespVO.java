package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "小程序 - 预算测算 Response VO（页面 14；P0 仅为参考区间）")
@Data
public class AppBudgetEstimateRespVO {

    @Schema(description = "测算编号")
    private String estimateId;

    @Schema(description = "项目编号")
    private String projectId;

    @Schema(description = "预算规则版本")
    private String ruleVersion;

    @Schema(description = "输入快照（地区、结构、材料等级等）")
    private Map<String, Object> inputSnapshot;

    @Schema(description = "总价区间下限（元）")
    private Long totalMinCents;

    @Schema(description = "总价区间上限（元）")
    private Long totalMaxCents;

    @Schema(description = "费用构成明细")
    private List<Map<String, Object>> breakdown;

    @Schema(description = "免责声明文本")
    private String disclaimer;

    @Schema(description = "测算时间")
    private LocalDateTime createdAt;

}
