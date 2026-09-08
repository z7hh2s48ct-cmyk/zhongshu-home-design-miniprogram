package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "小程序 - 选定候选 Request VO（平面/立面共用）")
@Data
public class AppSelectionCreateReqVO {

    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "任务编号不能为空")
    private String jobId;

    @Schema(description = "候选编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "候选编号不能为空")
    private String candidateId;

}
