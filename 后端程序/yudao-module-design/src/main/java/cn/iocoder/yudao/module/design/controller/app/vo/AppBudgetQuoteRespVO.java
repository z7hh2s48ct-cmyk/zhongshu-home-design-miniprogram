package cn.iocoder.yudao.module.design.controller.app.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Owner-visible final quote snapshot. Adjustment reasons and all internal pricing inputs stay private. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppBudgetQuoteRespVO(
        String quoteId, int quoteVersion, String budgetId, String revisionId, String projectId,
        String projectName, String resultVersionId, String schemeName, String regionName,
        String status, boolean current, long calculatedTotalCents, long adjustmentCents,
        long finalPriceCents, Map<String, Long> categoryTotals,
        List<AppItemizedBudgetRespVO.Item> items, String publishedAt, String withdrawnAt,
        String disclaimer) {
}
