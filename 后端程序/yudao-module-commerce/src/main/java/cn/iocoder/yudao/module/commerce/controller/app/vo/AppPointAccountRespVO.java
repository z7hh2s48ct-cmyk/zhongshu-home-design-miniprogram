package cn.iocoder.yudao.module.commerce.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 设计点账户 Response VO")
@Data
public class AppPointAccountRespVO {

    @Schema(description = "可用设计点（任务只扣可用点）")
    private Integer availablePoints;

    @Schema(description = "预留设计点（退款预留等，总余额 = 可用 + 预留）")
    private Integer reservedPoints;

    @Schema(description = "账户版本号（并发乐观锁展示用）")
    private Long version;

    @Schema(description = "当前对象允许执行的动作集合")
    private List<String> allowedActions;

}
