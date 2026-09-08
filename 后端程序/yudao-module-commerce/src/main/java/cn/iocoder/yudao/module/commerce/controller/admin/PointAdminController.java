package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.commerce.controller.app.vo.AppPointLedgerItemRespVO;
import cn.iocoder.yudao.module.commerce.enums.PermissionConstants;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.adjustment.ManualPointAdjustmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 设计点流水与人工调点（页面 12）")
@RestController
@RequestMapping("/design/v1")
public class PointAdminController {

    @Resource
    private PointAccountService pointAccountService;

    @Resource
    private ManualPointAdjustmentService manualPointAdjustmentService;

    @Resource
    private javax.sql.DataSource dataSource;

    @GetMapping("/point-ledger")
    @Operation(summary = "全局点数流水查询（充值/赠送/扣点/退回/人工调整），支持导出任务")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.POINTS_QUERY + "')")
    public CommonResult<PageResult<AppPointLedgerItemRespVO>> getLedgerPage(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AppPointLedgerItemRespVO> result = new PageResult<>();
        result.setTotal(pointAccountService.countLedger(userId, type));
        result.setList(pointAccountService.pageLedger(userId, type, pageNo, pageSize).stream().map(row -> {
            AppPointLedgerItemRespVO vo = new AppPointLedgerItemRespVO();
            vo.setLedgerId(String.valueOf(row.id()));
            vo.setType(row.type());
            vo.setDelta((int) row.delta());
            vo.setBalanceAfter((int) (row.availableAfter() + row.reservedAfter()));
            vo.setBizType(row.bizType());
            vo.setBizId(row.bizId());
            vo.setCreatedAt(java.time.LocalDateTime.ofInstant(row.createTime(), java.time.ZoneOffset.UTC));
            return vo;
        }).toList());
        return success(result);
    }

    @GetMapping("/manual-point-adjustments")
    @Operation(summary = "人工调点单分页查询（状态筛选；复核入口数据源）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.POINTS_QUERY + "')")
    public CommonResult<PageResult<Map<String, Object>>> getAdjustmentPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM manual_point_adjustment" + where, Long.class, args.toArray());
        args.add(Math.min(pageSize, 100));
        args.add((long) Math.max(pageNo - 1, 0) * pageSize);
        var list = jdbc.queryForList(
                "SELECT id, target_user_id, delta, reason, status, maker_user_id, checker_user_id, "
                        + "checker_comment, ledger_id, executed_at, create_time "
                        + "FROM manual_point_adjustment" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                args.toArray());
        // 雪花 id 字符串化（前端 JS 精度安全），复核回传由服务端 Long.parseLong 接受
        list.forEach(row -> {
            for (var key : java.util.List.of("id", "target_user_id", "maker_user_id", "checker_user_id", "ledger_id")) {
                Object value = row.get(key);
                if (value instanceof Number number) {
                    row.put(key, String.valueOf(number.longValue()));
                }
            }
        });
        return success(new PageResult<>(list, total == null ? 0 : total));
    }

    @PostMapping("/manual-point-adjustments")
    @Operation(summary = "创建人工调点申请（制单）；不能直接改余额")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.POINTS_ADJUST + "')")
    public CommonResult<Map<String, Object>> createAdjustment(@RequestBody Map<String, Object> body) {
        // 19 位雪花 id 超出 JS 安全整数，客户端可能以字符串送达：统一按字符串解析，双类型兼容
        long targetUserId = Long.parseLong(String.valueOf(body.get("targetUserId")));
        long delta = ((Number) body.get("delta")).longValue();
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("调点原因不能为空");
        }
        long makerUserId = SecurityFrameworkUtils.getLoginUserId();
        long id = manualPointAdjustmentService.submit(targetUserId, delta, reason, makerUserId);
        return success(Map.of("adjustmentId", String.valueOf(id), "status", "SUBMITTED"));
    }

    @PostMapping("/manual-point-adjustments/{adjustmentId}/review-decisions")
    @Operation(summary = "人工调点复核（复核人必须不同于制单人）；approve=true 时同事务执行并写流水与审计")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.POINTS_ADJUST + "')")
    public CommonResult<Map<String, Object>> reviewAdjustment(@PathVariable("adjustmentId") String adjustmentId,
                                                              @RequestBody Map<String, Object> decision) {
        Object approveRaw = decision.get("approve");
        if (!(approveRaw instanceof Boolean)) {
            throw new IllegalArgumentException("approve 必须为布尔值");
        }
        boolean approve = (Boolean) approveRaw;
        String comment = (String) decision.get("comment");
        long checkerUserId = SecurityFrameworkUtils.getLoginUserId();
        Long ledgerId = manualPointAdjustmentService.review(Long.parseLong(adjustmentId), checkerUserId,
                approve, comment);
        return success(Map.of(
                "status", approve ? "EXECUTED" : "REJECTED",
                "ledgerId", ledgerId == null ? "" : String.valueOf(ledgerId)));
    }

}
