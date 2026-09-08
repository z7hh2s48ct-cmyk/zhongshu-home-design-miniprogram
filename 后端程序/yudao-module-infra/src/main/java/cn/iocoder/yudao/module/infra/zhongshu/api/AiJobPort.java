package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.List;
import java.util.Optional;

/**
 * AI 任务端口（ai-orchestration 实现）：供 design 模块创建任务与读取候选，
 * 业务模块零互依。
 */
public interface AiJobPort {

    /** 创建平面任务并同事务扣点（幂等键 = user + Idempotency-Key）；返回 jobId */
    long createFlatJob(long userId, int count, String idempotencyKey, String projectRef);

    /** 创建立面任务并同事务扣点；须引用已选平面任务（projectRef 携带项目上下文） */
    long createElevationJob(long userId, int count, String idempotencyKey, String projectRef);

    Optional<JobView> getJob(long jobId);

    /** 已接受的有效候选（仅 ACCEPTED） */
    List<CandidateView> listAcceptedResults(long jobId);

    record JobView(long jobId, long userId, String status, int requestedCount,
                   int acceptedCount, int progress) {
    }

    record CandidateView(long resultId, int slotNo, String objectKey, String sha256, String mimeType) {
    }

}
