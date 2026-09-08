package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "小程序 - 提交投稿 Request VO（页面 12）")
@Data
public class AppSubmissionCreateReqVO {

    @Schema(description = "投稿的结果版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "结果版本不能为空")
    private String resultVersionId;

    @Schema(description = "公开展示授权", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "公开展示授权不能为空")
    private Boolean publicDisplayGranted;

    @Schema(description = "允许作为生成参考授权（独立于公开展示，不随审核通过自动获得）")
    private Boolean generationReferenceGranted;

    @Schema(description = "补充说明")
    private String note;

}
