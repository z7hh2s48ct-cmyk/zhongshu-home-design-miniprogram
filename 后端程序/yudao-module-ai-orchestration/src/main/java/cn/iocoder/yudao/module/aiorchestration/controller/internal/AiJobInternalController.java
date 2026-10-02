package cn.iocoder.yudao.module.aiorchestration.controller.internal;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimReqVO;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimRespVO;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalEventReqVO;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * AI Runtime 内部 API（架构 §7.4）
 *
 * 本控制器位于 controller.internal 包，不匹配底座 admin/app 前缀规则，自声明完整 /internal-api 路径。
 * P4B 阶段安全边界：生产部署须为 /internal-api/** 配置独立服务身份（mTLS/签名 + 时间戳重放窗口）。
 */
@Tag(name = "内部 - AI Runtime 任务合同")
@RestController
@RequestMapping("/internal-api/design/v1/ai-jobs")
// 底座安全链默认对未登记路径 401，internal 路径不持用户会话，必须显式放行安全链；
// 真实身份校验是方法内 HMAC 签名（未配置密钥即全拒），网关侧网络隔离由部署承担（§7.4）
@jakarta.annotation.security.PermitAll
public class AiJobInternalController {

    @Resource
    private AiJobOrchestrationService orchestrationService;

    @Resource
    private InternalSignatureVerifier signatureVerifier;

    @Resource
    private cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService settlementService;

    @org.springframework.beans.factory.annotation.Value("${zhongshu.ai.provider-code:apilio}")
    private String providerCode;

    /** 审查 C6：每个 internal 端点先验服务签名（HMAC+时间戳防重放），未配置密钥即全拒。
     *  body 字节由底座全局 CacheRequestBodyFilter 包装提供（请求可重复读）。 */
    private void requireSignature(HttpServletRequest request) {
        String timestamp = request.getHeader("X-ZS-Timestamp");
        String signature = request.getHeader("X-ZS-Signature");
        byte[] body;
        try {
            body = cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getBodyBytes(request);
        } catch (Exception e) {
            body = new byte[0];
        }
        if (!signatureVerifier.verify(timestamp, signature,
                request.getMethod(), request.getRequestURI(), body)) {
            throw new org.springframework.security.access.AccessDeniedException("内部签名校验失败");
        }
    }

    @PostMapping("/claims")
    @Operation(summary = "领取任务：FOR UPDATE SKIP LOCKED + 租约 + 递增 fencing token")
    public CommonResult<List<InternalClaimRespVO>> claimJobs(HttpServletRequest request,
                                                             @Valid @RequestBody InternalClaimReqVO reqVO) {
        requireSignature(request);
        List<InternalClaimRespVO> jobs = orchestrationService
                .claim(reqVO.getWorkerId(),
                        reqVO.getMaxJobs() == null ? 1 : reqVO.getMaxJobs(),
                        60, providerCode)
                .stream().map(job -> {
                    InternalClaimRespVO vo = new InternalClaimRespVO();
                    vo.setJobId(String.valueOf(job.jobId()));
                    vo.setAttemptNo(job.attemptNo());
                    vo.setFencingToken(job.fencingToken());
                    vo.setPhase(job.phase());
                    vo.setPayload(java.util.Map.of(
                            "requestedCount", job.requestedCount(),
                            "outputPrefix", job.outputPrefix()));
                    return vo;
                }).toList();
        return success(jobs);
    }

    @PostMapping("/{jobId}/lease-renewals")
    @Operation(summary = "续租：job_id + attempt_no + fencing_token，陈旧持有者被拒")
    public CommonResult<Boolean> renewLease(@PathVariable("jobId") String jobId,
                                            HttpServletRequest request,
                                            @Valid @RequestBody InternalEventReqVO reqVO) {
        requireSignature(request);
        return success(orchestrationService.renewLease(Long.parseLong(jobId),
                reqVO.getAttemptNo(), reqVO.getFencingToken(), 60));
    }

    @PostMapping("/{jobId}/prompt-calls")
    public CommonResult<String> reservePromptCall(@PathVariable("jobId") String jobId,HttpServletRequest request,
                                                 @RequestBody java.util.Map<String,Object> body) {
        requireSignature(request);
        return success(settlementService.reservePromptCall(Long.parseLong(jobId),((Number)body.get("attemptNo")).intValue(),((Number)body.get("fencingToken")).longValue()));
    }

    @PostMapping("/{jobId}/prompt-call-events")
    public CommonResult<Boolean> finishPromptCall(@PathVariable("jobId") String jobId,HttpServletRequest request,
                                                 @RequestBody java.util.Map<String,Object> body) {
        requireSignature(request);
        return success(settlementService.finishPromptCall(Long.parseLong(jobId),((Number)body.get("attemptNo")).intValue(),((Number)body.get("fencingToken")).longValue(),String.valueOf(body.get("outcome")),(String)body.get("callId"),(String)body.get("responseHash")));
    }

    @PostMapping("/{jobId}/progress-events")
    @Operation(summary = "进度回写")
    public CommonResult<Boolean> reportProgress(@PathVariable("jobId") String jobId,
                                                HttpServletRequest request,
                                                @Valid @RequestBody InternalEventReqVO reqVO) {
        requireSignature(request);
        orchestrationService.reportProgress(Long.parseLong(jobId),
                reqVO.getAttemptNo(), reqVO.getFencingToken(),
                reqVO.getProgress() == null ? 0 : reqVO.getProgress(), reqVO.getStage());
        return success(true);
    }

    @PostMapping("/{jobId}/result-events")
    @Operation(summary = "结果事件：先落 durable inbox 再进隔离；返回 QUARANTINED/DUPLICATE_EVENT/STALE_FENCING/SUPERSEDED/TERMINAL_IGNORED")
    public CommonResult<String> reportResult(@PathVariable("jobId") String jobId,
                                             HttpServletRequest request,
                                             @Valid @RequestBody InternalEventReqVO reqVO) {
        requireSignature(request);
        var outcome = orchestrationService.reportResult(Long.parseLong(jobId),
                reqVO.getAttemptNo(), reqVO.getFencingToken(),
                reqVO.getProviderCode(), reqVO.getSourceEventId(),
                reqVO.getCandidateSlotNo() == null ? 1 : reqVO.getCandidateSlotNo(),
                reqVO.getObjectKey(), reqVO.getSha256(),
                reqVO.getMimeType(), reqVO.getSizeBytes() == null ? 0 : reqVO.getSizeBytes());
        return success(outcome.name());
    }

    @PostMapping("/{jobId}/failure-events")
    @Operation(summary = "失败事件：记录并关闭尝试，任务回队")
    public CommonResult<Boolean> reportFailure(@PathVariable("jobId") String jobId,
                                               HttpServletRequest request,
                                               @Valid @RequestBody InternalEventReqVO reqVO) {
        requireSignature(request);
        return success(orchestrationService.reportFailure(Long.parseLong(jobId),
                reqVO.getAttemptNo(), reqVO.getFencingToken(),
                reqVO.getProviderCode(), reqVO.getSourceEventId(),
                reqVO.getErrorCode(), reqVO.getMessage()));
    }

    @PostMapping("/{jobId}/completion-events")
    public CommonResult<Boolean> complete(@PathVariable("jobId") String jobId, HttpServletRequest request,
            @Valid @RequestBody InternalEventReqVO event) {
        requireSignature(request);
        return success(orchestrationService.completeAttempt(Long.parseLong(jobId),event.getAttemptNo(),event.getFencingToken()));
    }

}
