package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.design.budget.BudgetAccountPriceService;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.controller.app.vo.AppMyPriceRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** T14: 我的当地单价（账号覆盖价）。覆盖跟账号永久生效；基准价目录只读，用户侧无 budget_item_price 写通道。 */
@Tag(name = "小程序 - 我的当地单价（账号覆盖价）")
@RestController
@RequestMapping("/design/v1/budget")
@PermitAll
public class AppAccountPriceController {

    @Resource
    private BudgetAccountPriceService accountPriceService;
    @Resource
    private IdentitySessionPort identitySessionPort;

    @Resource
    private cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter verifiedAccountRateLimiter;

    @GetMapping("/my-prices")
    @Operation(summary = "逐项列出基准价与本人覆盖价；基准缺价且无覆盖标记为 MISSING")
    public CommonResult<AppMyPriceRespVO.Items> myPrices(@RequestParam String regionCode,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(accountPriceService.listPrices(requireAccountId(authorization), regionCode));
    }

    @PutMapping("/my-prices/{optionId}")
    @Operation(summary = "设置本人单价覆盖：仅标准目录选项，金额为 1～1亿分")
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AppMyPriceRespVO> setMyPrice(@PathVariable String optionId, @RequestParam String regionCode,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = requireAccountId(authorization);
        verifiedAccountRateLimiter.check("budget-my-price", accountId, 10, 60);
        return success(accountPriceService.upsert(accountId, regionCode,
                BudgetInputs.positiveId(optionId), body));
    }

    @DeleteMapping("/my-prices/{optionId}")
    @Operation(summary = "恢复默认：移除本人覆盖价，后续测算回基准价")
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AppMyPriceRespVO> resetMyPrice(@PathVariable String optionId, @RequestParam String regionCode,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = requireAccountId(authorization);
        verifiedAccountRateLimiter.check("budget-my-price", accountId, 10, 60);
        return success(accountPriceService.reset(accountId, regionCode,
                BudgetInputs.positiveId(optionId)));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 与预算端点同一门禁：受限（未激活）会话不可读写我的当地单价
        return identitySessionPort.requireUnrestricted(token).accountId();
    }
}
