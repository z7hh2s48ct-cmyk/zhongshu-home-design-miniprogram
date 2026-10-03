package cn.iocoder.yudao.server.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 运营数据删除（2026-10-02 运营决策：全部模块可删，支持单个与批量）。
 *
 * 边界与口径：
 * - 逻辑删除（deleted=TRUE）：账号、点数流水、充值订单、AI任务、投稿、预算、案例；
 *   点数余额不回滚（账户表余额独立维护），历史审计链保底；
 * - 审计事件表无 deleted 列：物理删除（删审计本身不留痕，属管理员显式动作）；
 * - 账号删除同时撤销其全部有效授权（访问即时降级）；
 * - 每次删除（含批量逐条）写一条 ADMIN_DATA_DELETE 审计事件，记录操作者与对象。
 */
@Tag(name = "管理后台 - 运营数据删除")
@RestController
@RequestMapping("/design/v1/admin-data")
public class AdminDataDeleteController {

    /** 资源类型 → 表名（全部含 deleted 列，audit_event 除外走物理删除） */
    private static final Map<String, String> TABLES = Map.of(
            "account", "account",
            "point-ledger", "design_point_ledger",
            "recharge-order", "recharge_order",
            "ai-job", "ai_job",
            "submission", "case_submission",
            "budget", "budget_estimate",
            "case", "design_case"
    );

    @Resource
    private DataSource dataSource;

    @Resource
    private AuditPort auditPort;

    @DeleteMapping("/{type}/{id}")
    @Operation(summary = "删除单条运营数据（逻辑删除；审计事件为物理删除）")
    @PreAuthorize("@ss.hasPermission('design:export:manage') and @ss.hasPermission(@adminDataDeleteController.deletePermission(#p0))")
    public CommonResult<Boolean> deleteOne(@PathVariable("type") String type, @PathVariable("id") String id) {
        requirePermission(type);
        boolean ok = deleteInternal(type, Long.parseLong(id));
        return success(ok);
    }

    @PostMapping("/batch-deletes")
    @Operation(summary = "批量删除运营数据（逐条逻辑删除并逐条留审计，上限 200 条）")
    @PreAuthorize("@ss.hasPermission('design:export:manage') and @ss.hasPermission(@adminDataDeleteController.deletePermission(#p0['type']))")
    public CommonResult<Map<String, Object>> deleteBatch(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.get("type"));
        requirePermission(type);
        List<?> rawIds = (List<?>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) {
            return success(Map.of("deleted", 0));
        }
        if (rawIds.size() > 200) {
            throw new IllegalArgumentException("单次批量删除上限 200 条");
        }
        long operator = SecurityFrameworkUtils.getLoginUserId();
        int deleted = 0;
        for (Object raw : rawIds) {
            long id = Long.parseLong(String.valueOf(raw));
            if (deleteInternal(type, id)) {
                deleted++;
            }
        }
        return success(Map.of("deleted", deleted, "requested", rawIds.size(), "operator", String.valueOf(operator)));
    }

    // ========== 内部 ==========

    public String deletePermission(String type) {
        return switch (type == null ? "" : type) {
            case "account" -> "identity:account:delete";
            case "point-ledger" -> "commerce:points:delete";
            case "recharge-order" -> "commerce:recharge-order:delete";
            case "ai-job" -> "aiorchestration:job:delete";
            case "submission" -> "design:submission:delete";
            case "budget" -> "design:budget:delete";
            case "case" -> "design:case:delete";
            case "audit-event" -> "design:audit:delete";
            default -> throw new IllegalArgumentException("Unsupported deletion type: " + type);
        };
    }

    private void requirePermission(String type) {
        deletePermission(type);
        // 控制器级统一门禁已设（export:manage）；此处校验类型合法，方法级细分权限由各资源常量约束口径
        if (!TABLES.containsKey(type) && !"audit-event".equals(type)) {
            throw new IllegalArgumentException("不支持的资源类型: " + type);
        }
    }

    private boolean deleteInternal(String type, long id) {
        var jdbc = new JdbcTemplate(dataSource);
        long operator = SecurityFrameworkUtils.getLoginUserId();
        boolean ok;
        if ("audit-event".equals(type)) {
            ok = jdbc.update("DELETE FROM audit_event WHERE id = ?", id) == 1;
        } else if ("recharge-order".equals(type)) {
            // The conditional update serializes with the payment callback's order row lock.
            ok = jdbc.update("UPDATE recharge_order SET deleted = TRUE, update_time = now() "
                    + "WHERE id = ? AND deleted = FALSE AND payment_state IN ('CLOSED','FAILED') "
                    + "AND fulfillment_state = 'NOT_READY' AND NOT EXISTS "
                    + "(SELECT 1 FROM payment_transaction WHERE order_no = recharge_order.order_no)", id) == 1;
        } else {
            String table = TABLES.get(type);
            ok = jdbc.update("UPDATE " + table + " SET deleted = TRUE, update_time = now() WHERE id = ? AND deleted = FALSE", id) == 1;
        }
        if (ok && "account".equals(type)) {
            // 账号删除：撤销全部有效授权，访问即时降级（点数余额冻结不清理）
            jdbc.update("UPDATE design_access_grant SET status = 'REVOKED', revoked_at = now(), "
                    + "revoked_by = ?, update_time = now() WHERE account_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                    String.valueOf(operator), id);
        }
        if (ok) {
            auditPort.record(AuditEventMessage.builder()
                    .eventType("ADMIN_DATA_DELETE").actorType(AuditEventMessage.ActorType.ADMIN)
                    .actorId(String.valueOf(operator)).action("DELETE")
                    .bizType(type).bizId(String.valueOf(id))
                    .result(AuditEventMessage.AuditResult.SUCCESS)
                    .detail(Map.of("table", TABLES.getOrDefault(type, "audit_event")))
                    .build());
        }
        return ok;
    }

}
