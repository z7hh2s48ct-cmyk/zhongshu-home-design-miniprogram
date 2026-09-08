package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "小程序 - 案例详情 Response VO（页面 03）")
@Data
public class AppCaseDetailRespVO {

    @Schema(description = "案例编号")
    private String caseId;

    @Schema(description = "案例名称")
    private String title;

    @Schema(description = "案例说明")
    private String description;

    @Schema(description = "来源：COMPANY / AI")
    private String sourceType;

    @Schema(description = "结构化参数：styleCode、floorCount、buildingArea、faceWidth、depth、rooms 等")
    private Map<String, Object> parameters;

    @Schema(description = "封面资产 ID")
    private String coverAssetId;

    @Schema(description = "各层平面资产 ID 列表（按层序）")
    private List<String> floorPlanAssetIds;

    @Schema(description = "立面资产 ID")
    private String elevationAssetId;

    @Schema(description = "PDF 资产 ID")
    private String pdfAssetId;

    @Schema(description = "当前发布版本号（乐观锁展示用）")
    private Long version;

    @Schema(description = "当前对象允许执行的动作集合")
    private List<String> allowedActions;

}
