package cn.iocoder.yudao.module.design.enums;

/**
 * 投稿发布状态：WITHDRAWN 不可改回 PUBLISHED，重上需新案例版本 + 新授权 + 新发布事实
 */
public enum SubmissionPublicationStatusEnum {

    PRIVATE,
    PUBLISH_PENDING,
    PUBLISHED,
    WITHDRAWN

}
