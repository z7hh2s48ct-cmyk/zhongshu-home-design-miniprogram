package cn.iocoder.yudao.module.aiorchestration.controller.internal.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "内部 - Runtime 领取任务 Response VO")
@Data
public class InternalClaimRespVO {

    @Schema(description = "任务编号")
    private String jobId;

    @Schema(description = "尝试序号（从 1 递增）")
    private Integer attemptNo;

    @Schema(description = "fencing token：仅用于拒绝陈旧回写者，不参与业务结果去重")
    private Long fencingToken;

    @Schema(description = "租约到期时间")
    private LocalDateTime leaseExpiresAt;

    @Schema(description = "阶段：FLAT / ELEVATION")
    private String phase;

    @Schema(description = "任务参数（提示词、数量、风格、引用资产与授权快照等）")
    private Map<String, Object> payload;

    @Schema(description = "本次领取的全部任务")
    private List<InternalClaimRespVO> jobs;

}
