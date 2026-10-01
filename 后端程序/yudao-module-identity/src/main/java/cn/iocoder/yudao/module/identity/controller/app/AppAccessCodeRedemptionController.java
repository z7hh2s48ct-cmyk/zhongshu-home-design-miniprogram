package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessCodeRedeemReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessGrantRespVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 授权码兑换（页面 16）")
@RestController
@RequestMapping("/design/v1/access-code-redemptions")
@PermitAll // 自有 user_session Bearer 校验在方法内强制执行
public class AppAccessCodeRedemptionController {

    @Resource
    private AccessCodeRedemptionService redemptionService;

    @Resource
    private cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter verifiedAccountRateLimiter;

    @Resource
    private UserSessionService sessionService;

    @Resource
    private cn.iocoder.yudao.module.identity.accesscode.AccessGrantService accessGrantService;

    /**
     * 兑换授权码：需要登录会话（Bearer token）；按会话身份限流（Redis），
     * 兑换成功后会话授权态实时升级为正式。
     */
    @PostMapping
    @Operation(summary = "兑换授权码并绑定微信身份")
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<AppAccessGrantRespVO> redeem(@Valid @RequestBody AppAccessCodeRedeemReqVO reqVO,
                                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        verifiedAccountRateLimiter.check("access-code-redemption", context.accountId(), 10, 60);
        AccessCodeRedemptionService.RedemptionResult result = redemptionService.redeem(
                context.appid(), context.openid(), null, reqVO.getAccessCode());
        return success(toGrantVO(result.accountId(), result.grantId()));
    }

    private UserSessionService.AccessContext requireContext(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new cn.iocoder.yudao.framework.common.exception.ServiceException(401, "会话无效或已过期"));
    }

    private AppAccessGrantRespVO toGrantVO(long accountId, long grantId) {
        AppAccessGrantRespVO vo = new AppAccessGrantRespVO();
        var grant = accessGrantService.findGrant(grantId);
        grant.ifPresent(g -> {
            vo.setStatus(g.status());
            vo.setGrantedAt(g.grantedAt() == null ? null : LocalDateTime.ofInstant(g.grantedAt(), ZoneOffset.UTC));
        });
        vo.setAllowedActions(List.of("USE_PRODUCT"));
        return vo;
    }

}
