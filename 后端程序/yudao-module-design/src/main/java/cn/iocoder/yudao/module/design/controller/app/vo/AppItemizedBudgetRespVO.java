package cn.iocoder.yudao.module.design.controller.app.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Public budget snapshot. Internal unit prices, quantities, rules and reasons are deliberately absent. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppItemizedBudgetRespVO(
        String budgetId, String revisionId, String model, String projectId, String projectName,
        String resultVersionId, String schemeName, String regionName, Map<String, Object> inputSummary,
        String completeness, long pricedSubtotalCents, Long totalCents, Map<String, Long> categoryTotals,
        List<Item> items, List<String> missingItems, List<String> warnings,
        String disclaimer, String createdAt, boolean saved) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String itemId, String itemCode, String category, String publicName, String source,
                       String completeness, long pricedSubtotalCents, Long amountCents, List<Line> lines) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Line(String lineId, String optionLabel, String status, Long amountCents) {}

    public AppItemizedBudgetRespVO withSaved(boolean value) {
        return new AppItemizedBudgetRespVO(budgetId, revisionId, model, projectId, projectName, resultVersionId,
                schemeName, regionName, inputSummary, completeness, pricedSubtotalCents, totalCents,
                categoryTotals, items, missingItems, warnings, disclaimer, createdAt, value);
    }
}
