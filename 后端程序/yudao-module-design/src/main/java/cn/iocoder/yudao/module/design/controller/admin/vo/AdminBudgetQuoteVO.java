package cn.iocoder.yudao.module.design.controller.admin.vo;

import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetQuoteRespVO;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class AdminBudgetQuoteVO {
    private AdminBudgetQuoteVO() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Quote(String quoteId, String budgetId, String revisionId, int revisionNo,
                        int quoteVersion, String status, int version, long calculatedTotalCents,
                        long adjustmentCents, long finalPriceCents, String reason, String actorId,
                        boolean stale, boolean currentPublic, String publishedBy, String publishedAt,
                        String withdrawnBy, String withdrawnAt, String withdrawalReason,
                        AppBudgetQuoteRespVO preview) {
    }
}
