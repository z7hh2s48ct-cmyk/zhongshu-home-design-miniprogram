package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 站内消息 Response VO")
@Data
public class AppMessageRespVO {

    @Schema(description = "消息编号")
    private String messageId;

    @Schema(description = "消息类型：JOB_SUCCEEDED/JOB_FAILED/REFUND/SUBMISSION_APPROVED/SUBMISSION_REJECTED/PAYMENT_ALERT/RIGHTS_EXPIRED")
    private String messageType;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "关联业务对象（如 jobId、submissionId）")
    private String bizType;

    private String bizId;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "发送时间")
    private LocalDateTime sentAt;

}
