package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 微信登录 Response VO")
@Data
public class AppAuthLoginRespVO {

    @Schema(description = "访问令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    private String accessToken;

    @Schema(description = "刷新令牌")
    private String refreshToken;

    @Schema(description = "访问令牌过期时间（ISO 8601 UTC）")
    private LocalDateTime expiresAt;

    @Schema(description = "未获准用户为受限会话：只允许查询准入状态、协议和兑换授权码")
    private Boolean restricted;

}
