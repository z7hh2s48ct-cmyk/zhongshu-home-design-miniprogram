package cn.iocoder.yudao.module.identity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 授权码批次完整明文交付方式（互斥，批次创建时固定）
 */
@RequiredArgsConstructor
@Getter
public enum DeliveryModeEnum {

    /** 创建批次响应内直接返回完整码，展示机会仅一次 */
    INLINE("INLINE"),

    /** 一次性交付票据：先原子消费票据，再流式输出加密私有文件 */
    TICKET("TICKET");

    private final String mode;

    public static DeliveryModeEnum of(String mode) {
        return Arrays.stream(values()).filter(e -> e.mode.equals(mode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知交付方式：" + mode));
    }

}
