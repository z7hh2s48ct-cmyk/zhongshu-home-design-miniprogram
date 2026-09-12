package cn.iocoder.yudao.module.design.asset;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class TencentImsModerationAdapterTest {
    private HttpServer server;
    private final AtomicReference<String> response=new AtomicReference<>();
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicReference<String> requestBody=new AtomicReference<>();
    private TencentImsModerationAdapter adapter;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        adapter=new TencentImsModerationAdapter("test-ims-id","test-ims-key","ap-guangzhou","test-policy",
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),HttpClient.newHttpClient(),()->{},Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"),ZoneOffset.UTC));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void onlyExactPassSucceedsAndPersistsSafeEvidence() {
        for(String suggestion:java.util.List.of("Pass","Block","Review")) {
            response.set("{\"Response\":{\"RequestId\":\"test-request-1\",\"Suggestion\":\""+suggestion+"\"}}");
            var decision=adapter.review("USER_SKETCH",new byte[]{1,2,3});
            assertThat(decision.accepted()).isEqualTo("Pass".equals(suggestion));
            assertThat(decision.requestId()).isEqualTo("test-request-1");assertThat(decision.policy()).isEqualTo("test-policy");
            assertThat(requestBody.get()).contains("\"BizType\":\"test-policy\"").contains("\"FileContent\":\"AQID\"").doesNotContain("test-ims-key");
        }
    }
    @Test void unknownMissingAndVendorErrorsRemainUncertain() {
        for(String body:java.util.List.of("{}","{\"Response\":{\"Suggestion\":\"Pass\"}}","{\"Response\":{\"Error\":{\"Code\":\"InternalError\"}}}","invalid-json")) {
            response.set(body);assertThatThrownBy(()->adapter.review("USER_SKETCH",new byte[]{1})).isInstanceOf(ModerationUnavailableException.class).hasMessageNotContaining(body);
        }
    }
    @Test void oversizedResponseAndUnsupportedInputNeverPass() {
        response.set("x".repeat(140*1024));
        assertThatThrownBy(()->adapter.review("AI_OUTPUT",new byte[]{1})).isInstanceOf(ModerationUnavailableException.class);
        assertThat(adapter.review("CASE_PDF",new byte[]{1}).accepted()).isFalse();
        assertThat(adapter.review("AI_OUTPUT",new byte[7*1024*1024+1]).accepted()).isFalse();
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void canonicalSignatureHasUtcDateScopeAndChangesWithBody() throws Exception {
        String first=adapter.authorization("one".getBytes(StandardCharsets.UTF_8),1789257600L);
        assertThat(first).contains("/2026-09-13/ims/tc3_request").contains("SignedHeaders=content-type;host, Signature=");
        assertThat(first.substring(first.indexOf("Signature=")+10)).matches("[a-f0-9]{64}");
        assertThat(adapter.authorization("two".getBytes(StandardCharsets.UTF_8),1789257600L)).isNotEqualTo(first);
    }
    @Test void callerTimeoutCancelsOutstandingTransportBeforeReleasingCapacity() {
        var pending=new java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<byte[]>>() {
            @Override public java.net.http.HttpResponse<byte[]> get(long timeout,java.util.concurrent.TimeUnit unit)
                    throws java.util.concurrent.TimeoutException {throw new java.util.concurrent.TimeoutException();}
        };
        var client=org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.doReturn(pending).when(client).sendAsync(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class));
        var limited=new TencentImsModerationAdapter("test-id","test-key","ap-guangzhou","test-policy",URI.create("https://ims.tencentcloudapi.com/"),client,()->{},Clock.systemUTC());
        assertThatThrownBy(()->limited.review("AI_OUTPUT",new byte[]{1})).isInstanceOf(ModerationUnavailableException.class);
        assertThat(pending.isCancelled()).isTrue();
    }
}
