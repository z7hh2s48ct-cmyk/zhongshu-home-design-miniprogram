package cn.iocoder.yudao.module.design.controller.admin.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Internal administrator projections. Never return these from an App controller. */
public final class AdminBudgetRevisionVO {
    private AdminBudgetRevisionVO() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String budgetId, String projectId, String userId, String resultVersionId,
                          String projectName, String schemeName, String regionCode, String regionName,
                          String model, boolean saved, int currentVersion, String revisionId, int revisionNo,
                          String completeness, long pricedSubtotalCents, Long totalCents, String createdAt) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(String budgetId, String projectId, String userId, String resultVersionId,
                         String projectName, String schemeName, String regionCode, String regionName,
                         String model, boolean saved, int currentVersion, String revisionId, int revisionNo,
                         String completeness, long pricedSubtotalCents, Long totalCents, String createdAt,
                         boolean readOnly, Map<String, Long> categoryTotals, Map<String, Object> inputSnapshot,
                         List<String> missingFields, List<String> warnings, List<Line> lines) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Line(String lineId, String lineKey, String itemId, String optionId, String priceVersionId,
                       String itemCode, String publicName, String optionLabel, String category, String source,
                       String selectionGroup, String unit, String quantity, Long unitPriceCents, Long amountCents,
                       String status, String freeReason, String excludedReason, String internalNote,
                       String originalQuantity, Long originalUnitPriceCents, String quantitySource,
                       String priceSource, String sourceReference) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RevisionRef(String revisionId, int revisionNo, String actorType, String actorId,
                              String changeReason, String completeness, long pricedSubtotalCents,
                              Long totalCents, String createdAt) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TemplateResult(String itemId, String code, String name, boolean enabled, boolean publicSelectable) {}
}
