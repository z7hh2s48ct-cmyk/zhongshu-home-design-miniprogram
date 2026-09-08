package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "小程序 - 设计候选 Response VO（页面 07 选平面 / 页面 10 选立面）")
@Data
public class AppDesignCandidateRespVO {

    @Schema(description = "候选编号（选定时回传该值）")
    private String candidateId;

    @Schema(description = "产出该候选的任务编号")
    private String jobId;

    @Schema(description = "槽位号，从 1 开始；界面上的「方案 A/B/C」按它排序")
    private Integer slotNo;

    @Schema(description = "候选图资产编号；取图走 /assets/{assetId}/download-tickets 换一次性票据")
    private String assetId;

}
