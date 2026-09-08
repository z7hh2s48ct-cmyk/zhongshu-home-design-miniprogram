package cn.iocoder.yudao.module.design.enums;

/**
 * 投稿审核状态（与计算状态、发布状态三套分离）
 */
public enum SubmissionStatusEnum {

    DRAFT,
    VALIDATED,
    SUBMITTED,
    IN_REVIEW,
    CHANGES_REQUESTED,
    RESUBMITTED,
    APPROVED,
    REJECTED

}
