package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 发布校核 Response VO")
@Data
public class AppPublicationValidationRespVO {

    @Schema(description = "是否可提交")
    private Boolean valid;

    @Schema(description = "缺失/不通过字段明细")
    private List<String> missingFields;

    @Schema(description = "校核说明")
    private String message;

    @Schema(description = "校核快照编号（提交时引用）")
    private String validationSnapshotId;

    @Schema(description = "当前授权状态说明")
    private String rightsSummary;

}
