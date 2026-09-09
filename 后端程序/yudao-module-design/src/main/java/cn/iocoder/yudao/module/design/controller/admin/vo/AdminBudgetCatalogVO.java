package cn.iocoder.yudao.module.design.controller.admin.vo;

/** Explicit administrative projections; never reuse these records for App responses. */
public final class AdminBudgetCatalogVO {
    private AdminBudgetCatalogVO() {}

    public record Region(String regionId, String code, String name, boolean enabled, int version) {}
    public record Item(String itemId, String code, String name, String category, String source,
                       boolean enabled, boolean publicSelectable, int sortOrder, int version) {}
    public record Option(String optionId, String itemId, String code, String label, String selectionGroup,
                         String unit, String quantitySource, String quantityKey, String sourceReference,
                         boolean enabled, int sortOrder, int version) {}
    public record Price(String priceId, String regionId, String regionCode, String optionId,
                        Long unitPriceCents, String freeReason, String status, String effectiveAt,
                        String expiresAt, int version, String publishedBy, String publishedAt) {}
}
