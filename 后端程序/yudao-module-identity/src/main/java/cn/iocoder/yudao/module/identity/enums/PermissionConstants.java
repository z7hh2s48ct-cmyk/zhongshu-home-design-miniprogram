package cn.iocoder.yudao.module.identity.enums;

/**
 * identity 模块权限点（架构文档 §4.2）
 *
 * 仅作为服务端权限判定的稳定字符串；后台菜单不作为授权依据。
 */
public interface PermissionConstants {

    /** 授权码与批次管理（生成、停用、交付票据） */
    String ACCESS_CODE_MANAGE = "identity:access-code:manage";

    /** 授权码完整明文复制与导出 */
    String ACCESS_CODE_EXPORT = "identity:access-code:export";

    /** C 端用户（账号/授权）只读查询 */
    String ACCOUNT_READ = "identity:account:query";

    /** Review data subject requests and approve closure after retention/financial checks. */
    String PRIVACY_MANAGE = "identity:privacy:manage";

}
