package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "小程序 - 创建平面/立面任务 Request VO（按阶段区分端点）")
@Data
public class AppDesignJobCreateReqVO {

    @Schema(description = "请求生成的候选数量 N（计价按数量与单价冻结快照）", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "生成数量不能为空")
    @Min(value = 1, message = "生成数量至少 1")
    @Max(value = 4, message = "生成数量最多 4")
    private Integer count;

    @Schema(description = "用户确认的计价规则编号与版本；旧客户端可缺省，新客户端必须随确认提交")
    private cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation priceConfirmation;

    @Schema(description = "风格编码（立面必填）")
    private String styleCode;

    @Schema(description = "屋顶形式（立面可选）")
    private String roofType;

    @Schema(description = "材质（立面可选）")
    private String material;

    @Schema(description = "颜色（立面可选）")
    private String color;

    @Schema(description = "补充提示词")
    private String prompt;

}
