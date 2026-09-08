package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 一次性下载票据 Response VO")
@Data
public class AppAssetDownloadTicketRespVO {

    @Schema(description = "票据编号")
    private String ticketId;

    @Schema(description = "短期签名下载 URL（一次性消费）")
    private String downloadUrl;

    @Schema(description = "过期时间")
    private LocalDateTime expiresAt;

}
