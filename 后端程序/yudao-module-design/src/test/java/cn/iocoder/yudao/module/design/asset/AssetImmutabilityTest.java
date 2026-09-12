package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

/** RG1: actual signed HTTP replay must not change accepted bytes. No live cloud resources. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetImmutabilityTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:RELEASE.2024-01-16T16-07-38Z")
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "localtester")
            .withEnv("MINIO_ROOT_PASSWORD", "local-test-only-password").withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    SimpleDriverDataSource ds; JdbcTemplate jdbc; CosObjectStorageAdapter storage; AssetService assets;
    final HttpClient http = HttpClient.newHttpClient();
    @BeforeAll void setup() {
        ds = new SimpleDriverDataSource(); ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl()); ds.setUsername(PG.getUsername()); ds.setPassword(PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        try (var admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("localtester", "local-test-only-password")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket("immutable-assets").build());
        }
        storage = new CosObjectStorageAdapter(endpoint, "us-east-1", "immutable-assets", "localtester", "local-test-only-password", true, 20000);
        assets = service(new StubContentModerationAdapter());
    }
    AssetService service(ContentModerationPort moderation) {
        return new AssetService(ds, new DataSourceTransactionManager(ds), storage, new AssetContentScanner(), moderation,
                new JdbcDeliveryPort(ds), new RightsGrantService(ds));
    }
    @AfterAll void close() { if (storage != null) storage.close(); }
    byte[] png() throws Exception { var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB), "png", out); return out.toByteArray(); }
    AssetService.UploadTicket upload() throws Exception {
        byte[] png=png(); var ticket=assets.createUploadTicket(101,"USER_SKETCH","image/png",png.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
        assertThat(put(ticket.uploadUrl(),png)).isEqualTo(200); return ticket;
    }
    int put(String url,byte[] body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode(); }
    String key(long id) { return jdbc.queryForObject("SELECT object_key FROM asset WHERE id=?",String.class,id); }
    @Test void acceptedDownloadRemainsSanitizedAfterSignedUploadReplay() throws Exception {
        var ticket=upload(); String raw=key(ticket.assetId());
        assertThat(assets.completeUpload(101,ticket.assetId())).isEqualTo("ACCEPTED");
        String accepted=key(ticket.assetId()); assertThat(accepted).startsWith("accepted/").isNotEqualTo(raw);
        byte[] original;
        try (var stream = storage.getObject(accepted)) { original = stream.readAllBytes(); }
        assertThat(put(ticket.uploadUrl(),"unscanned harmless replacement".getBytes())).isEqualTo(200);
        var download=assets.requestDownloadTicket(101,ticket.assetId());
        String url=assets.resolveDownloadTicket(101,ticket.assetId(),download.getToken());
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(original);
        assertThat(assets.completeUpload(101,ticket.assetId())).isEqualTo("ACCEPTED");
        assertThat(key(ticket.assetId())).isEqualTo(accepted);
    }
    @Test void staleScannerCannotPublishWhenAnotherAttemptOwnsValidation() throws Exception {
        var ticket=upload(); String raw=key(ticket.assetId());
        var old=service((type,bytes)->{ jdbc.update("UPDATE asset SET validation_token=? WHERE id=?",java.util.UUID.randomUUID().toString(),ticket.assetId()); return true; });
        assertThatThrownBy(()->old.completeUpload(101,ticket.assetId())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(key(ticket.assetId())).isEqualTo(raw);
        assertThatThrownBy(()->assets.requestDownloadTicket(101,ticket.assetId())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void overwriteDuringModerationCannotChangeTheApprovedSnapshot() throws Exception {
        var ticket = upload();
        var racing = service((type, bytes) -> {
            try { assertThat(put(ticket.uploadUrl(), "changed during scan".getBytes())).isEqualTo(200); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return true;
        });
        assertThat(racing.completeUpload(101, ticket.assetId())).isEqualTo("ACCEPTED");
        byte[] approved;
        try (var stream = storage.getObject(key(ticket.assetId()))) { approved = stream.readAllBytes(); }
        assertThat(ImageIO.read(new ByteArrayInputStream(approved))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT stored_sha256 FROM asset WHERE id=?", String.class, ticket.assetId()))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(approved)));
    }
    @Test void overwriteBeforeScanDoesNotBecomeAnAcceptedAsset() throws Exception {
        var ticket=upload(); put(ticket.uploadUrl(),new byte[100000]);
        assertThat(assets.completeUpload(101,ticket.assetId())).isEqualTo("REJECTED");
        assertThatThrownBy(()->assets.requestDownloadTicket(101,ticket.assetId())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
}
