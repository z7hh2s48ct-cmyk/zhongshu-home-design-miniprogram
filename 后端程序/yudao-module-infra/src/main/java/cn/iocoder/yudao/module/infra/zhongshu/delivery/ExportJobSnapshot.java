package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * 异步导出任务快照
 */
@Value
@Builder
public class ExportJobSnapshot {

    public enum Status {PENDING, RUNNING, COMPLETED, FAILED, EXPIRED}

    Long jobId;

    String jobType;

    Status status;

    String requesterType;

    Long requesterUserId;

    Map<String, Object> filterSnapshot;

    String fileAssetId;

    String fileSha256;

    String error;

    Instant expiresAt;

    Instant createTime;

}
