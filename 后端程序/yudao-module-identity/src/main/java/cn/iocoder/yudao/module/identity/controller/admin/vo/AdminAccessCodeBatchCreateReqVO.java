package cn.iocoder.yudao.module.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 创建授权码批次 Request VO")
@Data
public class AdminAccessCodeBatchCreateReqVO {

    @Schema(description = "生成数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 10000, message = "单批次数量不能超过 10000")
    private Integer quantity;

    @Schema(description = "完整明文交付方式：INLINE / TICKET（互斥，创建时固定）", requiredMode = Schema.RequiredMode.REQUIRED, example = "TICKET")
    @NotBlank(message = "交付方式不能为空")
    @Pattern(regexp = "INLINE|TICKET", message = "交付方式只能是 INLINE 或 TICKET")
    private String deliveryMode;

    @Schema(description = "有效期天数")
    @Min(value = 1, message = "有效期至少 1 天")
    private Integer validityDays;

    @Schema(description = "用途备注")
    private String purposeNote;

}
