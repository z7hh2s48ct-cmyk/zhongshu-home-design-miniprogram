package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAuthLoginReqVO;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAuthLoginRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import java.util.Map;
import jakarta.validation.Valid;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 微信登录")
@RestController
@RequestMapping("/design/v1/auth")
public class AppAuthController {

    @Resource
    private AccountLoginService accountLoginService;

    @Value("${zhongshu.identity.wechat-appid:stub-appid}")
    private String wechatAppid;

    @Resource
    private cn.iocoder.yudao.module.identity.session.UserSessionService sessionService;

    @PostMapping("/token-refresh")
    @PermitAll
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "刷新会话：旧 access/refresh 吊销并签发新对（grant 状态实时重判）")
    public CommonResult<AppAuthLoginRespVO> refresh(@RequestBody Map<String, String> body) {
        var tokens = sessionService.refresh(body.get("refreshToken"))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("刷新令牌无效或已过期"));
        AppAuthLoginRespVO resp = new AppAuthLoginRespVO();
        resp.setAccessToken(tokens.accessToken());
        resp.setRefreshToken(tokens.refreshToken());
        resp.setExpiresAt(java.time.LocalDateTime.ofInstant(tokens.expiresAt(), java.time.ZoneOffset.UTC));
        resp.setRestricted(false);
        return success(resp);
    }

    @PostMapping("/wechat-login")
    @PermitAll // 会话签发入口本就匿名；滥用防护走 IP 限流
    @RateLimiter(time = 60, count = 30, keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "微信登录：客户端只提交 wx.login 临时 code；未获准用户得到受限会话")
    public CommonResult<AppAuthLoginRespVO> wechatLogin(@Valid @RequestBody AppAuthLoginReqVO reqVO) {
        AccountLoginService.LoginResult login = accountLoginService.login(
                wechatAppid, reqVO.getCode(), null);
        AppAuthLoginRespVO resp = new AppAuthLoginRespVO();
        resp.setAccessToken(login.accessToken());
        resp.setRefreshToken(login.refreshToken());
        resp.setExpiresAt(LocalDateTime.ofInstant(login.expiresAt(), ZoneOffset.UTC));
        resp.setRestricted(login.restricted());
        return success(resp);
    }

}
