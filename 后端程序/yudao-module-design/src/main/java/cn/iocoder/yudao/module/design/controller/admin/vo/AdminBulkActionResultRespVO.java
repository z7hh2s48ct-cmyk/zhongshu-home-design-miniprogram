package cn.iocoder.yudao.module.design.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 批量操作逐项结果 Response VO（不静默半成功）")
@Data
public class AdminBulkActionResultRespVO {

    @Schema(description = "逐项结果")
    private List<Item> items;

    @Schema(description = "单项目标结果")
    @Data
    public static class Item {

        @Schema(description = "目标对象编号")
        private String targetId;

        @Schema(description = "是否成功")
        private Boolean success;

        @Schema(description = "失败时的稳定错误码字符串")
        private String errorCode;

    }

}
