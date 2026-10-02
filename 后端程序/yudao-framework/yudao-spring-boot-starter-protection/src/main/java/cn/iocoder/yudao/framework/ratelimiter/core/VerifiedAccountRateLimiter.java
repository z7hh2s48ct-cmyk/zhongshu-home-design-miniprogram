package cn.iocoder.yudao.framework.ratelimiter.core;

import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import java.util.concurrent.TimeUnit;

/** Call only after the application's session service has validated the account. */
public class VerifiedAccountRateLimiter {
    private final RateLimiterRedisDAO redis;
    public VerifiedAccountRateLimiter(RateLimiterRedisDAO redis) { this.redis = redis; }
    public void check(String operation, long verifiedAccountId, int count, int seconds) {
        if (verifiedAccountId <= 0 || operation == null || operation.isBlank())
            throw new IllegalArgumentException("Verified account required");
        String key = SecureUtil.sha256("verified-account:" + operation + ":" + verifiedAccountId);
        if (!Boolean.TRUE.equals(redis.tryAcquire(key, count, seconds, TimeUnit.SECONDS)))
            throw new ServiceException(GlobalErrorCodeConstants.TOO_MANY_REQUESTS);
    }
}
