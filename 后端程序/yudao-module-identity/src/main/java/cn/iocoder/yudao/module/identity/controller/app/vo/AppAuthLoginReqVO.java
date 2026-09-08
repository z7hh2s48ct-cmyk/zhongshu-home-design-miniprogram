package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "小程序 - 微信登录 Request VO")
@Data
public class AppAuthLoginReqVO {

    @Schema(description = "wx.login 临时登录凭证 code", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "微信登录 code 不能为空")
    private String code;

}
