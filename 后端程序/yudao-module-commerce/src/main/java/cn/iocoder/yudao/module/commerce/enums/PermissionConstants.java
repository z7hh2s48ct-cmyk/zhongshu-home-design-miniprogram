package cn.iocoder.yudao.module.commerce.enums;

/**
 * commerce 模块权限点（架构文档 §4.2）
 */
public interface PermissionConstants {

    String RECHARGE_PLAN_QUERY = "commerce:recharge-plan:query";
    String RECHARGE_PLAN_MANAGE = "commerce:recharge-plan:manage";

    String RECHARGE_ORDER_QUERY = "commerce:recharge-order:query";
    String PAYMENT_RECONCILE = "commerce:payment:reconcile";

    String POINTS_QUERY = "commerce:points:query";
    String POINTS_ADJUST = "commerce:points:adjust";
    String POINTS_EXPORT = "commerce:points:export";

}
