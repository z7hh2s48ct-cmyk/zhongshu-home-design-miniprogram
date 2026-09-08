package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "小程序 - 申请上传凭证 Request VO")
@Data
public class AppAssetUploadTicketReqVO {

    @Schema(description = "资产用途类型：USER_SKETCH / CASE_IMAGE / CASE_PDF 等", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "资产类型不能为空")
    private String assetType;

    @Schema(description = "声明的 MIME 类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "image/jpeg")
    @NotBlank(message = "MIME 类型不能为空")
    private String mimeType;

    @Schema(description = "声明的大小（字节）；服务端按资产类型上限强制校验", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "文件大小不能为空")
    private Long sizeBytes;

    @Schema(description = "声明内容的 SHA-256", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "内容哈希不能为空")
    private String sha256;

}
