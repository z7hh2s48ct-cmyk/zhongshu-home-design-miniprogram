package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "小程序 - 客服入口配置 Response VO")
@Data
public class AppSupportEntryRespVO {

    @Schema(description = "客服电话")
    private String phone;

    @Schema(description = "企业微信客服标识")
    private String wecomCorpId;

    @Schema(description = "帮助页地址")
    private String helpUrl;

}
