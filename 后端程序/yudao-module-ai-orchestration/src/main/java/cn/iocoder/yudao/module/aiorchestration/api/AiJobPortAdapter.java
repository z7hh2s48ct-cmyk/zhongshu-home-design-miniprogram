package cn.iocoder.yudao.module.aiorchestration.api;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * AiJobPort 的 ai-orchestration 实现（委托 P4B/P4C 服务）
 */
@Component
public class AiJobPortAdapter implements AiJobPort {

    private final AiJobSettlementService settlementService;

    private final AiJobOrchestrationService orchestrationService;

    public AiJobPortAdapter(AiJobSettlementService settlementService,
                            AiJobOrchestrationService orchestrationService) {
        this.settlementService = settlementService;
        this.orchestrationService = orchestrationService;
    }

    @Override
    public long createFlatJob(long userId, int count, String idempotencyKey, String projectRef) {
        return settlementService.createJobWithCharge(userId, "FLAT", count, idempotencyKey, projectRef);
    }

    @Override
    public long createElevationJob(long userId, int count, String idempotencyKey, String projectRef) {
        return settlementService.createJobWithCharge(userId, "ELEVATION", count, idempotencyKey, projectRef);
    }

    @Override
    public Optional<JobView> getJob(long jobId) {
        return orchestrationService.getJob(jobId).map(job -> new JobView(
                job.jobId(), job.userId(), job.status(), job.requestedCount(),
                job.acceptedCount(), job.progress(), job.phase(), job.projectRef()));
    }

    @Override
    public boolean freezeInput(long jobId, java.util.Map<String,Object> snapshot) { return orchestrationService.freezeInput(jobId,snapshot); }

    @Override
    public long createFlatJob(long userId, int count, String key, String ref,
                              cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation price) {
        return settlementService.createJobWithCharge(userId, "FLAT", count, key, ref, price);
    }

    @Override
    public long createFlatJob(long userId, int count, String key, String ref,
                              cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation price,
                              cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options) {
        return settlementService.createJobWithCharge(userId, "FLAT", count, key, ref, price, options.resolution());
    }

    @Override
    public long createElevationJob(long userId, int count, String key, String ref,
                                   cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation price) {
        return settlementService.createJobWithCharge(userId, "ELEVATION", count, key, ref, price);
    }

    @Override
    public long createElevationJob(long userId, int count, String key, String ref,
                                   cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation price,
                                   cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options) {
        return settlementService.createJobWithCharge(userId, "ELEVATION", count, key, ref, price, options.resolution());
    }

    @Override
    public Optional<JobView> latestJob(long userId, long projectId, String phase) {
        return orchestrationService.latestJob(userId, projectId, phase).map(job -> new JobView(
                job.jobId(), job.userId(), job.status(), job.requestedCount(), job.acceptedCount(), job.progress(),job.phase(),job.projectRef()));
    }

    @Override
    public List<CandidateView> listAcceptedResults(long jobId) {
        return orchestrationService.listAcceptedResults(jobId).stream()
                .map(r -> new CandidateView(r.resultId(), r.slotNo(), r.objectKey(),
                        r.sha256(), r.mimeType()))
                .toList();
    }

}
