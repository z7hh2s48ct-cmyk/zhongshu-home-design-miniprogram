package cn.iocoder.yudao.module.design.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 案例来源
 */
@RequiredArgsConstructor
@Getter
public enum CaseSourceEnum {

    /** 公司案例（后台上传维护） */
    COMPANY("COMPANY"),

    /** AI 案例（用户投稿审核通过后发布） */
    AI("AI");

    private final String source;

}
