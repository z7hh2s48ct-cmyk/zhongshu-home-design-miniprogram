package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.commerce.enums.PermissionConstants;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleService;
import cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 出图计价规则")
@RestController
@RequestMapping("/design/v1/generation-price-rules")
public class GenerationPriceRuleAdminController {

    private final JdbcTemplate jdbc;
    private final PriceRuleService priceRuleService;
    private final AuditPort auditPort;

    /** F-6 档位上限配置化：默认 4，产品扩展档位时改配置即可，无需改代码 */
    @org.springframework.beans.factory.annotation.Value("${zhongshu.commerce.generation-count-max:4}")
    private int generationCountMax;

    public GenerationPriceRuleAdminController(JdbcTemplate jdbc, PriceRuleService priceRuleService,
                                              AuditPort auditPort) {
        this.jdbc = jdbc;
        this.priceRuleService = priceRuleService;
        this.auditPort = auditPort;
    }

    public record CreateRuleRequest(String stage, String resolution, long unitPointCost,
                                    int minCount, int maxCount, Instant effectiveAt, Instant expiresAt) {
    }

    @GetMapping
    @Operation(summary = "分页查询出图计价规则")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.GENERATION_PRICE_QUERY + "')")
    public CommonResult<PageResult<Map<String, Object>>> getRulePage(
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        List<String> where = new ArrayList<>(List.of("deleted = FALSE"));
        List<Object> args = new ArrayList<>();
        addFilter(where, args, "stage", stage);
        addFilter(where, args, "resolution", resolution);
        addFilter(where, args, "status", status);
        String base = "FROM generation_price_rule WHERE " + String.join(" AND ", where);
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());
        int size = Math.min(Math.max(pageSize, 1), 100);
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((long) Math.max(pageNo - 1, 0) * size);
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id, stage, resolution, unit_point_cost, min_count, max_count, effective_at, "
                        + "expires_at, status, version " + base + " ORDER BY effective_at DESC, id DESC LIMIT ? OFFSET ?",
                queryArgs.toArray()).stream().map(this::toRule).toList();
        return success(new PageResult<>(list, total == null ? 0 : total));
    }

    @PostMapping
    @Operation(summary = "新增出图计价规则；价格调整必须追加规则")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.GENERATION_PRICE_MANAGE + "')")
    @Transactional
    public CommonResult<Map<String, Object>> createRule(@RequestBody CreateRuleRequest request) {
        if (request == null || request.effectiveAt() == null
                || request.unitPointCost() < 1 || request.unitPointCost() > 1_000_000_000L
                || request.minCount() < 1 || request.maxCount() < request.minCount()
                || request.maxCount() > generationCountMax
                || request.expiresAt() != null && !request.expiresAt().isAfter(request.effectiveAt())) {
            throw new ServiceException(400, "计价规则参数无效（单次张数上限当前为 " + generationCountMax + "）");
        }
        GenerationImageOptions options = normalize(request.stage(), request.resolution());
        String stage = request.stage().trim().toUpperCase(java.util.Locale.ROOT);
        long ruleId = priceRuleService.createRule(stage, options.resolution(), request.unitPointCost(),
                request.minCount(), request.maxCount(), request.effectiveAt(), request.expiresAt());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stage", stage);
        detail.put("resolution", options.resolution());
        detail.put("unitPointCost", request.unitPointCost());
        detail.put("minCount", request.minCount());
        detail.put("maxCount", request.maxCount());
        detail.put("effectiveAt", request.effectiveAt().toString());
        detail.put("expiresAt", request.expiresAt() == null ? null : request.expiresAt().toString());
        audit(ruleId, "CREATE", detail);
        return success(Map.of("ruleId", String.valueOf(ruleId)));
    }

    @PatchMapping("/{ruleId}/retire")
    @Operation(summary = "停用出图计价规则；历史任务快照保持不变")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.GENERATION_PRICE_MANAGE + "')")
    @Transactional
    public CommonResult<Boolean> retireRule(@PathVariable("ruleId") String ruleId,
            @RequestBody(required = false) Map<String, Object> body) {
        long id = Long.parseLong(ruleId);
        Map<String, Object> detail = jdbc.queryForMap(
                "SELECT stage,resolution,unit_point_cost,min_count,max_count,effective_at,expires_at,version "
                        + "FROM generation_price_rule WHERE id=?", id);
        boolean retired = priceRuleService.retireRule(id);
        if (retired) {
            Map<String, Object> auditDetail = toAuditDetail(detail);
            // D2-4 停用原因必填：原因随审计留痕
            auditDetail.put("reason", body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason")));
            audit(id, "RETIRE", auditDetail);
        }
        return success(retired);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{ruleId}")
    @Operation(summary = "删除已停用的出图计价规则（逻辑删除）；生效中规则必须先停用。历史扣费金额已快照在流水，追溯不受影响")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.GENERATION_PRICE_MANAGE + "')")
    @Transactional
    public CommonResult<Boolean> deleteRule(@PathVariable("ruleId") String ruleId) {
        long id = Long.parseLong(ruleId);
        Map<String, Object> detail = jdbc.queryForMap(
                "SELECT stage,resolution,unit_point_cost,min_count,max_count,effective_at,expires_at,version,status "
                        + "FROM generation_price_rule WHERE id=? AND deleted = FALSE", id);
        int updated = jdbc.update(
                "UPDATE generation_price_rule SET deleted = TRUE, update_time = now() "
                        + "WHERE id = ? AND status = 'RETIRED' AND deleted = FALSE", id);
        boolean deleted = updated == 1;
        if (deleted) {
            audit(id, "DELETE", toAuditDetail(detail));
        }
        return success(deleted);
    }

    private GenerationImageOptions normalize(String stage, String resolution) {
        try {
            return GenerationImageOptions.normalize(stage, resolution, null);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, e.getMessage());
        }
    }

    private void addFilter(List<String> where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.add(column + " = ?");
            args.add(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private Map<String, Object> toRule(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ruleId", String.valueOf(((Number) row.get("id")).longValue()));
        item.put("stage", row.get("stage"));
        item.put("resolution", row.get("resolution"));
        item.put("unitPointCost", ((Number) row.get("unit_point_cost")).longValue());
        item.put("minCount", ((Number) row.get("min_count")).intValue());
        item.put("maxCount", ((Number) row.get("max_count")).intValue());
        item.put("effectiveAt", ((Timestamp) row.get("effective_at")).toInstant().toString());
        item.put("expiresAt", row.get("expires_at") == null ? null
                : ((Timestamp) row.get("expires_at")).toInstant().toString());
        item.put("status", row.get("status"));
        item.put("version", ((Number) row.get("version")).longValue());
        return item;
    }

    private Map<String, Object> toAuditDetail(Map<String, Object> row) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stage", row.get("stage"));
        detail.put("resolution", row.get("resolution"));
        detail.put("unitPointCost", ((Number) row.get("unit_point_cost")).longValue());
        detail.put("minCount", ((Number) row.get("min_count")).intValue());
        detail.put("maxCount", ((Number) row.get("max_count")).intValue());
        detail.put("effectiveAt", ((Timestamp) row.get("effective_at")).toInstant().toString());
        detail.put("expiresAt", row.get("expires_at") == null ? null
                : ((Timestamp) row.get("expires_at")).toInstant().toString());
        detail.put("version", ((Number) row.get("version")).longValue());
        return detail;
    }

    private void audit(long ruleId, String action, Map<String, Object> detail) {
        auditPort.record(AuditEventMessage.builder()
                .eventType("GENERATION_PRICE_RULE").actorType(AuditEventMessage.ActorType.ADMIN)
                .actorId(String.valueOf(SecurityFrameworkUtils.getLoginUserId())).action(action)
                .bizType("generation_price_rule").bizId(String.valueOf(ruleId))
                .result(AuditEventMessage.AuditResult.SUCCESS).detail(detail).build());
    }
}
