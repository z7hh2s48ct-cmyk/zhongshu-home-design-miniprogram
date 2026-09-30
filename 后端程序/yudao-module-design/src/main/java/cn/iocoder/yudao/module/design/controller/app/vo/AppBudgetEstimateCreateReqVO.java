package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预算测算输入（页面 13）。
 * 字段白名单由服务端固定：只接受参与计价的四项，其余入参一律忽略，
 * 避免客户端塞入任意键污染可复算的输入快照。
 */
@Schema(description = "小程序 - 创建预算测算 Request VO（页面 13）")
@Data
public class AppBudgetEstimateCreateReqVO {

    @Schema(description = "地区编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "VAR1")
    @NotBlank(message = "地区编码不能为空")
    private String regionCode;

    @Schema(description = "结构类型：BRICK 砖混 / FRAME 框架 / STEEL 钢结构",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "BRICK")
    @NotBlank(message = "结构类型不能为空")
    private String structureType;

    @Schema(description = "材料等级：A 经济 / B 舒适 / C 品质",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "A")
    @NotBlank(message = "材料等级不能为空")
    private String materialGrade;

    @Schema(description = "建筑面积（㎡）", requiredMode = Schema.RequiredMode.REQUIRED, example = "168")
    @NotNull(message = "建筑面积不能为空")
    @Min(value = 1, message = "建筑面积必须大于 0")
    @Max(value = 10000, message = "建筑面积超出合理范围")
    private Integer buildingArea;

    @Schema(description = "关联的设计结果版本编号（可选，合同 §6.8）")
    private Long resultVersionId;

    @jakarta.validation.constraints.NotNull(message = "请先确认预算测算价格")
    private cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation usageConfirmation;

}
