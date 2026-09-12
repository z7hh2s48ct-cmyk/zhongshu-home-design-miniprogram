package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** Explicit public projection: no raw requirement/config JSON or professional pricing quantities. */
@Schema(description = "小程序 - 项目预算导入参数，缺失字段不虚构默认值")
public record AppBudgetInputsRespVO(String projectId, String resultVersionId, List<String> requirementSnapshotIds,
                                   Map<String, Object> importedValues, Map<String, String> sources,
                                   List<String> missingFields, List<String> warnings) { }
