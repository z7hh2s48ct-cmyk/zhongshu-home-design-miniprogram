package cn.iocoder.yudao.module.aiorchestration.controller.internal.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Schema(description = "内部 - 租约续租/进度/结果/失败事件的公共校验字段 Request VO")
@Data
public class InternalEventReqVO {

    @Schema(description = "尝试序号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "attemptNo 不能为空")
    private Integer attemptNo;

    @Schema(description = "fencing token；旧持有者的回写被拒绝", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "fencingToken 不能为空")
    private Long fencingToken;

    @Schema(description = "Provider 编码（结果/失败事件必填）")
    private String providerCode;

    @Schema(description = "Provider 侧源事件 ID；无源 ID 时由 Adapter 确定性合成")
    private String sourceEventId;

    @Schema(description = "候选槽位号（结果事件必填）")
    private Integer candidateSlotNo;

    @Schema(description = "输出对象 key（必须位于本任务的隔离前缀内）")
    private String objectKey;

    @Schema(description = "输出内容 SHA-256")
    private String sha256;

    @Schema(description = "输出 MIME 类型")
    private String mimeType;

    @Schema(description = "输出大小（字节）")
    private Long sizeBytes;

    @Schema(description = "进度百分比（进度事件）")
    private Integer progress;

    @Schema(description = "阶段说明（进度事件）")
    private String stage;

    @Schema(description = "失败分类（失败事件）")
    private String errorCode;

    @Schema(description = "失败说明（脱敏后）")
    private String message;

    @Schema(description = "Provider 请求 ID")
    private String providerRequestId;

    @Schema(description = "扩展元数据")
    private Map<String, Object> metadata;

}
