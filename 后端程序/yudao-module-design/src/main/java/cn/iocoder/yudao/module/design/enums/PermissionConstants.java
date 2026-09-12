package cn.iocoder.yudao.module.design.enums;

/**
 * design 模块权限点（架构文档 §4.2）
 */
public interface PermissionConstants {

    String DASHBOARD_READ = "design:dashboard:query";

    String CASE_READ = "design:case:query";
    String CASE_CREATE = "design:case:create";
    String CASE_WRITE = "design:case:update";
    String CASE_PUBLISH = "design:case:publish";

    String SUBMISSION_REVIEW = "design:submission:review";

    String EXPORT_MANAGE = "design:export:manage";
    String AUDIT_READ = "design:audit:query";

    String BUDGET_QUERY = "design:budget:query";
    String BUDGET_CONFIGURE = "design:budget:configure";
    String BUDGET_EDIT = "design:budget:edit";
    String BUDGET_PRICE_PUBLISH = "design:budget:price-publish";
    String BUDGET_QUOTE = "design:budget:quote";
    String BUDGET_QUOTE_PUBLISH = "design:budget:quote-publish";

}
