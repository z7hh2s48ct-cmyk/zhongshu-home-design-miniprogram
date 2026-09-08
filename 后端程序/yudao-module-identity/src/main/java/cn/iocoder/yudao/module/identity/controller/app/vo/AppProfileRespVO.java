package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "小程序 - 个人信息 Response VO（页面 15“我的”）")
@Data
public class AppProfileRespVO {

    @Schema(description = "用户编号")
    private Long id;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像 URL（短期签名或公开地址）")
    private String avatar;

    @Schema(description = "账号状态：ACTIVE / DISABLED / CLOSED")
    private String status;

    @Schema(description = "使用权状态：ACTIVE 已激活 / NONE 未激活或已撤销；由服务端实时判定")
    private String accessGrantStatus;

    // 设计点余额不在本响应内下发：余额的唯一合同是 GET /design/v1/point-account。
    // 同一数值出现在两个端点会产生"哪个是准的"的歧义，也会让"我的"页缓存出陈旧余额。

    @Schema(description = "设计/发布统计，如 projectCount、publishedCount")
    private Map<String, Integer> stats;

    @Schema(description = "当前对象允许执行的动作集合，客户端不得自行推导")
    private List<String> allowedActions;

}
