package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "小程序 - 授权码兑换 Request VO")
@Data
public class AppAccessCodeRedeemReqVO {

    @Schema(description = "授权码完整明文（仅限单次兑换）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "授权码不能为空")
    private String accessCode;

}
