package cn.iocoder.yudao.framework.web.core.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class BoundedRequestBodyTest {
    @Test void rejectsDeclaredOversizeBeforeReading() throws Exception {
        var request = new MockHttpServletRequest("POST", "/app-api/design/v1/projects") {
            @Override public long getContentLengthLong() { return 1024L * 1024 + 1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("must not read"); }
        };
        request.setContentType("application/json");
        var response = new MockHttpServletResponse();
        new CacheRequestBodyFilter().doFilter(request, response, (r,s) -> { throw new AssertionError("must not dispatch"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void boundsChunkedAndCallbackBodiesBeforeDispatch() throws Exception {
        for (var uri : new String[]{"/app-api/design/v1/projects", "/design/v1/payments/wechat/notify", "/design/v1/payments/wechat/refund-notify"}) {
            int limit = uri.contains("/payments/") ? 65536 : 1048576;
            var request = new MockHttpServletRequest("POST", uri) { @Override public long getContentLengthLong() { return -1; } };
            request.setContentType(uri.endsWith("refund-notify") ? "text/plain" : "application/json"); request.setContent(new byte[limit+1]);
            var response = new MockHttpServletResponse();
            new CacheRequestBodyFilter().doFilter(request, response, (r,s) -> { throw new AssertionError("must not dispatch"); });
            assertThat(response.getStatus()).isEqualTo(413);
        }
    }
    @Test void acceptedBodyRemainsRepeatableAndUtf8() throws Exception {
        var request = new MockHttpServletRequest("POST", "/app-api/design/v1/projects");
        request.setContentType("application/json"); request.setContent("{\"名称\":\"测试\"}".getBytes(StandardCharsets.UTF_8));
        var dispatched = new AtomicBoolean();
        new CacheRequestBodyFilter().doFilter(request, new MockHttpServletResponse(), (r,s) -> {
            assertThat(r.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray());
            assertThat(r.getReader().readLine()).isEqualTo("{\"名称\":\"测试\"}"); dispatched.set(true);
        });
        assertThat(dispatched).isTrue();
    }
}
