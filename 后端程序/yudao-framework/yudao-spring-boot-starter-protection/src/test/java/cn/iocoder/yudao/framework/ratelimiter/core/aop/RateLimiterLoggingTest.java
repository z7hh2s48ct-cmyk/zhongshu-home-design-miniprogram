package cn.iocoder.yudao.framework.ratelimiter.core.aop;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RateLimiterLoggingTest {
 public static class StableKey implements RateLimiterKeyResolver {
  public String resolver(JoinPoint point, RateLimiter rule){return "fixture-stable";}
 }
 @RateLimiter(keyResolver=StableKey.class) void endpoint(){}
 @Test void rejectedCredentialsAreNeverEvaluatedOrLogged() throws Exception {
  var rule=getClass().getDeclaredMethod("endpoint").getAnnotation(RateLimiter.class);
  var point=mock(JoinPoint.class);var signature=mock(Signature.class);
  when(point.getSignature()).thenReturn(signature);when(signature.toShortString()).thenReturn("login()");
  when(point.getArgs()).thenReturn(new Object[]{"Bearer fixture-secret", "fixture-refresh", "fixture-code"});
  var dao=mock(RateLimiterRedisDAO.class);
  var logger=(Logger)LoggerFactory.getLogger(RateLimiterAspect.class);
  var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
  try {
   assertThatThrownBy(()->new RateLimiterAspect(List.of(new StableKey()),dao).beforePointCut(point,rule)).isInstanceOf(RuntimeException.class);
   verify(point,never()).getArgs();
   assertThat(appender.list).hasSize(1);
   assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("fixture-secret","fixture-refresh","fixture-code");
  } finally {logger.detachAppender(appender);appender.stop();}
 }
}
