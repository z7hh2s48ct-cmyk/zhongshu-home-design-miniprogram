package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "小程序 - 访问授权（使用权）Response VO")
@Data
public class AppAccessGrantRespVO {

    @Schema(description = "授权状态：ACTIVE / REVOKED", example = "ACTIVE")
    private String status;

    @Schema(description = "授权生效时间")
    private LocalDateTime grantedAt;

    @Schema(description = "授权失效时间，null 表示长期有效")
    private LocalDateTime expiresAt;

    @Schema(description = "当前对象允许执行的动作集合，客户端不得自行推导")
    private List<String> allowedActions;

}
