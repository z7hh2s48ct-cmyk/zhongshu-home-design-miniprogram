package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.Map;

@Schema(description = "小程序 - 方案调整请求 Request VO（页面 11 调整；生成新的不可变结果版本）")
@Data
public class AppRevisionRequestReqVO {

    @jakarta.validation.constraints.NotNull(message = "请先确认本次生成价格")
    private cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation priceConfirmation;

    @Schema(description = "调整原因（留档，供审计与后续版本对照）")
    private String reason;

    @Schema(description = "调整后的立面配置（风格/屋顶/材质/颜色等），并入新一轮需求快照")
    private Map<String, Object> configUpdates;

    @Schema(description = "重新生成的候选数量 N（按立面单价与数量冻结计价快照）", example = "2")
    @Min(value = 1, message = "生成数量至少 1")
    @Max(value = 4, message = "生成数量最多 4")
    private Integer count = 2;

}
