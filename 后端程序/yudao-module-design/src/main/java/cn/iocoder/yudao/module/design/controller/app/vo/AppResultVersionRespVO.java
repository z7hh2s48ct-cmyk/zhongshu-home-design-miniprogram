package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 设计结果版本 Response VO（页面 11；版本不可变，旧版本只读）")
@Data
public class AppResultVersionRespVO {

    @Schema(description = "版本行编号")
    private String versionId;

    @Schema(description = "版本号，从 1 递增；调整链每完成一轮 +1")
    private Long version;

    @Schema(description = "本版本采用的平面候选编号")
    private String flatCandidateIds;

    @Schema(description = "本版本采用的立面候选编号")
    private String elevationCandidateId;

    @Schema(description = "生成配置快照（风格、屋顶、材质等）")
    private String configSnapshot;

    @Schema(description = "是否已被更新版本取代；true 表示只读历史版本")
    private Boolean superseded;

    @Schema(description = "生成时间")
    private LocalDateTime createdAt;

}
