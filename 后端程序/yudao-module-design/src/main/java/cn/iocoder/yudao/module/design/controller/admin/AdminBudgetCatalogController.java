package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetCatalogVO.*;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** Configuration does not imply publication. All writes take their actor from the authenticated session. */
@RestController
@RequestMapping("/design/v1/budget")
public class AdminBudgetCatalogController {
    @Resource
    private BudgetCatalogService budgetCatalogService;
    @Value("${zhongshu.design.budget-admin-tenant-id:1}")
    private long adminTenantId = 1L;

    @GetMapping("/regions")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<PageResult<Region>> regions(@RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        actor();
        return success(budgetCatalogService.regions(pageNo, pageSize));
    }

    @PostMapping("/regions")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Region> createRegion(@RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.createRegion(actor(), key, body));
    }

    @PatchMapping("/regions/{regionId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Region> updateRegion(@PathVariable String regionId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.updateRegion(actor(), key, regionId, body));
    }

    @GetMapping("/items")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<PageResult<Item>> items(@RequestParam(required = false) String category, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        actor();
        return success(budgetCatalogService.items(category, pageNo, pageSize));
    }

    @PostMapping("/items")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Item> createItem(@RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.createItem(actor(), key, body));
    }

    @PatchMapping("/items/{itemId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Item> updateItem(@PathVariable String itemId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.updateItem(actor(), key, itemId, body));
    }

    @GetMapping("/options")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<PageResult<Option>> options(@RequestParam(required = false) String itemId, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        actor();
        return success(budgetCatalogService.options(itemId, pageNo, pageSize));
    }

    @PostMapping("/options")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Option> createOption(@RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.createOption(actor(), key, body));
    }

    @PatchMapping("/options/{optionId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Option> updateOption(@PathVariable String optionId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.updateOption(actor(), key, optionId, body));
    }

    @GetMapping("/prices")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<PageResult<Price>> prices(@RequestParam String regionCode, @RequestParam(required = false) String optionId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        actor();
        return success(budgetCatalogService.prices(regionCode, optionId, status, pageNo, pageSize));
    }

    @PostMapping("/prices")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Price> createPrice(@RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.createPrice(actor(), key, body));
    }

    @PatchMapping("/prices/{priceId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<Price> updatePrice(@PathVariable String priceId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.updatePrice(actor(), key, priceId, body));
    }

    @PostMapping("/prices/{priceId}/publish")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_PRICE_PUBLISH + "')")
    public CommonResult<Price> publishPrice(@PathVariable String priceId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.publishPrice(actor(), key, priceId, body));
    }

    @PostMapping("/prices/{priceId}/disable")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_PRICE_PUBLISH + "')")
    public CommonResult<Price> disablePrice(@PathVariable String priceId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(budgetCatalogService.disablePrice(actor(), key, priceId, body));
    }

    private long actor() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || user.getId() <= 0 || user.getTenantId() == null
                || user.getTenantId() != adminTenantId
                || (user.getVisitTenantId() != null && !user.getVisitTenantId().equals(user.getTenantId()))) {
            throw new AccessDeniedException("仅所属公司管理员可访问预算配置，不支持跨租户模拟");
        }
        return user.getId();
    }
}
