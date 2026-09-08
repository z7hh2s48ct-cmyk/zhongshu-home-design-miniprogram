package cn.iocoder.yudao.framework.common.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 游标分页结果（小程序端合同）
 *
 * 与 {@link PageResult} 并列使用：后台管理列表用页码 {@link PageResult}，
 * 小程序案例/消息等列表用游标 {@link CursorPageResult}，游标同时编码排序键与 ID，避免同值翻页重复。
 * 二者都不是第二套响应壳，均作为 CommonResult 的 data 使用。
 */
@Schema(description = "游标分页结果")
@Data
public final class CursorPageResult<T> implements Serializable {

    @Schema(description = "数据", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<T> list;

    @Schema(description = "下一页游标，null 表示没有更多数据", example = "eyJhcmVhIjoxMjAsImlkIjo5OTh9")
    private String nextCursor;

    public CursorPageResult() {
    }

    public CursorPageResult(List<T> list, String nextCursor) {
        this.list = list;
        this.nextCursor = nextCursor;
    }

    public static <T> CursorPageResult<T> empty() {
        return new CursorPageResult<>(new ArrayList<>(), null);
    }

}
