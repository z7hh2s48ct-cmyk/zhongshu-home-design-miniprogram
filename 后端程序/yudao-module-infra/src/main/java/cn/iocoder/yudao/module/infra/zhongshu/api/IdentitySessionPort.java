package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.Optional;

/**
 * 身份会话查询端口（供其他业务模块校验小程序自有会话）
 *
 * 实现方：identity 模块（user_session，Bearer token → 账号上下文）；
 * 消费方：design 等模块的 App 控制器。端口放 infra 共享包以避免业务模块互相依赖。
 */
public interface IdentitySessionPort {

    int ACCESS_GRANT_REQUIRED = 1_070_001_001;

    /** token 无效/过期/账号停用返回 empty；restricted 会话同样返回上下文（调用方按需处理） */
    Optional<SessionContext> resolveByBearerToken(String bearerToken);

    /**
     * 解析并强制要求非受限会话（审查 H4：受限会话只允许查准入状态/协议/兑换授权码）。
     * 会话失效返回 401；受限会话返回独立业务码，资源越权仍为 403。
     */
    default SessionContext requireUnrestricted(String bearerToken) {
        SessionContext ctx = resolveByBearerToken(bearerToken)
                .orElseThrow(() -> new cn.iocoder.yudao.framework.common.exception.ServiceException(401, "会话无效或已过期"));
        if (ctx.restricted()) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(ACCESS_GRANT_REQUIRED, "账号未激活，请先兑换授权码");
        }
        return ctx;
    }

    record SessionContext(long accountId, String appid, String openid, boolean restricted) {
    }

}
