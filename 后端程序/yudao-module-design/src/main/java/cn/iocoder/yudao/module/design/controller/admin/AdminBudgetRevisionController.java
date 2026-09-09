package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.budget.AdminBudgetRevisionService;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetRevisionVO.*;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** Internal drafts are separate immutable revisions, not implicitly published owner quotes. */
@RestController
@RequestMapping("/design/v1/budget/estimates")
public class AdminBudgetRevisionController {
    @Resource
    private AdminBudgetRevisionService adminBudgetRevisionService;
    @Value("${zhongshu.design.budget-admin-tenant-id:1}")
    private long adminTenantId = 1L;

    @GetMapping
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<PageResult<Summary>> list(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) String completeness, @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        actor();
        return success(adminBudgetRevisionService.list(projectId, completeness, pageNo, pageSize));
    }

    @GetMapping("/{budgetId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<Detail> get(@PathVariable String budgetId, @RequestParam(required = false) String revisionId) {
        actor();
        return success(adminBudgetRevisionService.get(budgetId, revisionId));
    }

    @GetMapping("/{budgetId}/revisions")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_QUERY + "')")
    public CommonResult<List<RevisionRef>> history(@PathVariable String budgetId) {
        actor();
        return success(adminBudgetRevisionService.history(budgetId));
    }

    @PostMapping("/{budgetId}/revisions")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_EDIT + "')")
    public CommonResult<Detail> revise(@PathVariable String budgetId, @RequestHeader("Idempotency-Key") String key,
                                        @RequestBody Map<String, Object> body) {
        return success(adminBudgetRevisionService.revise(actor(), budgetId, key, body));
    }

    @PostMapping("/{budgetId}/items/{lineId}/template")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.BUDGET_EDIT + "') and @ss.hasPermission('" + PermissionConstants.BUDGET_CONFIGURE + "')")
    public CommonResult<TemplateResult> saveAsTemplate(@PathVariable String budgetId, @PathVariable String lineId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        return success(adminBudgetRevisionService.saveAsTemplate(actor(), budgetId, lineId, key, body));
    }

    private long actor() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || user.getId() <= 0 || user.getTenantId() == null
                || user.getTenantId() != adminTenantId
                || (user.getVisitTenantId() != null && !user.getVisitTenantId().equals(user.getTenantId()))) {
            throw new AccessDeniedException("仅所属公司管理员可访问预算修订，不支持跨租户模拟");
        }
        return user.getId();
    }
}
