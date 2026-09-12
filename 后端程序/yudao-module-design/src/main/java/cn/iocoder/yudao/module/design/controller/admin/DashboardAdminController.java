package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 工作台聚合（页面 02）。
 *
 * 只读计数经同一数据源跨域取数（design/ai/commerce 库表）：工作台是天然的跨域读模型，
 * 各业务模块均无对外暴露计数端点，为此引入跨模块服务调用反而把展示需求传染进领域层。
 * 计数全部走参数化只读 SQL，无写入路径；表结构演进时此处同步即可。
 */
@Tag(name = "管理后台 - 工作台（页面 02）")
@RestController
@RequestMapping("/design/v1/dashboard")
public class DashboardAdminController {

    @Resource
    private DataSource dataSource;

    @GetMapping("/summary")
    @Operation(summary = "工作台聚合：今日任务/待审核/订单/点数与待办")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.DASHBOARD_READ + "')")
    public CommonResult<Map<String, Object>> getSummary() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> summary = new LinkedHashMap<>();
        // 生成侧
        summary.put("aiJobsRunning", scalar(jdbc,
                "SELECT count(*) FROM ai_job WHERE status IN ('QUEUED','RUNNING','VALIDATING','SETTLING','CANCEL_REQUESTED') AND deleted = FALSE"));
        var trend = jdbc.queryForList("WITH days AS (SELECT generate_series("
                + "(now() AT TIME ZONE 'Asia/Shanghai')::date - 6, "
                + "(now() AT TIME ZONE 'Asia/Shanghai')::date, interval '1 day')::date AS day), "
                + "finished AS (SELECT j.id, MAX(s.create_time) AS finished_at FROM ai_job j "
                + "JOIN ai_job_settlement s ON s.job_id = j.id AND s.deleted = FALSE "
                + "WHERE j.status = 'SUCCEEDED' AND j.deleted = FALSE GROUP BY j.id) "
                + "SELECT to_char(d.day, 'YYYY-MM-DD') AS day, count(f.id) AS count FROM days d "
                + "LEFT JOIN finished f ON (f.finished_at AT TIME ZONE 'Asia/Shanghai')::date = d.day "
                + "GROUP BY d.day ORDER BY d.day");
        summary.put("aiTrend", trend);
        summary.put("businessTimezone", "Asia/Shanghai");
        summary.put("aiJobsSucceededToday", trend.get(trend.size() - 1).get("count"));
        // 审核侧
        summary.put("submissionsPendingReview", scalar(jdbc,
                "SELECT count(*) FROM case_submission WHERE status IN ('SUBMITTED','RESUBMITTED','IN_REVIEW') AND deleted = FALSE"));
        summary.put("casesPublished", scalar(jdbc,
                "SELECT count(*) FROM design_case WHERE publication_status = 'PUBLISHED' AND deleted = FALSE"));
        // 商业侧
        summary.put("ordersPendingFulfillment", scalar(jdbc,
                "SELECT count(*) FROM recharge_order WHERE payment_state = 'SUCCEEDED' "
                        + "AND fulfillment_state NOT IN ('CREDITED') AND deleted = FALSE"));
        summary.put("ordersUnknownPayment", scalar(jdbc,
                "SELECT count(*) FROM recharge_order WHERE payment_state = 'UNKNOWN' AND deleted = FALSE"));
        summary.put("pointsConsumedToday", scalar(jdbc,
                "SELECT COALESCE(-SUM(delta), 0) FROM design_point_ledger "
                        + "WHERE type IN ('FLAT_GENERATION_DEBIT','ELEVATION_GENERATION_DEBIT') "
                        + "AND (create_time AT TIME ZONE 'Asia/Shanghai')::date = "
                        + "(now() AT TIME ZONE 'Asia/Shanghai')::date AND deleted = FALSE"));
        summary.put("generationPointsRefundedToday", scalar(jdbc,
                "SELECT COALESCE(SUM(delta), 0) FROM design_point_ledger WHERE type = 'TASK_SETTLEMENT_REFUND' "
                        + "AND (create_time AT TIME ZONE 'Asia/Shanghai')::date = "
                        + "(now() AT TIME ZONE 'Asia/Shanghai')::date AND deleted = FALSE"));
        summary.put("openRefunds", scalar(jdbc,
                "SELECT count(*) FROM refund_order WHERE channel_state IN ('CREATED','PENDING','UNKNOWN') AND deleted = FALSE"));
        // 消息与用户
        summary.put("accountsActive", scalar(jdbc,
                "SELECT count(*) FROM account WHERE status = 'ACTIVE' AND deleted = FALSE"));
        summary.put("accessGrantsActive", scalar(jdbc,
                "SELECT count(*) FROM design_access_grant WHERE status = 'ACTIVE' AND deleted = FALSE"));
        return success(summary);
    }

    /** count/sum 标量统一收敛为 Long，避免各表空结果类型不一致 */
    private long scalar(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

}
