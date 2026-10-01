package cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl;

import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import org.aspectj.lang.JoinPoint;

/**
 * IP 级别的限流 Key 解析器，使用方法名 + 连接来源 IP，组装成一个 Key
 *
 * 为了避免 Key 过长，使用 MD5 进行“压缩”
 *
 * @author 芋道源码
 */
public class ClientIpRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String methodName = joinPoint.getSignature().toString();
        var request = ServletUtils.getRequest();
        // Use the connection peer. Client-controlled forwarded headers cannot create buckets.
        String clientIp = request == null ? "unknown" : request.getRemoteAddr();
        return SecureUtil.md5(methodName + ":" + clientIp);
    }

}
