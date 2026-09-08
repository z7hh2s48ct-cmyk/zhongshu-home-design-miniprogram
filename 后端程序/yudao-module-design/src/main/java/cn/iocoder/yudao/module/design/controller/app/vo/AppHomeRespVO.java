package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 首页聚合 Response VO（页面 01）")
@Data
public class AppHomeRespVO {

    @Schema(description = "精选案例")
    private List<AppCaseSummaryRespVO> featuredCases;

    @Schema(description = "未读消息数")
    private Long unreadCount;

    @Schema(description = "使用权状态：ACTIVE / REVOKED / NONE")
    private String accessGrantStatus;

}
