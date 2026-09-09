package cn.iocoder.yudao.module.design.controller.app.vo;

import java.util.List;

/** Public catalog deliberately has no prices, quantities, conversion rules or internal references. */
public record AppBudgetCatalogRespVO(List<Item> items) {
    public record Region(String code, String name) {}
    public record Item(String itemId, String code, String category, String name, String source,
                       List<Option> options) {}
    public record Option(String optionId, String code, String label, String selectionGroup,
                         String availability) {}
}
