package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 数据主体申请（导出/账号关闭）Response VO")
@Data
public class AppSubjectRequestRespVO {

    @Schema(description = "申请编号")
    private String requestId;

    @Schema(description = "状态：PENDING / PROCESSING / COMPLETED / REJECTED")
    private String status;

    @Schema(description = "申请时间")
    private LocalDateTime createdAt;

    @Schema(description = "完成时间")
    private LocalDateTime completedAt;

    @Schema(description = "一次性下载票据（仅导出完成后返回）")
    private String downloadTicket;

}
