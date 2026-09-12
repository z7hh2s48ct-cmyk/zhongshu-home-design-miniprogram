package cn.iocoder.yudao.module.aiorchestration.controller.app;

import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.controller.app.vo.AppAiJobRespVO;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - AI 任务（页面 06/09）")
@RestController
@RequestMapping("/design/v1/ai-jobs")
@PermitAll // 自有 user_session Bearer 校验在方法内强制执行（requireAccountId）
public class AppAiJobController {

    @Resource
    private AiJobOrchestrationService orchestrationService;

    @Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identitySessionPort;

    @GetMapping("/{jobId}")
    @Operation(summary = "任务状态/进度/阶段/候选（仅本人可见；对象级权限）")
    public CommonResult<AppAiJobRespVO> getJob(@PathVariable("jobId") String jobId,
                                               @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var job = orchestrationService.getJobForUser(Long.parseLong(jobId), userId)
                .orElseThrow(() -> exception(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN));
        return success(toVo(job));
    }

    @PostMapping("/{jobId}/cancellation-requests")
    @Operation(summary = "请求取消：cancelSeq 单调序裁决与结果竞态；不得只凭离开页面退款")
    public CommonResult<AppAiJobRespVO> requestCancellation(@PathVariable("jobId") String jobId,
                                                            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        orchestrationService.requestCancellationForUser(Long.parseLong(jobId), userId);
        var job = orchestrationService.getJobForUser(Long.parseLong(jobId), userId).orElseThrow();
        return success(toVo(job));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    public static AppAiJobRespVO toVo(AiJobOrchestrationService.JobSnapshot job) {
        AppAiJobRespVO vo = new AppAiJobRespVO();
        vo.setJobId(String.valueOf(job.jobId()));
        vo.setProjectId(job.projectRef());
        vo.setUserId(String.valueOf(job.userId()));
        vo.setPhase(job.phase());
        vo.setStatus(job.status());
        vo.setRequestedCount(job.requestedCount());
        vo.setAcceptedCount(job.acceptedCount());
        vo.setProgress(job.progress());
        vo.setUnitPointCost(job.unitPointCost());
        vo.setTotalPointCost(job.totalPointCost());
        vo.setRefundedPointCost(job.refundedPointCost());
        vo.setNetPointCost(job.totalPointCost() == null || job.refundedPointCost() == null ? null
                : job.totalPointCost() - job.refundedPointCost());
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        vo.setCreatedAt(job.createdAt() == null ? null : java.time.LocalDateTime.ofInstant(job.createdAt(), zone));
        vo.setFinishedAt(job.finishedAt() == null ? null : java.time.LocalDateTime.ofInstant(job.finishedAt(), zone));
        vo.setAllowedActions(java.util.List.of("CREATED", "QUEUED", "RUNNING", "VALIDATING").contains(job.status())
                ? java.util.List.of("CANCEL") : java.util.List.of());
        return vo;
    }

}
