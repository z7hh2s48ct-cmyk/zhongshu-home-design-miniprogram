package cn.iocoder.yudao.module.design.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 案例 Response VO（页面 03 户型库管理）")
@Data
public class AdminCaseRespVO {

    @Schema(description = "案例编号")
    private String caseId;

    @Schema(description = "名称")
    private String title;

    @Schema(description = "来源：COMPANY / AI")
    private String sourceType;

    @Schema(description = "风格编码")
    private String styleCode;

    @Schema(description = "层数")
    private Integer floorCount;

    @Schema(description = "建筑面积（㎡）")
    private Integer buildingArea;

    @Schema(description = "发布状态：DRAFT / PUBLISHED / OFFLINE")
    private String publicationStatus;

    @Schema(description = "当前版本（乐观锁；过期更新返回 STATE_VERSION_CONFLICT）")
    private Long version;

    @Schema(description = "标签")
    private List<String> tags;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

}
