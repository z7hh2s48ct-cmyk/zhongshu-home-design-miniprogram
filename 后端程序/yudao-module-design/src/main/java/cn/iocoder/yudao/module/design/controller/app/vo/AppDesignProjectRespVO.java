package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 设计项目 Response VO（页面 04~11 主对象）")
@Data
public class AppDesignProjectRespVO {

    @Schema(description = "项目编号")
    private String projectId;

    @Schema(description = "来源：CASE_REFERENCE / SELF_UPLOAD")
    private String sourceType;

    @Schema(description = "参考案例编号（来源为案例时）")
    private String refCaseId;

    @Schema(description = "当前阶段：FLAT / ELEVATION")
    private String stage;

    @Schema(description = "已选平面候选编号（未选定时为空；立面任务必须引用它）")
    private String selectedFlatCandidateId;

    @Schema(description = "已选立面候选编号")
    private String selectedElevationCandidateId;

    @Schema(description = "最终结果版本编号")
    private String resultVersionId;

    @Schema(description = "本次创建/查询关联的 AI 任务编号（创建任务与调整请求的返回值，客户端据此轮询进度）")
    private String jobId;

    private String jobStatus;
    private Integer requestedCount;
    private String resumeAction;
    private String selectedFlatAssetId;

    @Schema(description = "项目状态：DRAFT / IN_PROGRESS / COMPLETED")
    private String status;

    @Schema(description = "候选列表（详情传入 jobId 时下发该任务已接受的候选，供选择页展示）")
    private List<AppDesignCandidateRespVO> candidates;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createdAt;

    @Schema(description = "当前对象允许执行的动作集合（如 CREATE_FLAT_JOB、CREATE_ELEVATION_JOB、SUBMIT）")
    private List<String> allowedActions;

}
