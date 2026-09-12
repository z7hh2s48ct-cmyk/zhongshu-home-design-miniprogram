package cn.iocoder.yudao.module.commerce.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/design/v1/generation-price-quotes")
@PermitAll
public class AppGenerationPriceController {
    @Resource private PriceRuleService priceRuleService;
    @Resource private IdentitySessionPort identitySessionPort;

    public record Quote(String stage, int count, long unitPointCost, long totalPointCost,
                        String ruleId, long ruleVersion, String validUntil) { }

    @GetMapping
    public CommonResult<Quote> quote(@RequestParam("stage") String stage, @RequestParam("count") int count,
                                    @RequestHeader(value="Authorization", required=false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        identitySessionPort.requireUnrestricted(token);
        if (!java.util.List.of("FLAT", "ELEVATION").contains(stage) || count < 1 || count > 4)
            throw new ServiceException(400, "生成阶段或数量无效");
        Instant now = Instant.now();
        var rule = priceRuleService.resolve(stage, now).orElseThrow(() -> new ServiceException(1_072_000_001, "当前暂无可用计价规则"));
        if (count < rule.minCount() || count > rule.maxCount()) throw new ServiceException(400, "数量超出当前计价规则范围");
        Instant until = now.plusSeconds(300);
        if (rule.expiresAt() != null && rule.expiresAt().isBefore(until)) until = rule.expiresAt();
        return CommonResult.success(new Quote(stage, count, rule.unitPointCost(), Math.multiplyExact(rule.unitPointCost(), count),
                String.valueOf(rule.id()), rule.version(), until.toString()));
    }
}
