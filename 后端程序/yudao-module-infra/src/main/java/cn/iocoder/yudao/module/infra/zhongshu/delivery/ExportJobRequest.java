package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.Builder;
import lombok.Value;

/**
 * 异步导出任务创建请求
 */
@Value
@Builder
public class ExportJobRequest {

    /** 导出类型，如 ACCESS_CODE_LEDGER、POINT_LEDGER、AUDIT_EVENTS */
    String jobType;

    /** 发起人类型：ADMIN / USER */
    String requesterType;

    Long requesterUserId;

    /** 过滤条件快照（审计用） */
    java.util.Map<String, Object> filterSnapshot;

    /** 字段范围快照（最小必要） */
    java.util.Map<String, Object> fieldScope;

    /** 文件与下载票据有效期（秒） */
    Integer ttlSeconds;

}
