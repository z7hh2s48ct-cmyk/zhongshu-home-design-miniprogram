package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "小程序 - 投稿 Response VO")
@Data
public class AppSubmissionRespVO {

    @Schema(description = "投稿编号")
    private String submissionId;

    @Schema(description = "项目编号")
    private String projectId;

    @Schema(description = "投稿用户编号")
    private String userId;

    @Schema(description = "结果版本编号")
    private String resultVersionId;

    @Schema(description = "投稿状态：DRAFT/VALIDATED/SUBMITTED/IN_REVIEW/CHANGES_REQUESTED/RESUBMITTED/APPROVED/REJECTED")
    private String status;

    @Schema(description = "发布状态：PRIVATE/PUBLISH_PENDING/PUBLISHED/WITHDRAWN")
    private String publicationStatus;

    @Schema(description = "当前审核轮次")
    private Integer currentRound;

    @Schema(description = "最近一次审核意见")
    private String reviewComment;

    @Schema(description = "提交时间")
    private LocalDateTime submittedAt;

    @Schema(description = "当前对象允许执行的动作集合（如 RESUBMIT）")
    private List<String> allowedActions;

}
