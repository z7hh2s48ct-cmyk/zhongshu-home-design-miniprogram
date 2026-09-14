package cn.iocoder.yudao.module.design.controller.app.vo;

import java.util.List;

/**
 * T14: 我的当地单价（账号覆盖价）。金额单位为分；基准价为固定武汉参考目录价。
 * priceSource：ACCOUNT_OVERRIDE=账号覆盖价生效，DEFAULT=基准价生效，MISSING=基准缺价且无覆盖。
 */
public record AppMyPriceRespVO(String optionId, String code, String label, String selectionGroup, String unit,
                               Long baselinePriceCents, Long myPriceCents, String priceSource, String reason, String updatedAt) {

    public record Items(String regionCode, String regionName, List<Group> groups) {}

    public record Group(String itemId, String itemCode, String itemName, String category, List<AppMyPriceRespVO> options) {}
}
