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
        assertThatThrownBy(()->adapter.review("AI_OUTPUT",new byte[7*1024*1024+1])).isInstanceOf(ModerationUnavailableException.class);
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

    @Test void largeFullImageUsesPrivateShortSignedSourceAndExactPassWithoutInlineBase64() throws Exception {
        var storage=org.mockito.Mockito.mock(ObjectStoragePort.class);
        org.mockito.Mockito.when(storage.supportsExternalModerationSource()).thenReturn(true);
        org.mockito.Mockito.when(storage.presignDownloadUrl(org.mockito.ArgumentMatchers.startsWith("moderation-input/"),org.mockito.ArgumentMatchers.eq(120L)))
                .thenReturn("https://fixture-private.cos.ap-guangzhou.myqcloud.com/moderation-input/image?fake-signature=not-real");
        var full=new TencentImsModerationAdapter("test-id","test-key","ap-guangzhou","test-policy",
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),HttpClient.newHttpClient(),()->{},Clock.systemUTC(),storage);
        byte[] bytes=new byte[TencentImsModerationAdapter.INLINE_LIMIT+1];bytes[0]=(byte)0x89;bytes[1]='P';bytes[2]='N';bytes[3]='G';bytes[bytes.length-1]=24;
        for(String suggestion:java.util.List.of("Pass","Block","Review")) {
            response.set("{\"Response\":{\"RequestId\":\"large-test\",\"Suggestion\":\""+suggestion+"\"}}");
            assertThat(full.review("AI_OUTPUT",bytes).accepted()).isEqualTo("Pass".equals(suggestion));
            assertThat(requestBody.get()).contains("\"FileUrl\":\"https://fixture-private.cos").doesNotContain("FileContent");
        }
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(3)).putObject(org.mockito.ArgumentMatchers.startsWith("moderation-input/"),org.mockito.ArgumentMatchers.same(bytes));
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(3)).deleteModerationObject(org.mockito.ArgumentMatchers.startsWith("moderation-input/"));
        assertThat(full.review("AI_OUTPUT",new byte[TencentImsModerationAdapter.SOURCE_LIMIT+1]).accepted()).isFalse();
        assertThat(full.supportsUpload("CASE_PDF")).isFalse();assertThat(full.supportsUpload("CASE_IMAGE")).isTrue();
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test void unsupportedPdfIsRejectedBeforeTicketDatabaseOrStorageAccess() {
        var ds=org.mockito.Mockito.mock(javax.sql.DataSource.class);var storage=org.mockito.Mockito.mock(ObjectStoragePort.class);
        var assets=new AssetService(ds,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),storage,
                new AssetContentScanner(),adapter,org.mockito.Mockito.mock(cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort.class),null);
        assertThatThrownBy(()->assets.createUploadTicket(7,"CASE_PDF","application/pdf",100,"a".repeat(64)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class).hasMessageContaining("不支持");
        org.mockito.Mockito.verifyNoInteractions(ds,storage);
    }

    @Test void failedSignedSourceTransportRemainsUncertainAndCleansTemporaryObject() {
        var storage=org.mockito.Mockito.mock(ObjectStoragePort.class);
        org.mockito.Mockito.when(storage.supportsExternalModerationSource()).thenReturn(true);
        org.mockito.Mockito.when(storage.presignDownloadUrl(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong())).thenReturn("local://not-external");
        var full=new TencentImsModerationAdapter("test-id","test-key","ap-guangzhou","test-policy",
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),HttpClient.newHttpClient(),()->{},Clock.systemUTC(),storage);
        byte[] bytes=new byte[TencentImsModerationAdapter.INLINE_LIMIT+1];bytes[0]=(byte)0x89;bytes[1]='P';bytes[2]='N';bytes[3]='G';
        assertThatThrownBy(()->full.review("AI_OUTPUT",bytes)).isInstanceOf(ModerationUnavailableException.class);
        org.mockito.Mockito.verify(storage).deleteModerationObject(org.mockito.ArgumentMatchers.startsWith("moderation-input/"));
        assertThat(requests.get()).isZero();
    }

    @Test void legitimateLarge4kPngIsReviewedWholeAndKeepsOriginalDimensionsAndContent() throws Exception {
        var image=new java.awt.image.BufferedImage(3840,2160,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random=new java.util.Random(901);for(int y=0;y<2160;y++)for(int x=0;x<3840;x++)image.setRGB(x,y,y<1080?random.nextInt(1<<24):0xffffff);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var report=new AssetContentScanner().scan("image/png",bytes.toByteArray());
        assertThat(report.passed()).isTrue();assertThat(report.width()).isEqualTo(3840);assertThat(report.height()).isEqualTo(2160);
        assertThat(report.sanitizedContent().length).isBetween(TencentImsModerationAdapter.INLINE_LIMIT+1,TencentImsModerationAdapter.SOURCE_LIMIT);
        var storage=org.mockito.Mockito.mock(ObjectStoragePort.class);
        org.mockito.Mockito.when(storage.supportsExternalModerationSource()).thenReturn(true);
        org.mockito.Mockito.when(storage.presignDownloadUrl(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(120L)))
                .thenReturn("https://fixture-private.cos.ap-guangzhou.myqcloud.com/moderation-input/image?fake-signature=not-real");
        var full=new TencentImsModerationAdapter("test-id","test-key","ap-guangzhou","test-policy",
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),HttpClient.newHttpClient(),()->{},Clock.systemUTC(),storage);
        response.set("{\"Response\":{\"RequestId\":\"full-4k\",\"Suggestion\":\"Pass\"}}");
        assertThat(full.review("AI_OUTPUT",report.sanitizedContent()).accepted()).isTrue();
        org.mockito.Mockito.verify(storage).putObject(org.mockito.ArgumentMatchers.startsWith("moderation-input/"),org.mockito.ArgumentMatchers.same(report.sanitizedContent()));
        org.mockito.Mockito.verify(storage).deleteModerationObject(org.mockito.ArgumentMatchers.startsWith("moderation-input/"));
        var preserved=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(report.sanitizedContent()));
        assertThat(preserved.getWidth()).isEqualTo(3840);assertThat(preserved.getHeight()).isEqualTo(2160);
        assertThat(requestBody.get()).contains("FileUrl").doesNotContain("FileContent");
    }
}
