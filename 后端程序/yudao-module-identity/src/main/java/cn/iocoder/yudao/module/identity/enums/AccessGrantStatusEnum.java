package cn.iocoder.yudao.module.identity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 小程序使用权（design_access_grant）状态
 */
@RequiredArgsConstructor
@Getter
public enum AccessGrantStatusEnum {

    /** 可正常使用产品 */
    ACTIVE,

    /** 已撤销（后台解绑只撤销授权，原授权码保持已消费） */
    REVOKED;

}
