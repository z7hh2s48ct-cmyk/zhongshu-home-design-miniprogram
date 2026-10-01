package cn.iocoder.yudao.module.design.asset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2 T13-10/T13-11 合同测试（真实 MinIO，S3 兼容）：用<b>真实 AWS S3 SDK v2 与真实 S3 协议</b>验证
 * {@link CosObjectStorageAdapter} 的私有桶语义，全程不触达真实腾讯 COS、不产生真实消费。
 *
 * <p>为何用 MinIO（{@link GenericContainer}）而非 mock：T13-10 要求「验证私有 PUT/GET/HEAD、签名、region/bucket/endpoint」，
 * 这些是 SDK 与 S3 服务端之间的<b>协议级</b>行为（预签名 URL 能否被真实端接受、HEAD 的 404/403 状态码、Content-Type 往返），
 * mock 无法证明真实兼容性。MinIO 是与 COS 同协议的 S3 兼容端，path-style 寻址下可完整验证上述行为；
 * 真实 COS 特有项（虚拟主机式寻址、{@code cos.{region}.myqcloud.com} endpoint 格式、生命周期）属凭据依赖，待 T13-15 真实验收。
 *
 * <p>覆盖（对齐 T13-10/T13-11 验收）：
 * <ul>
 *   <li>服务端直写 putObject → existsWithSize（命中/大小不一致）→ getObject 读回原文（真实 PUT/HEAD/GET）；</li>
 *   <li>Content-Type 按 key 扩展名推断（消毒回写不丢形象 MIME）；</li>
 *   <li><b>预签名 PUT URL 供客户端二进制直传</b>（带 Content-Type，不被签名约束）→ 真实 HTTP PUT 落对象（T13-12 兼容前提）；</li>
 *   <li><b>预签名 GET URL 供短期下载</b> → 真实 HTTP GET 取回原文（私有桶唯一读取路径）；</li>
 *   <li>404 判别：existsWithSize 返回 false（确属不存在）、getObject 抛 NOT_FOUND（不静默）；</li>
 *   <li><b>403 判别（T13-11 核心）</b>：错误凭据→ACCESS_DENIED，<b>绝不返回 false 当作「不存在」</b>；</li>
 *   <li>私有桶：匿名（无凭据/无预签名）GET 被拒（403），证明未开公有读（T13-10「禁默认公开桶」）；</li>
 *   <li>超时判别：{@link ApiCallTimeoutException}→TIMEOUT（确定性 mock，无需真实网络停滞），绝不当作「不存在」。</li>
 * </ul>
 *
 * <p>测试凭据为本地临时容器口令（非真实密钥）；容器随测试类启停，端口随机映射，绝不外泄。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CosObjectStorageAdapterContractTest {

    /**
     * 固定 MinIO 版本（可复现，避免 latest 漂移）。
     * 官方 minio/minio 已下架 Docker Hub（2026-09-11~14 删除），quay.io 自 2026-09-24 起要求鉴权，
     * 故改用社区重建镜像 pgsty/minio（同源、匿名可拉、S3 API 行为一致）。
     */
    private static final String MINIO_IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z";
    /** 本地临时容器 root 口令（MinIO 要求 ≥8 字符）；仅测试用，非任何真实凭据。 */
    private static final String ROOT_USER = "zhongshuadmin";
    private static final String ROOT_PASSWORD = "zhongshu-local-test-secret";
    /** MinIO 默认区域。 */
    private static final String REGION = "us-east-1";
    /** DNS 合规的小写桶名。 */
    private static final String BUCKET = "zhongshu-private-test";

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withCommand("server", "/data")
            // 以就绪探针为等待条件（比日志匹配更稳）：MinIO S3 API 就绪后 /minio/health/ready 返回 200。
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(180)));

    private final HttpClient http = HttpClient.newHttpClient();
    private String endpoint;
    /** 管理用 S3Client：建桶、校验 Content-Type 等（与被测适配器独立，作客观第三方核验）。 */
    private S3Client adminS3;
    /** 被测适配器：用公开构造器（真实 endpoint/凭据/path-style），完整走生产装配路径 buildS3/buildPresigner。 */
    private CosObjectStorageAdapter adapter;

    @BeforeAll
    void setUp() {
        endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        adminS3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ROOT_USER, ROOT_PASSWORD)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        adminS3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        adapter = new CosObjectStorageAdapter(endpoint, REGION, BUCKET, ROOT_USER, ROOT_PASSWORD, true, 20_000);
    }

    @AfterAll
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        if (adminS3 != null) {
            adminS3.close();
        }
    }

    /** T13-11：putObject 服务端直写 → existsWithSize 命中（大小一致）/不一致 → getObject 读回原文，全链路真实 S3 协议。 */
    @Test
    void putThenExistsThenGetRoundTrips() throws Exception {
        String key = "roundtrip/diagram.png";
        byte[] content = "众墅之家 COS 合同测试字节内容".getBytes(StandardCharsets.UTF_8);
        adapter.putObject(key, content);

        assertThat(adapter.existsWithSize(key, content.length)).as("大小一致应命中").isTrue();
        assertThat(adapter.existsWithSize(key, content.length + 1)).as("大小不一致必须为 false").isFalse();

        try (InputStream in = adapter.getObject(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test void temporaryModerationObjectsArePrivateAndDeletedWithoutTouchingAcceptedAssets() throws Exception {
        String temporary="moderation-input/fixture/image.png",accepted="accepted/fixture/image.png";
        byte[] bytes={1,2,3};adapter.putObject(temporary,bytes);adapter.putObject(accepted,bytes);
        assertThat(adapter.supportsExternalModerationSource()).isTrue();
        var signed=adapter.presignDownloadUrl(temporary,120);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(signed)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(bytes);
        adapter.deleteModerationObject(temporary);assertThat(adapter.existsWithSize(temporary,3)).isFalse();
        assertThatThrownBy(()->adapter.deleteModerationObject(accepted)).isInstanceOf(IllegalArgumentException.class);
        assertThat(adapter.existsWithSize(accepted,3)).isTrue();
        assertThat(adminS3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(accepted).build()).contentType()).isEqualTo("image/png");
    }

    /** T13-11：Content-Type 按 key 扩展名推断——消毒回写不丢形象 MIME（否则预签名 GET 内联展示异常）。 */
    @Test
    void putObjectInfersContentTypeFromKey() {
        String key = "content-type/photo.jpg";
        adapter.putObject(key, new byte[]{1, 2, 3});
        HeadObjectResponse head = adminS3.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(head.contentType()).isEqualTo("image/jpeg");
    }

    /**
     * T13-12 兼容前提：预签名 PUT URL 供客户端<b>二进制直传</b>。客户端设置 Content-Type（与 avatar-upload.js 一致），
     * 而适配器<b>不签名 Content-Type</b>（解耦服务端策略与客户端申报 MIME），故真实 HTTP PUT 应被 S3 端接受（200）并落对象。
     */
    @Test
    void presignedUploadUrlAcceptsClientBinaryPut() throws Exception {
        String key = "presign/upload.png";
        byte[] content = new byte[2048];
        new Random(42).nextBytes(content);

        String uploadUrl = adapter.presignUploadUrl(key, 900);
        HttpResponse<String> put = http.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .header("Content-Type", "image/png")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as("预签名 PUT 应被真实 S3 端接受").isEqualTo(200);

        assertThat(adapter.existsWithSize(key, content.length)).as("直传后对象应存在且大小一致").isTrue();
    }

    /** T13-11：预签名 GET URL 供短期下载 → 真实 HTTP GET 取回原文（私有桶下唯一读取路径，无公开访问）。 */
    @Test
    void presignedDownloadUrlReturnsObjectBytes() throws Exception {
        String key = "presign/download.png";
        byte[] content = "presigned-download-payload".getBytes(StandardCharsets.UTF_8);
        adapter.putObject(key, content);

        String downloadUrl = adapter.presignDownloadUrl(key, 300);
        HttpResponse<byte[]> get = http.send(
                HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(content);
    }

    /** T13-11：不存在的对象——existsWithSize 返回 false（404 确属不存在）；getObject 抛 NOT_FOUND（本应存在却读不到，如实暴露）。 */
    @Test
    void missingObjectIsNotFoundNotSilent() {
        String key = "missing/never-uploaded.png";
        assertThat(adapter.existsWithSize(key, 10)).as("不存在对象 existsWithSize 应为 false").isFalse();
        assertThatThrownBy(() -> adapter.getObject(key))
                .isInstanceOfSatisfying(ObjectStorageException.class,
                        e -> assertThat(e.reason()).isEqualTo(ObjectStorageException.Reason.NOT_FOUND));
    }

    /**
     * T13-11 核心：权限拒绝（错误凭据 → MinIO 403 SignatureDoesNotMatch）必须抛 ACCESS_DENIED，
     * <b>绝不返回 false 当作「不存在」</b>——否则服务端凭据权限问题会被 completeUpload 误判为用户未上传
     * （SIZE_MISMATCH），拒绝合法资产。对象确实存在，仅凭据无权访问。
     */
    @Test
    void accessDeniedIsNotTreatedAsMissing() {
        String key = "acl/existing.png";
        adapter.putObject(key, new byte[]{9, 9, 9}); // 对象确实存在
        CosObjectStorageAdapter badCred = new CosObjectStorageAdapter(
                endpoint, REGION, BUCKET, ROOT_USER, "wrong-secret-key-not-valid", true, 20_000);
        try {
            assertThatThrownBy(() -> badCred.existsWithSize(key, 3))
                    .isInstanceOfSatisfying(ObjectStorageException.class,
                            e -> assertThat(e.reason()).isEqualTo(ObjectStorageException.Reason.ACCESS_DENIED));
        } finally {
            badCred.close();
        }
    }

    /** T13-10：桶为私有——匿名（无凭据、无预签名）GET 被拒（403），证明未开公有读（禁默认公开桶）。 */
    @Test
    void bucketIsPrivateNoAnonymousRead() throws Exception {
        String key = "private/secret.png";
        adapter.putObject(key, new byte[]{1});
        HttpResponse<String> anon = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/" + BUCKET + "/" + key)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).as("私有桶匿名读取必须被拒").isEqualTo(403);
    }

    /**
     * T13-11：超时/网络异常判别为 TIMEOUT（确定性 mock，无需真实网络停滞），<b>绝不当作「不存在」</b>。
     * 用包级构造器注入替身 {@link S3Client}：headObject 抛 {@link ApiCallTimeoutException}，
     * 证明 existsWithSize 抛出 TIMEOUT 而非返回 false（与 404 的「false」语义严格区分）。
     */
    @Test
    void timeoutIsClassifiedAsTimeoutNotMissing() {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        when(mockS3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(ApiCallTimeoutException.builder().message("simulated call timeout").build());
        CosObjectStorageAdapter timeoutAdapter = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 20_000);
        assertThatThrownBy(() -> timeoutAdapter.existsWithSize("any/key.png", 10))
                .isInstanceOfSatisfying(ObjectStorageException.class,
                        e -> assertThat(e.reason()).isEqualTo(ObjectStorageException.Reason.TIMEOUT));
    }

    /**
     * T13-11（codex round-1 P2 修复）：响应头成功后响应体<b>断连</b>——底层 read 抛 {@link SocketTimeoutException}
     * （其消息故意携带一个「若泄露即失败」的伪签名 URL 金丝雀）。用包级构造器注入替身 {@link S3Client}：getObject 返回
     * 一个包装「读 1 字节后抛超时」流的 {@link ResponseInputStream}。断言：{@code readAllBytes()} 抛
     * {@link ObjectStorageException} {@code TIMEOUT} 而非裸 {@link IOException}（否则会被 AssetService.completeUpload
     * 当作 READ_FAILED 令合法资产永久 REJECTED）；且异常<b>不带 cause</b>、消息<b>不含</b>金丝雀 URL（净化，绝不泄露签名 URL）。
     */
    @Test
    void bodyReadDisconnectIsClassifiedAsTimeoutNotRawIOException() {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        String leakCanary = "sig=SECRET-SHOULD-NOT-LEAK";
        InputStream disconnecting = new InputStream() {
            private boolean served = false;
            @Override
            public int read() throws IOException {
                if (!served) { served = true; return 'x'; }
                throw new SocketTimeoutException("read timed out: " + leakCanary);
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!served) { served = true; b[off] = 'x'; return 1; }
                throw new SocketTimeoutException("read timed out: " + leakCanary);
            }
        };
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(disconnecting)));
        CosObjectStorageAdapter guarded = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 20_000);
        assertThatThrownBy(() -> guarded.getObject("body/disconnect.png").readAllBytes())
                .isInstanceOfSatisfying(ObjectStorageException.class, e -> {
                    assertThat(e.reason()).isEqualTo(ObjectStorageException.Reason.TIMEOUT);
                    assertThat(e.getCause()).as("净化：绝不带 cause（其消息可能含签名 URL）").isNull();
                    assertThat(e.getMessage()).as("净化：消息绝不含底层签名 URL 金丝雀").doesNotContain("SECRET-SHOULD-NOT-LEAK");
                });
    }

    /**
     * T13-11（codex round-1 P2 + round-2 P2 修复）：响应体<b>停滞/慢滴</b>——每次 read 前 sleep、且<b>永不 EOF</b>（模拟 COS 端持续慢速供给）。
     * 这正是「配置的读取预算无法限制完整读取」的场景：若无守卫，{@code readAllBytes()} 将<b>无限循环</b>。用包级构造器注入
     * 极短预算（150ms）的替身适配器，getObject 返回每字节 read 耗时 100ms 的慢滴流。<b>鉴别 drain-close 与 abort（round-2 P2）</b>：
     * 替身忠实建模 SDK 语义（javap 实证 2.54.7）：{@code ResponseInputStream.abort()} 先调注入的洁净 {@code abort} 动作（置 {@code abortCalled}）拆连接、
     * 再经 {@code IoUtils.closeQuietlyV2} 调 {@code close()}（连接已断→廉价 no-op）；仅<b>未经 abort 直接 close</b>（缺陷路径）时 {@code close()} 才模拟 Apache5 排空阻塞 4s。
     * 断言：包装流在<b>累计超预算</b>时走真 {@code abort()}（{@code abortCalled=true}、{@code drainedOnClose=false}）中止并抛 {@link ObjectStorageException} {@code TIMEOUT}，耗时受限。
     */
    @Test
    void slowBodyExceedingReadBudgetIsAbortedAsTimeout() {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        AtomicBoolean drainedOnClose = new AtomicBoolean(false);
        AtomicBoolean abortCalled = new AtomicBoolean(false);
        InputStream slowDrip = new InputStream() {
            @Override
            public int read() throws IOException {
                sleepQuietly(100);
                return 'y'; // 永不返回 -1：模拟持续慢滴、绝不 EOF
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                sleepQuietly(100);
                b[off] = 'y';
                return 1;   // 每次只给 1 字节，强制多次 read 累计耗时
            }
            @Override
            public void close() {
                // 真实语义（javap 实证 SDK 2.54.7）：abort() 先调 abortable.abort()（→abortCalled）拆连接，再经 closeQuietlyV2 调本 close()——
                // 连接已断、无内容可排空，故为廉价 no-op。仅「未经 abort 直接 close」（缺陷路径）才模拟 Apache5 排空剩余内容而阻塞。
                if (abortCalled.get()) {
                    return;
                }
                drainedOnClose.set(true);
                sleepQuietly(4_000);
            }
        };
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(slowDrip, () -> abortCalled.set(true))));
        // 极短读取预算 150ms：每字节 read 耗时 100ms，累计约 2 字节即超预算 → checkDeadline 走真 abort 中止（无守卫则无限循环）。
        CosObjectStorageAdapter guarded = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 150);
        long start = System.nanoTime();
        assertThatThrownBy(() -> guarded.getObject("body/slow.png").readAllBytes())
                .isInstanceOfSatisfying(ObjectStorageException.class,
                        e -> assertThat(e.reason()).isEqualTo(ObjectStorageException.Reason.TIMEOUT));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(abortCalled).as("累计超预算必须走真 abort()（强制切断、不排空剩余响应体）").isTrue();
        assertThat(drainedOnClose).as("绝不能用 close() 中止——Apache5 下 close 会排空剩余内容而长时间阻塞，绕过累计截止").isFalse();
        assertThat(elapsed).as("真 abort 生效：耗时应受限（约 2×100ms），绝非 close-drain 的 4s+").isLessThan(2_000);
    }

    /**
     * T13-11（codex round-2 P1 + round-5 P2 加固）：响应体读取失败<b>且</b>底层 close() 也抛含金丝雀的 IOException——净化要求：
     * 主体抛出的 {@link ObjectStorageException} <b>不带 cause、消息不含金丝雀</b>；且关闭阶段裸 IOException <b>绝不作 suppressed 附着</b>
     * （否则 {@code GlobalExceptionHandler} 打印异常全链会泄露签名 URL）。用 try-with-resources 触发「主体异常 + 关闭异常」组合。
     * <b>幂等鉴别力（round-5 P2：以底层关闭计数实证，不再仅靠代码观察）</b>：readFailure → abortQuietly → {@code abort()} →
     * {@code IoUtils.closeQuietlyV2} <b>已调用底层 close() 一次</b>（计数 = 1）并置 {@code closed=true}；try-with-resources 的包装流
     * {@code close()} 因 {@code if(closed)return} <b>幂等跳过、不再二次调用底层 close()</b>（计数仍 = 1）。删除该守卫则计数会变 2、断言即红。
     */
    @Test
    void bodyReadAndCloseFailuresNeverLeakCanaryNorAttachRawSuppressed() {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        String leakCanary = "sig=CLOSE-SECRET-SHOULD-NOT-LEAK";
        // round-5 P2：底层 close() 调用计数——实证包装流 close() 的幂等跳过（abort 已关一次，close 不再增计）。
        AtomicInteger underlyingCloseCount = new AtomicInteger(0);
        InputStream readThenCloseThrows = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failed: " + leakCanary);
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("read failed: " + leakCanary);
            }
            @Override
            public void close() throws IOException {
                underlyingCloseCount.incrementAndGet();
                throw new IOException("close failed: " + leakCanary);
            }
        };
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(readThenCloseThrows, () -> { /* 洁净 abort 动作：将连接标记为不可复用 */ })));
        CosObjectStorageAdapter guarded = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 20_000);
        assertThatThrownBy(() -> {
            try (InputStream in = guarded.getObject("body/close-fail.png")) {
                in.readAllBytes();
            }
        }).isInstanceOfSatisfying(ObjectStorageException.class, e -> {
            assertThat(e.getCause()).as("净化：绝不带 cause（其消息可能含签名 URL）").isNull();
            assertThat(e.getMessage()).as("净化：消息绝不含底层金丝雀").doesNotContain("CLOSE-SECRET-SHOULD-NOT-LEAK");
            assertThat(e.getSuppressed()).as("净化 close：绝不把裸关闭异常作 suppressed 附着（会被 GlobalExceptionHandler 打印全链泄露）").isEmpty();
        });
        // 幂等实证：abort 路径经 IoUtils.closeQuietlyV2 已调底层 close() 一次；try-with-resources 的包装流 close()
        // 因 closed=true 幂等跳过、不再二次调用。删除 if(closed)return 守卫会使计数变 2、本断言变红（鉴别力）。
        assertThat(underlyingCloseCount.get())
                .as("包装流 close() 幂等：底层 close() 仅由 abort 路径调用一次，包装流 close() 不再增计")
                .isEqualTo(1);
    }

    /**
     * T13-11(codex round-3 P2 + round-5 P3 注释订正): 读取成功后、close 阶段抛含金丝雀的 IOException——验证包装流 close() 的
     * <b>catch 净化分支</b>：本例读取成功、未经 readFailure/abortQuietly，故首次 close() 前 {@code closed} 仍为 {@code false}，
     * close() 会真正调用底层 close()（抛异常）并由 {@code catch(Exception)} 净化——只记类型名、绝不上抛、绝不作 suppressed 附着
     * （否则 GlobalExceptionHandler 打印全链泄露 URL）。与 row 11 的「{@code closed=true} 幂等跳过」分支互补，两者合起覆盖 close() 两条路径。
     */
    @Test
    void readSuccessThenCloseThrowsNeverAttachesRawSuppressed() throws Exception {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        String leakCanary = "sig=SUCCESS-CLOSE-SECRET";
        InputStream readThenCloseThrows = new InputStream() {
            @Override
            public int read() throws IOException {
                return -1; // 成功读完 (空内容)
            }
            @Override
            public void close() throws IOException {
                throw new IOException("close failed after successful read: " + leakCanary);
            }
        };
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(readThenCloseThrows, () -> { }))); // 空 abort 回调
        CosObjectStorageAdapter guarded = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 20_000);
        InputStream in = guarded.getObject("body/success-then-close-fail.png");
        byte[] data = in.readAllBytes();
        assertThat(data).as("读取成功").isEmpty();
        // 显式调 close()（不用 try-with-resources）：编译器看到底层 InputStream.close() 声明 throws IOException 会要求处理；
        // 本例读取成功、closed 仍为 false，故 close() 会真正调用底层 close()（抛含金丝雀 IOException），
        // 由包装流 close() 的 catch(Exception) 净化——只记类型名、不上抛。用 catch(Exception) 兜底断言其确未上抛。
        try {
            in.close();
        } catch (Exception e) {
            throw new AssertionError("close() 应已净化异常、不上抛", e);
        }
        // 无异常抛出即证明 catch 净化分支生效：底层 close() 抛出的 IOException 被包装流 close() 内部捕获、未上抛、
        // 未作 suppressed 附着（净化异常绝不携带底层金丝雀）。
    }

    /**
     * T13-11(codex round-3 P2): 响应体 available() 抛含金丝雀的异常——验证包装流 available() 净化逻辑:
     * 捕获 IOException/RuntimeException 后返回 0(保守估计),只记类型名不记 e.getMessage()(避免签名 URL 泄露),绝不上抛裸异常。
     */
    @Test
    void availableThrowsReturnsZeroNotRawException() throws Exception {
        S3Client mockS3 = mock(S3Client.class);
        S3Presigner mockPresigner = mock(S3Presigner.class);
        String leakCanary = "sig=AVAILABLE-SECRET";
        InputStream availableThrows = new InputStream() {
            @Override
            public int read() throws IOException {
                return -1;
            }
            @Override
            public int available() throws IOException {
                throw new IOException("available failed: " + leakCanary);
            }
        };
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(availableThrows, () -> { })));
        CosObjectStorageAdapter guarded = new CosObjectStorageAdapter(mockS3, mockPresigner, BUCKET, 20_000);
        InputStream in = guarded.getObject("body/available-fail.png");
        assertThat(in.available()).as("available 异常时返回 0(净化)").isEqualTo(0);
        // 显式调 close()：同样用 catch(Exception) 捕获所有异常（包装流已净化）。
        try {
            in.close();
        } catch (Exception e) {
            throw new AssertionError("close() 应已净化异常、不上抛", e);
        }
        // 无异常抛出即证明净化生效：available() 内部捕获了 IOException、返回 0。
    }
    
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
