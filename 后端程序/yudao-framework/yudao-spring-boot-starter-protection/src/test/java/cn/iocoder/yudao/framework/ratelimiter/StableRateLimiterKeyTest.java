package cn.iocoder.yudao.framework.ratelimiter;

import cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.*;
import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StableRateLimiterKeyTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    JoinPoint point() { var p=mock(JoinPoint.class);var s=mock(Signature.class);when(p.getSignature()).thenReturn(s);when(s.toString()).thenReturn("fixture-login");return p; }
    @Test void peerBucketIgnoresPayloadTokenAndForgedForwardedHeaders() {
        var p=point();var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var resolver=new ClientIpRateLimiterKeyResolver();String key=resolver.resolver(p,null);
        request.addHeader("X-Forwarded-For","198.51.100.99");request.addHeader("Forwarded","for=203.0.113.1");
        when(p.getArgs()).thenReturn(new Object[]{"new-code","rotated-token"});
        assertThat(resolver.resolver(p,null)).isEqualTo(key);verify(p,never()).getArgs();
        request.setRemoteAddr("127.0.0.8");assertThat(resolver.resolver(p,null)).isNotEqualTo(key);
    }
    @Test void defaultAndSecurityUserBucketsDoNotDependOnRequestArguments() {
        var p=point();var global=new DefaultRateLimiterKeyResolver();var user=new UserRateLimiterKeyResolver();
        String a=global.resolver(p,null),b=user.resolver(p,null);when(p.getArgs()).thenReturn(new Object[]{"another"});
        assertThat(global.resolver(p,null)).isEqualTo(a);assertThat(user.resolver(p,null)).isEqualTo(b);verify(p,never()).getArgs();
    }
    @Test void verifiedAccountLimitSurvivesBearerRotationAndIsolatesAccountsAndOperations() {
        var dao=mock(RateLimiterRedisDAO.class);when(dao.tryAcquire(anyString(),anyInt(),anyInt(),any())).thenReturn(true);
        var limiter=new VerifiedAccountRateLimiter(dao);limiter.check("price",901,10,60);limiter.check("price",901,10,60);
        limiter.check("price",902,10,60);limiter.check("redemption",901,10,60);
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);verify(dao,times(4)).tryAcquire(captor.capture(),eq(10),eq(60),eq(TimeUnit.SECONDS));
        var keys=captor.getAllValues();assertThat(keys.get(0)).isEqualTo(keys.get(1)).isNotEqualTo(keys.get(2)).isNotEqualTo(keys.get(3));
        when(dao.tryAcquire(anyString(),anyInt(),anyInt(),any())).thenReturn(false);
        assertThatThrownBy(()->limiter.check("price",901,10,60)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->limiter.check("price",0,10,60)).isInstanceOf(IllegalArgumentException.class);
    }
}
