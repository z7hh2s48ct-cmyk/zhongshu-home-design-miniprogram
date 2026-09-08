package cn.iocoder.yudao.module.aiorchestration.enums;

/**
 * 结果校验状态：Runtime 只提交事实候选，Core 受控校验器裁决 ACCEPTED/REJECTED
 */
public enum AiResultValidationStateEnum {

    RECEIVED,
    OUTPUT_QUARANTINED,
    VALIDATING,
    ACCEPTED,
    REJECTED

}
