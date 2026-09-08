package cn.iocoder.yudao.module.design.enums;

/**
 * 审核决定
 */
public enum ReviewDecisionEnum {

    /** 通过（通过后仍需独立发布动作） */
    APPROVE,

    /** 要求修改（用户可重提，新建 revision） */
    CHANGES_REQUESTED,

    /** 拒绝 */
    REJECT

}
