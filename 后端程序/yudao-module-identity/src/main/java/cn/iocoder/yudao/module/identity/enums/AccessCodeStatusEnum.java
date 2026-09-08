package cn.iocoder.yudao.module.identity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 授权码状态
 */
@RequiredArgsConstructor
@Getter
public enum AccessCodeStatusEnum {

    /** 可兑换 */
    ACTIVE,

    /** 已被兑换消费（终态，解绑后也不复活） */
    CONSUMED,

    /** 已停用 */
    DISABLED;

    /** 是否已过期由 expires_at 与当前时间比较得出，不作为独立状态 */
}
