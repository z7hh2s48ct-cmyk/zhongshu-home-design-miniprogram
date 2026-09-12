package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetCatalogRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/design/v1/budget")
@PermitAll
public class AppBudgetCatalogController {
    @Resource
    private BudgetCatalogService budgetCatalogService;
    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping("/regions")
    public CommonResult<List<AppBudgetCatalogRespVO.Region>> regions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUnrestricted(authorization);
        return success(budgetCatalogService.publicRegions());
    }

    @GetMapping("/options")
    public CommonResult<AppBudgetCatalogRespVO> options(@RequestParam String regionCode,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUnrestricted(authorization);
        return success(budgetCatalogService.publicOptions(regionCode));
    }

    private void requireUnrestricted(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        identitySessionPort.requireUnrestricted(token);
    }
}
