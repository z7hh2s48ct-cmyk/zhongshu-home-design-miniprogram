package cn.iocoder.yudao.module.aiorchestration.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "小程序 - AI 任务 Response VO（页面 06/09 生成中、07/10 选择）")
@Data
public class AppAiJobRespVO {

    @Schema(description = "任务编号")
    private String jobId;

    @Schema(description = "项目编号")
    private String projectId;

    @Schema(description = "阶段：FLAT / ELEVATION")
    private String phase;

    @Schema(description = "任务状态：CREATED/QUEUED/RUNNING/VALIDATING/SETTLING/SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED/CANCELLED/CANCEL_REQUESTED")
    private String status;

    @Schema(description = "请求数量 N")
    private Integer requestedCount;

    @Schema(description = "已接受的有效结果数")
    private Integer acceptedCount;

    @Schema(description = "单价（设计点）")
    private Integer unitPointCost;

    @Schema(description = "总扣点（快照）")
    private Integer totalPointCost;

    @Schema(description = "进度百分比 0~100")
    private Integer progress;

    @Schema(description = "预计完成时间")
    private LocalDateTime estimatedFinishedAt;

    @Schema(description = "候选列表（任务终态后可见，仅 ACCEPTED 槽位）")
    private List<Candidate> candidates;

    @Schema(description = "当前对象允许执行的动作集合（如 CANCEL、SELECT）")
    private List<String> allowedActions;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "终态时间")
    private LocalDateTime finishedAt;

    @Schema(description = "候选槽位")
    @Data
    public static class Candidate {

        @Schema(description = "候选编号")
        private String candidateId;

        @Schema(description = "槽位号")
        private Integer slotNo;

        @Schema(description = "资产编号（隔离校验通过后的正式资产）")
        private String assetId;

    }

}
