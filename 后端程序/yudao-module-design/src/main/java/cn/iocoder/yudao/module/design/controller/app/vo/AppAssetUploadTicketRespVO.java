package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "小程序 - 上传凭证 Response VO")
@Data
public class AppAssetUploadTicketRespVO {

    @Schema(description = "资产编号")
    private String assetId;

    @Schema(description = "私有桶上传地址（受限前缀，短期有效）")
    private String uploadUrl;

    @Schema(description = "上传所需请求头（含签名）")
    private Map<String, String> headers;

    @Schema(description = "凭证过期时间")
    private LocalDateTime expiresAt;

}
