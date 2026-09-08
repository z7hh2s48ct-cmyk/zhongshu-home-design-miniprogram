package cn.iocoder.yudao.module.identity.session;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * IdentitySessionPort 实现：把 identity 的 user_session 暴露给其他业务模块
 */
@Component
public class IdentitySessionPortAdapter implements IdentitySessionPort {

    private final UserSessionService sessionService;

    public IdentitySessionPortAdapter(UserSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public Optional<SessionContext> resolveByBearerToken(String bearerToken) {
        return sessionService.validateAccessToken(bearerToken)
                .map(ctx -> new SessionContext(ctx.accountId(), ctx.appid(), ctx.openid(), ctx.restricted()));
    }

}
