package cn.iocoder.yudao.framework.test.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FakeHttpServer} 自测（T13-03）。
 *
 * <p>证明 HTTP 假服务器可在无任何真实外部消费下完成「请求/响应往返 + 请求捕获 + 未桩 404 + 错误体透传」，
 * 为 B1（微信 {@code code2session}）、B4（微信支付 API）真实适配器的隔离测试提供可依赖的基线：
 * 适配器 baseUrl 指向本假服务器即可测试协议解析与错误分支，绝不触达真实微信/腾讯端点。
 *
 * <p>防挂起：类级 {@link Timeout}（独立线程模式，超时即中断并失败，兜底任何阻塞）+ 客户端连接超时 + 每请求响应超时，
 * 确保即便响应处理出现回归也只会「快速失败」而不会拖死构建。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FakeHttpServerTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Test
    void servesStubbedResponseAndCapturesRequest() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            // 微信 code2session 成功态：HTTP 200 + JSON(openid/session_key/unionid)
            fake.stubGet("/sns/jscode2session", 200, "application/json; charset=utf-8",
                    "{\"openid\":\"fake-openid\",\"session_key\":\"fake-key\",\"unionid\":\"fake-unionid\"}");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fake.baseUrl()
                            + "/sns/jscode2session?appid=wx-fake&js_code=CODE&grant_type=authorization_code"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("X-Test", "1")
                    .GET()
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("fake-openid").contains("fake-unionid");

            FakeHttpServer.RecordedRequest recorded = fake.lastRequest();
            assertThat(recorded.method()).isEqualTo("GET");
            assertThat(recorded.path()).isEqualTo("/sns/jscode2session");
            assertThat(recorded.query()).contains("appid=wx-fake").contains("js_code=CODE");
            assertThat(recorded.headers()).containsKey("X-Test");
        }
    }

    @Test
    void capturesPostBodyAndHeaders() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            // 微信支付 API 出站调用形态：POST + JSON 体 + 自定义鉴权头
            fake.stubPost("/v3/pay/transactions/jsapi", 200, "application/json", "{\"prepay_id\":\"wx-prepay-1\"}");

            HttpResponse<String> response = client().send(HttpRequest.newBuilder()
                            .uri(URI.create(fake.baseUrl() + "/v3/pay/transactions/jsapi"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Authorization", "WECHATPAY2-SHA256-RSA2048 fake-signature")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"out_trade_no\":\"ORDER-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("wx-prepay-1");

            FakeHttpServer.RecordedRequest recorded = fake.lastRequest();
            assertThat(recorded.method()).isEqualTo("POST");
            assertThat(recorded.body()).isEqualTo("{\"out_trade_no\":\"ORDER-1\"}");
            assertThat(recorded.headers().get("Authorization").get(0)).contains("WECHATPAY2-SHA256-RSA2048");
        }
    }

    @Test
    void unstubbedPathReturns404WithoutHanging() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder().uri(URI.create(fake.baseUrl() + "/missing"))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(fake.requests()).hasSize(1);
        }
    }

    @Test
    void deliversErrorBodyVerbatimSoAdapterCanBranchOnErrcode() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            // 微信 code2session 失败态：真实协议为 HTTP 200 + errcode/errmsg，适配器须据 body 判错而非仅看状态码
            fake.stubGet("/sns/jscode2session", 200, "application/json",
                    "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

            HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder().uri(URI.create(fake.baseUrl() + "/sns/jscode2session"))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("40029").contains("invalid code");
        }
    }

    @Test
    void resetClearsStubsAndRecordedRequests() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            fake.stubGet("/ping", 200, "text/plain", "pong");
            client().send(HttpRequest.newBuilder().uri(URI.create(fake.baseUrl() + "/ping"))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(fake.requests()).hasSize(1);

            fake.reset();
            assertThat(fake.requests()).isEmpty();
            // 清桩后同一路径回落 404
            HttpResponse<String> afterReset = client().send(
                    HttpRequest.newBuilder().uri(URI.create(fake.baseUrl() + "/ping"))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(afterReset.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void lastRequestThrowsWhenNothingReceived() throws Exception {
        try (FakeHttpServer fake = FakeHttpServer.start()) {
            assertThatThrownBy(fake::lastRequest).isInstanceOf(IllegalStateException.class);
            assertThat(fake.baseUrl()).startsWith("http://127.0.0.1:").endsWith(String.valueOf(fake.port()));
        }
    }

}
