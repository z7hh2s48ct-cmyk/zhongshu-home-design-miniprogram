package cn.iocoder.yudao.module.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 授权码 Response VO（永远只显示掩码）")
@Data
public class AdminAccessCodeRespVO {

    @Schema(description = "授权码记录编号")
    private String id;

    @Schema(description = "掩码，如 ZS-****-****-7F3A")
    private String codeMask;

    @Schema(description = "批次编号")
    private String batchId;

    @Schema(description = "状态：ACTIVE / CONSUMED / DISABLED（过期由有效期计算）")
    private String status;

    @Schema(description = "是否存在可复制明文的加密制品（批次密文缺失时为 false）")
    private Boolean canCopy;

    @Schema(description = "发行时间")
    private LocalDateTime issuedAt;

    @Schema(description = "兑换时间")
    private LocalDateTime consumedAt;

    @Schema(description = "明文首次交付时间；完整批次票据仅允许在未交付前签发")
    private LocalDateTime secretExposedAt;

    @Schema(description = "有效期至，null 为长期")
    private LocalDateTime expiresAt;

    @Schema(description = "绑定用户编号，未绑定为空")
    private String boundUser;

}
