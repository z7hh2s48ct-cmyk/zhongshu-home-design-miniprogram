package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * C 端用户管理（运营诉求：查用户、看授权与点数；页面 15 之外的运营面）。
 *
 * 读模型：跨 account / design_access_grant / design_point_ledger / design_project /
 * case_submission 的展示型联查，与工作台同款取数方式（参数化只读 SQL）。
 * 生命周期：停用已获产品确认（2026-10-02）——PATCH /{id} 置 DISABLED 并撤销全部有效授权；
 * 注销仍走隐私申请关闭流程（identity:privacy:manage）。
 */
@Tag(name = "管理后台 - C端用户管理")
@RestController
@RequestMapping("/design/v1/accounts")
public class AccountAdminController {

    @Resource
    private DataSource dataSource;

    @Resource
    private cn.iocoder.yudao.module.identity.accesscode.AccessGrantService accessGrantService;

    @GetMapping
    @Operation(summary = "用户分页查询（昵称关键字/账号状态筛选；含授权态与点数余额汇总）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCOUNT_READ + "')")
    public CommonResult<PageResult<Map<String, Object>>> getAccountPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "nickname", required = false) String nickname,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder where = new StringBuilder(" WHERE a.deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            args.add(status);
        }
        if (nickname != null && !nickname.isBlank()) {
            where.append(" AND a.nickname ILIKE ?");
            args.add("%" + nickname.trim() + "%");
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM account a" + where, Long.class, args.toArray());
        // 最新一条流水的 available_after 即当前可用余额（账本只追加，末行即快照）
        String listSql =
                "SELECT a.id, a.nickname, a.status, a.create_time, g.id AS grant_id, g.status AS grant_status, "
                        + "(SELECT count(*) FROM design_project p WHERE p.user_id = a.id AND p.deleted = FALSE) AS project_count, "
                        + "(SELECT count(*) FROM case_submission s WHERE s.user_id = a.id AND s.status = 'APPROVED' "
                        + "  AND s.deleted = FALSE) AS approved_submission_count, "
                        + "(SELECT l.available_after FROM design_point_ledger l WHERE l.user_id = a.id "
                        + "  AND l.deleted = FALSE ORDER BY l.id DESC LIMIT 1) AS available_points "
                        + "FROM account a "
                        + "LEFT JOIN design_access_grant g ON g.account_id = a.id AND g.status = 'ACTIVE' "
                        + "  AND g.deleted = FALSE"
                        + where
                        + " ORDER BY a.id DESC LIMIT ? OFFSET ?";
        args.add(Math.min(pageSize, 100));
        args.add((long) Math.max(pageNo - 1, 0) * pageSize);
        List<Map<String, Object>> list = jdbc.queryForList(listSql, args.toArray());
        // 19 位雪花 id 超出 JS 安全整数：所有 id 出网关前统一字符串化，防止前端精度失真打错目标
        list.forEach(this::stringifyIds);
        return success(new PageResult<>(list, total == null ? 0 : total));
    }

    /** 递归把 Map 内以 id 结尾的键值转为字符串（雪花 id 防精度丢失） */
    @SuppressWarnings("unchecked")
    private void stringifyIds(Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number number
                    && (entry.getKey().equalsIgnoreCase("id") || entry.getKey().toLowerCase().endsWith("_id"))) {
                entry.setValue(String.valueOf(number.longValue()));
            }
        }
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "用户详情（授权记录、点数余额与近期流水、设计/投稿统计）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCOUNT_READ + "')")
    public CommonResult<Map<String, Object>> getAccount(@PathVariable("accountId") String accountId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long id = Long.parseLong(accountId);
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT a.id, a.nickname, a.status, a.avatar, a.create_time FROM account a "
                        + "WHERE a.id = ? AND a.deleted = FALSE", id);
        if (accounts.isEmpty()) {
            return success(null);
        }
        Map<String, Object> detail = new LinkedHashMap<>(accounts.get(0));
        stringifyIds(detail);
        List<Map<String, Object>> grants = jdbc.queryForList(
                "SELECT id, status, granted_at, revoked_at, revoked_by FROM design_access_grant "
                        + "WHERE account_id = ? AND deleted = FALSE ORDER BY id DESC LIMIT 20", id);
        grants.forEach(this::stringifyIds);
        detail.put("grants", grants);
        Long available = jdbc.queryForObject(
                "SELECT COALESCE((SELECT available_points FROM design_point_account "
                        + "WHERE user_id = ? AND deleted = FALSE), 0)", Long.class, id);
        detail.put("availablePoints", available == null ? 0 : available);
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT id, type, delta, available_after, biz_type, biz_id, reason, create_time "
                        + "FROM design_point_ledger WHERE user_id = ? AND deleted = FALSE "
                        + "ORDER BY id DESC LIMIT 10", id);
        ledger.forEach(this::stringifyIds);
        detail.put("recentLedger", ledger);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("projectCount", countOrNull(jdbc,
                "SELECT count(*) FROM design_project WHERE user_id = ? AND deleted = FALSE", id));
        counts.put("approvedSubmissionCount", countOrNull(jdbc,
                "SELECT count(*) FROM case_submission WHERE user_id = ? AND status = 'APPROVED' AND deleted = FALSE", id));
        counts.put("aiJobCount", countOrNull(jdbc,
                "SELECT count(*) FROM ai_job WHERE user_id = ? AND deleted = FALSE", id));
        detail.put("stats", counts);
        return success(detail);
    }

    private Long countOrNull(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{accountId}")
    @Operation(summary = "停用账号：状态置 DISABLED 并立即撤销全部有效授权；新兑换与登录激活被拒绝，存量访问实时降级")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCOUNT_DISABLE + "')")
    public CommonResult<Boolean> disableAccount(@PathVariable("accountId") String accountId) {
        String operator = String.valueOf(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId());
        return success(accessGrantService.disableAccount(Long.parseLong(accountId), operator));
    }

}
