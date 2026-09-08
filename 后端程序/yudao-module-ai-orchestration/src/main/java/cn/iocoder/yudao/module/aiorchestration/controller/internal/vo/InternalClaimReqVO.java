package cn.iocoder.yudao.module.aiorchestration.controller.internal.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "内部 - Runtime 领取任务 Request VO")
@Data
public class InternalClaimReqVO {

    @Schema(description = "Worker 实例标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "workerId 不能为空")
    private String workerId;

    @Schema(description = "本次最多领取任务数")
    private Integer maxJobs;

    @Schema(description = "只领取指定阶段（可选）")
    private String phase;

}
