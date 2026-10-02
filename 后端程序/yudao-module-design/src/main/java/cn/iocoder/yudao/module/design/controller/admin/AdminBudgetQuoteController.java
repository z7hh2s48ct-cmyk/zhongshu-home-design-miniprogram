package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.budget.BudgetQuoteService;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetQuoteVO.Quote;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/design/v1")
public class AdminBudgetQuoteController {
    @Resource
    private BudgetQuoteService budgetQuoteService;
    @Value("${zhongshu.design.budget-admin-tenant-id:1}")
    private long adminTenantId = 1L;

    @GetMapping("/budget/estimates/{budgetId}/quotes")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<List<Quote>> list(@PathVariable String budgetId) {
        actor();
        return success(budgetQuoteService.adminList(budgetId));
    }

    @GetMapping("/budget/quotes/{quoteId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<Quote> get(@PathVariable String quoteId) {
        actor();
        return success(budgetQuoteService.adminGet(quoteId));
    }

    @PostMapping("/budget/estimates/{budgetId}/quotes")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUOTE + "')")
    public CommonResult<Quote> create(@PathVariable String budgetId, @RequestHeader("Idempotency-Key") String key,
                                      @RequestBody Map<String, Object> body) {
        return success(budgetQuoteService.create(actor(), budgetId, key, body));
    }

    @PostMapping("/budget/quotes/{quoteId}/publish")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUOTE_PUBLISH + "')")
    public CommonResult<Quote> publish(@PathVariable String quoteId, @RequestHeader("Idempotency-Key") String key,
                                       @RequestBody Map<String, Object> body) {
        return success(budgetQuoteService.publish(actor(), quoteId, key, body));
    }

    @PostMapping("/budget/quotes/{quoteId}/withdraw")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUOTE_PUBLISH + "')")
    public CommonResult<Quote> withdraw(@PathVariable String quoteId, @RequestHeader("Idempotency-Key") String key,
                                        @RequestBody Map<String, Object> body) {
        return success(budgetQuoteService.withdraw(actor(), quoteId, key, body));
    }

    @PostMapping("/budget/quotes/{quoteId}/discard")
    @Operation(summary = "作废草稿（含 stale 草稿）：仅 DRAFT 可作废，业主端永不展示")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUOTE_PUBLISH + "')")
    public CommonResult<Quote> discard(@PathVariable String quoteId, @RequestHeader("Idempotency-Key") String key,
                                       @RequestBody Map<String, Object> body) {
        return success(budgetQuoteService.discard(actor(), quoteId, key, body));
    }

    private long actor() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || user.getId() <= 0 || user.getTenantId() == null
                || user.getTenantId() != adminTenantId
                || (user.getVisitTenantId() != null && !user.getVisitTenantId().equals(user.getTenantId())))
            throw new AccessDeniedException("仅所属公司管理员可管理预算报价，不支持跨租户模拟");
        return user.getId();
    }
}
