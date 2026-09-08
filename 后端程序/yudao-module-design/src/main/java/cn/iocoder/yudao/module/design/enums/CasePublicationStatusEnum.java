package cn.iocoder.yudao.module.design.enums;

/**
 * 案例库发布状态：列表只返回 PUBLISHED
 */
public enum CasePublicationStatusEnum {

    /** 草稿，不可见 */
    DRAFT,

    /** 已上架 */
    PUBLISHED,

    /** 已下架，即时不可见 */
    OFFLINE

}
