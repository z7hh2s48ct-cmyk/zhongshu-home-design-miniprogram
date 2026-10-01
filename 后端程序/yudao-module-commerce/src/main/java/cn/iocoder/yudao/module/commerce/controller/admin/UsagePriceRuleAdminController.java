package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.commerce.enums.PermissionConstants;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/design/v1/usage-price-rules")
public class UsagePriceRuleAdminController {
    private final UsagePointPriceService prices;
    private final AuditPort audit;

    public UsagePriceRuleAdminController(UsagePointPriceService prices, AuditPort audit) {
        this.prices = prices;
        this.audit = audit;
    }

    public record CreateRule(String product, long pointCost, Instant effectiveAt, Instant expiresAt) { }

    @GetMapping
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.USAGE_PRICE_QUERY + "')")
    public CommonResult<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String product, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        var page = prices.listRules(product, status, pageNo, Math.min(Math.max(pageSize, 1), 100));
        return success(new PageResult<>(page.list().stream().map(this::view).toList(), page.total()));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.USAGE_PRICE_MANAGE + "')")
    @org.springframework.transaction.annotation.Transactional
    public CommonResult<Map<String, Object>> create(@RequestBody CreateRule request) {
        if (request == null) throw new IllegalArgumentException("价格规则不能为空");
        long id = prices.createRule(request.product(), request.pointCost(), request.effectiveAt(),
                request.expiresAt(), String.valueOf(SecurityFrameworkUtils.getLoginUserId()));
        var detail = Map.<String, Object>of("product", request.product(), "pointCost", request.pointCost());
        audit.record(AuditEventMessage.builder().eventType("USAGE_PRICE_RULE")
                .actorType(AuditEventMessage.ActorType.ADMIN)
                .actorId(String.valueOf(SecurityFrameworkUtils.getLoginUserId())).action("CREATE")
                .bizType("service_usage_price_rule").bizId(String.valueOf(id))
                .result(AuditEventMessage.AuditResult.SUCCESS).detail(detail).build());
        return success(Map.of("ruleId", String.valueOf(id)));
    }

    @PatchMapping("/{ruleId}/retire")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.USAGE_PRICE_MANAGE + "')")
    @org.springframework.transaction.annotation.Transactional
    public CommonResult<Boolean> retire(@PathVariable long ruleId) {
        boolean changed = prices.retireRule(ruleId, String.valueOf(SecurityFrameworkUtils.getLoginUserId()));
        if (changed) audit.record(AuditEventMessage.builder().eventType("USAGE_PRICE_RULE")
                .actorType(AuditEventMessage.ActorType.ADMIN)
                .actorId(String.valueOf(SecurityFrameworkUtils.getLoginUserId())).action("RETIRE")
                .bizType("service_usage_price_rule").bizId(String.valueOf(ruleId))
                .result(AuditEventMessage.AuditResult.SUCCESS).detail(Map.of()).build());
        return success(changed);
    }

    private Map<String, Object> view(UsagePointPriceService.PriceRule rule) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ruleId", String.valueOf(rule.id())); row.put("product", rule.product());
        row.put("pointCost", rule.pointCost()); row.put("version", rule.version());
        row.put("effectiveAt", rule.effectiveAt().toString());
        row.put("expiresAt", rule.expiresAt() == null ? null : rule.expiresAt().toString());
        row.put("status", rule.status());
        return row;
    }
}
