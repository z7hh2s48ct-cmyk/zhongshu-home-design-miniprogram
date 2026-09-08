package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Schema(description = "小程序 - 创建设计项目 Request VO（页面 04 参考户型 / 页面 05 自主设计）")
@Data
public class AppDesignProjectCreateReqVO {

    @Schema(description = "来源：CASE_REFERENCE（参考已发布案例）/ SELF_UPLOAD（自主设计）",
            example = "SELF_UPLOAD")
    @Pattern(regexp = "CASE_REFERENCE|SELF_UPLOAD", message = "来源只能是 CASE_REFERENCE 或 SELF_UPLOAD")
    private String sourceType = "SELF_UPLOAD";

    @Schema(description = "参考案例编号（sourceType=CASE_REFERENCE 时必填；仅可引用已发布且有生成参考授权的案例）")
    private String refCaseId;

    @Schema(description = "用户草图资产编号（自主设计上传草图时传；须已完成上传校验）")
    private String sketchAssetId;

    @Schema(description = "需求输入（层数、户型、面积、提示词等），冻结为需求快照 v1")
    private Map<String, Object> requirementInputs;

}
