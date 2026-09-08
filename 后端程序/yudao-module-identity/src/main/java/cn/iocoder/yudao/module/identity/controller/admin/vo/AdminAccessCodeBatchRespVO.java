package cn.iocoder.yudao.module.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 授权码批次 Response VO")
@Data
public class AdminAccessCodeBatchRespVO {

    @Schema(description = "批次编号")
    private Long id;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "交付方式：INLINE / TICKET")
    private String deliveryMode;

    @Schema(description = "已交付明文的码数量（secretExposedAt 已设置的码数）")
    private Integer exposedCount;

    @Schema(description = "过期时间")
    private LocalDateTime expiresAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "完整码列表：仅 INLINE 创建响应内返回一次；TICKET 模式与已交付后永远为空")
    private List<String> oneTimeCodes;

}
