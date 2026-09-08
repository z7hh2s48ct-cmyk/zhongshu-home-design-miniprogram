package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 协议同意事实 Response VO")
@Data
public class AppPrivacyConsentRespVO {

    @Schema(description = "协议类型：PRIVACY_POLICY / USER_AGREEMENT / AI_PROCESSING_NOTICE", example = "PRIVACY_POLICY")
    private String policyType;

    @Schema(description = "协议版本")
    private String version;

    @Schema(description = "同意时间")
    private LocalDateTime acceptedAt;

}
