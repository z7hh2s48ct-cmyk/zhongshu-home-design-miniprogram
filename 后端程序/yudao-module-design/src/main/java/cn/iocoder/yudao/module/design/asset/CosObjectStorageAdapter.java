package cn.iocoder.yudao.module.design.asset;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

/**
 * 腾讯云 COS 私有桶对象存储适配器（B2 T13-11）：{@link ObjectStoragePort} 的真实实现，
 * 直接复用 infra {@code S3FileClient} 同一套 AWS S3 SDK v2（{@code software.amazon.awssdk:s3}，
 * BOM 锁定 2.54.7），<b>不新增第二套同用途 SDK</b>（计划 §2「避免额外引入一套同用途 SDK」）。
 *
 * <p>装配条件（T13-01）：{@code zhongshu.design.asset.storage.provider=cos}；与 {@link LocalObjectStorageAdapter}
 * 互斥，每端口只装配一个实现。生产禁止 local、必须 cos，由 {@code ZhongshuWiringEnvironmentPostProcessor}
 * 启动守卫强制；COS 真实模式所需 secret-id/secret-key/region/bucket/endpoint 由 {@code RealServiceWiringPolicy}
 * 在启动期校验（缺失即快速失败，绝不静默装配无凭据的适配器）。
 *
 * <p>为何不直接复用 infra {@code S3FileClient}（T13-10 兼容验证结论）：{@code S3FileClient} 服务于底座通用文件模块，
 * 其语义与 {@link ObjectStoragePort} 私有桶合同不符——① {@code presignPutUrl} 用<b>固定 24h</b> TTL，而本端口要求
 * 上传票据 900s、下载 URL 300s 的<b>可配短 TTL</b>；② 无 {@code headObject}，无法在不下载整个对象的前提下做
 * {@code existsWithSize} 大小校验；③ {@code getContent} 返回 {@code byte[]}（全量下载），而本端口 {@code getObject}
 * 要求返回 {@link InputStream}（流式，供大对象校验/消毒）；④ 不暴露 404/403/超时判别，无法满足 T13-11「区分 404、
 * 权限拒绝、超时，不将所有错误当不存在」。故复用其<b>底层 SDK 依赖</b>而非其<b>封装</b>，另写本适配器承载私有桶语义。
 *
 * <p>私有桶语义（架构 §6.9/§10.5）：全部对象只存私有桶，读取一律经短期下载预签名（{@link #presignDownloadUrl}），
 * 数据库不存长期签名 URL；客户端直传经上传预签名（{@link #presignUploadUrl}）。<b>绝不开公有读</b>——本适配器不提供
 * 任何公开访问路径，{@code enablePublicAccess} 之类开关不存在于本实现。
 *
 * <p>安全边界（承 {@code RealWechatIdentityAdapter} 同一纪律）：
 * <ul>
 *   <li><b>构造期校验配置并快速失败</b>：endpoint/region/secret-id/secret-key/bucket 任一空白或 endpoint 非 http(s)
 *       即抛 {@link IllegalArgumentException}，消息<b>只回显配置键名、绝不回显密钥值</b>；</li>
 *   <li><b>异常与日志绝不泄露凭据/签名 URL</b>：{@link S3Exception} 只取 {@code statusCode} 与 {@code errorCode}
 *       记录，绝不记录 {@code e.getMessage()}（可能含带签名参数的请求 URL）；绝不把底层 SDK 异常作为 cause 外抛
 *       （经 {@code GlobalExceptionHandler} 落库会泄露 URL）；预签名 URL 属对象级承载凭据，从不写日志；</li>
 *   <li><b>有界超时</b>：{@code apiCallTimeout} 界定单次 S3 调用（含 SDK 内部重试）总时长，杜绝 COS 不可达时
 *       请求线程被无限挂起；超时经 {@link ObjectStorageException.Reason#TIMEOUT} 分类，绝不当作「对象不存在」。</li>
 * </ul>
 *
 * <p>可测试性（T13-10）：endpoint/region/path-style-access 均可配，隔离测试将其指向本地 MinIO（S3 兼容）
 * Testcontainer（path-style），用<b>真实 SDK 与真实 S3 协议</b>验证私有 PUT/GET/HEAD、预签名上传/下载往返、
 * existsWithSize、404/403 判别，全程不触达真实腾讯 COS、不产生真实消费。真实 COS 特有行为（虚拟主机式寻址、
 * {@code cos.{region}.myqcloud.com} endpoint 格式、生命周期）属凭据依赖项，待 T13-15 真实验收。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhongshu.design.asset.storage", name = "provider", havingValue = "cos")
public class CosObjectStorageAdapter implements ObjectStoragePort {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    /** 响应体读取预算（ms）：界定 {@link #getObject} 完整读取的累计时长（codex round-1 P2 修复，见 ReadDeadlineGuardedInputStream）。 */
    private final long readTimeoutMs;

    /**
     * Spring 装配构造器：各参经 {@code @Value} 从配置解析，校验后构建 {@link S3Client} 与 {@link S3Presigner}，
     * 再委派给包级构造器。因本类另有一个供隔离测试注入替身客户端的包级构造器，多构造器并存时 Spring 无法自行判定
     * 注入入口，故以 {@code @Autowired} 显式指明（与 {@code RealWechatIdentityAdapter} 同一处理）。
     */
    @Autowired
    public CosObjectStorageAdapter(
            @Value("${zhongshu.design.asset.storage.cos.endpoint:}") String endpoint,
            @Value("${zhongshu.design.asset.storage.cos.region:}") String region,
            @Value("${zhongshu.design.asset.storage.cos.bucket:}") String bucket,
            @Value("${zhongshu.design.asset.storage.cos.secret-id:}") String secretId,
            @Value("${zhongshu.design.asset.storage.cos.secret-key:}") String secretKey,
            @Value("${zhongshu.design.asset.storage.cos.path-style-access:false}") boolean pathStyleAccess,
            @Value("${zhongshu.design.asset.storage.cos.api-call-timeout-ms:20000}") long apiCallTimeoutMs) {
        this(buildS3(endpoint, region, secretId, secretKey, pathStyleAccess, apiCallTimeoutMs),
                buildPresigner(endpoint, region, secretId, secretKey, pathStyleAccess),
                bucket, apiCallTimeoutMs);
    }

    /**
     * 包级可见构造器：供隔离测试注入替身 {@link S3Client}/{@link S3Presigner}（如以 mock 确定性触发
     * {@link ApiCallTimeoutException} 验证超时分类、或以慢速/中断的响应体流验证 {@link #getObject} 的读取截止与净化分类，
     * 无需真实网络停滞），或直接指向 MinIO 的真实客户端。{@code readTimeoutMs} 为响应体读取预算，生产装配复用
     * {@code api-call-timeout-ms}（同一 COS 操作超时预算），非法值（&lt;1）归一为 1ms。
     */
    CosObjectStorageAdapter(S3Client s3, S3Presigner presigner, String bucket, long readTimeoutMs) {
        if (s3 == null || presigner == null) {
            throw new IllegalArgumentException("S3Client/S3Presigner 不能为空");
        }
        if (isBlank(bucket)) {
            throw new IllegalArgumentException("COS bucket 不能为空（检查 zhongshu.design.asset.storage.cos.bucket）");
        }
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.readTimeoutMs = Math.max(1L, readTimeoutMs);
    }

    // ========== ObjectStoragePort 实现 ==========

    /**
     * 客户端直传地址：COS 预签名 PUT URL，TTL 可配（上传票据 900s）。<b>不签名 Content-Type</b>——
     * 由客户端按申报 MIME 自行设置（见小程序 avatar-upload.js/ai-design 直传），与服务端策略解耦。
     * 预签名是<b>本地计算</b>（无网络调用），故不涉及 404/403/超时判别。
     */
    @Override
    public String presignUploadUrl(String objectKey, long ttlSeconds) {
        requireKey(objectKey);
        Duration ttl = positiveTtl(ttlSeconds);
        // 预签名 URL 属对象级承载凭据，绝不写日志。
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(objectKey).build())
                        .build())
                .url().toString();
    }

    /**
     * 对象是否存在且大小一致：以 {@code headObject}（不下载对象体）读取 {@code Content-Length} 比对。
     * <b>错误判别（T13-11 核心）</b>：仅 404/NoSuchKey 返回 {@code false}（确属不存在）；403 权限拒绝、
     * 超时/网络异常、其它服务异常一律抛 {@link ObjectStorageException}，<b>绝不把权限或超时错误当作「不存在」</b>
     * （否则会把服务端凭据权限问题或瞬态故障误判为用户未上传，令 completeUpload 错误地以 SIZE_MISMATCH 拒绝合法资产）。
     */
    @Override
    public boolean existsWithSize(String objectKey, long expectedSize) {
        requireKey(objectKey);
        try {
            HeadObjectResponse resp = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            Long len = resp.contentLength();
            return len != null && len == expectedSize;
        } catch (S3Exception e) {
            // HEAD 404 无响应体，SDK 可能抛 NoSuchKeyException 或 statusCode=404 的 S3Exception，两者都判「不存在」。
            if (isNotFound(e)) {
                return false;
            }
            throw classify("existsWithSize", objectKey, e);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            log.warn("[existsWithSize] COS HEAD 调用超时 key={}", objectKey);
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "HEAD 调用超时");
        } catch (SdkClientException e) {
            log.warn("[existsWithSize] COS HEAD 网络/客户端异常 key={}（{}）", objectKey, e.getClass().getSimpleName());
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "HEAD 网络/客户端异常");
        }
    }

    /**
     * 读取对象内容（校验/消毒用）：返回流式 {@link InputStream}，调用方负责关闭。
     * 404/403/超时分别经 {@link ObjectStorageException} 分类抛出（404 亦抛出——调用方若需「不存在即 false」语义
     * 应先经 {@link #existsWithSize}；getObject 的 404 属「本应存在却读不到」的异常路径，如实暴露而非静默）。
     *
     * <p><b>响应体读取守卫（codex round-1 P2 修复）</b>：SDK 的 {@code apiCallTimeout} 只界定「获取响应头」的调用，
     * <b>不约束响应体的流式读取</b>（AWS 明确 {@link ResponseInputStream} 仅受 socket timeout 界定）；若直接返回裸流，
     * 响应头返回后响应体停滞/断连时调用方 {@code readAllBytes()} 已在适配器 try/catch 之外，读取超时不会分类为
     * {@link ObjectStorageException.Reason#TIMEOUT}，而会被 {@code AssetService.completeUpload} 的 {@code catch(IOException)}
     * 当作 {@code READ_FAILED} 令合法资产被<b>永久 REJECTED</b>。故返回 {@link ReadDeadlineGuardedInputStream} 包装流，
     * 为完整读取提供累计截止时间、超时中止与净化异常分类（详见该类）。
     */
    @Override
    public InputStream getObject(String objectKey) {
        requireKey(objectKey);
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return new ReadDeadlineGuardedInputStream(in, objectKey, readTimeoutMs);
        } catch (S3Exception e) {
            throw classify("getObject", objectKey, e);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            log.warn("[getObject] COS GET 调用超时 key={}", objectKey);
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "GET 调用超时");
        } catch (SdkClientException e) {
            log.warn("[getObject] COS GET 网络/客户端异常 key={}（{}）", objectKey, e.getClass().getSimpleName());
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "GET 网络/客户端异常");
        }
    }

    /**
     * 覆写对象（EXIF 消毒后回写、管理端案例上传）：服务端直接 PUT 字节。Content-Type 按 objectKey 扩展名推断
     * （AssetService 生成的 key 必带正确扩展名 .jpg/.png/.pdf），令消毒回写后对象仍带正确 MIME——否则 SDK 默认
     * {@code application/octet-stream} 会覆盖客户端直传时设置的形象 MIME，导致后续预签名 GET 内联展示异常。
     */
    @Override
    public void putObject(String objectKey, byte[] content) {
        requireKey(objectKey);
        if (content == null) {
            throw new IllegalArgumentException("putObject 内容不能为空 key=" + objectKey);
        }
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(objectKey).contentType(contentTypeFromKey(objectKey)).build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw classify("putObject", objectKey, e);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            log.warn("[putObject] COS PUT 调用超时 key={}", objectKey);
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "PUT 调用超时");
        } catch (SdkClientException e) {
            log.warn("[putObject] COS PUT 网络/客户端异常 key={}（{}）", objectKey, e.getClass().getSimpleName());
            throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey, "PUT 网络/客户端异常");
        }
    }

    /**
     * 短期下载 URL：COS 预签名 GET URL，TTL 可配（下载 300s）。私有桶下这是唯一的读取路径（无公开访问）。
     * 预签名是本地计算（无网络调用）。返回的 URL 属对象级承载凭据，绝不写日志。
     */
    @Override
    public String presignDownloadUrl(String objectKey, long ttlSeconds) {
        requireKey(objectKey);
        Duration ttl = positiveTtl(ttlSeconds);
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                        .build())
                .url().toString();
    }

    @Override public boolean supportsExternalModerationSource() { return true; }

    @Override public void deleteModerationObject(String objectKey) {
        requireKey(objectKey);
        if (!objectKey.startsWith("moderation-input/")) throw new IllegalArgumentException("MODERATION_TEMPORARY_KEY_REQUIRED");
        s3.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    /** 释放 SDK 客户端连接池（S3Client/S3Presigner 均 SdkAutoCloseable）；容器关闭时由 Spring 调用。 */
    @PreDestroy
    public void close() {
        closeQuietly(s3, "S3Client");
        closeQuietly(presigner, "S3Presigner");
    }

    // ========== 内部：客户端构建与配置校验 ==========

    private static S3Client buildS3(String endpoint, String region, String secretId, String secretKey,
                                    boolean pathStyleAccess, long apiCallTimeoutMs) {
        // 参数从左到右求值：buildS3 先于 buildPresigner，故配置校验在此一次即可（校验失败 buildPresigner 不会执行）。
        validateConfig(endpoint, region, secretId, secretKey);
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(secretId, secretKey)))
                // path-style：MinIO/隔离测试用 true；腾讯 COS 用虚拟主机式（false，SDK 自动拼 {bucket}.{endpoint}）。
                // chunkedEncodingEnabled(false)：与 infra S3FileClient 一致，规避 aws-chunked 流式签名在部分 S3 兼容端的兼容问题。
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .chunkedEncodingEnabled(false)
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(Math.max(1L, apiCallTimeoutMs)))
                        .build())
                .build();
    }

    private static S3Presigner buildPresigner(String endpoint, String region, String secretId, String secretKey,
                                              boolean pathStyleAccess) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(secretId, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build())
                .build();
    }

    /** 构造期配置校验：消息只回显配置键名，绝不回显密钥值（与 RealServiceWiringPolicy/启动守卫同一纪律）。 */
    private static void validateConfig(String endpoint, String region, String secretId, String secretKey) {
        if (isBlank(endpoint)) {
            throw new IllegalArgumentException("COS endpoint 不能为空（检查 zhongshu.design.asset.storage.cos.endpoint）");
        }
        if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            throw new IllegalArgumentException("COS endpoint 必须以 http:// 或 https:// 开头（zhongshu.design.asset.storage.cos.endpoint）");
        }
        if (isBlank(region)) {
            throw new IllegalArgumentException("COS region 不能为空（检查 zhongshu.design.asset.storage.cos.region）");
        }
        if (isBlank(secretId)) {
            throw new IllegalArgumentException("COS secret-id 不能为空（检查 zhongshu.design.asset.storage.cos.secret-id）");
        }
        if (isBlank(secretKey)) {
            throw new IllegalArgumentException("COS secret-key 不能为空（检查 zhongshu.design.asset.storage.cos.secret-key）");
        }
    }

    // ========== 内部：错误分类与工具 ==========

    /** 把 {@link S3Exception} 按状态码/错误码分类为净化异常；只记录 statusCode+errorCode，绝不记录 e.getMessage()（可能含签名 URL）。 */
    private ObjectStorageException classify(String op, String objectKey, S3Exception e) {
        int sc = e.statusCode();
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        if (isNotFound(e)) {
            log.warn("[{}] COS 对象不存在 key={} status={} code={}", op, objectKey, sc, code);
            return new ObjectStorageException(ObjectStorageException.Reason.NOT_FOUND, objectKey, "对象不存在(404)");
        }
        if (sc == 403 || "AccessDenied".equals(code)) {
            log.warn("[{}] COS 权限拒绝 key={} status=403 code={}", op, objectKey, code);
            return new ObjectStorageException(ObjectStorageException.Reason.ACCESS_DENIED, objectKey, "权限拒绝(403)");
        }
        log.warn("[{}] COS 服务异常 key={} status={} code={}", op, objectKey, sc, code);
        return new ObjectStorageException(ObjectStorageException.Reason.SERVICE_ERROR, objectKey, "服务异常(" + sc + ")");
    }

    /** 404 判定：HEAD 无响应体时 SDK 可能只给 statusCode=404，GET 可能给 NoSuchKey/NotFound 错误码，两者都算不存在。 */
    private static boolean isNotFound(S3Exception e) {
        if (e.statusCode() == 404) {
            return true;
        }
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        return "NoSuchKey".equals(code) || "NotFound".equals(code);
    }

    private static void requireKey(String objectKey) {
        if (isBlank(objectKey)) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
    }

    private static Duration positiveTtl(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("预签名 TTL 必须为正秒数，实际=" + ttlSeconds);
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    /** 按 objectKey 扩展名推断 Content-Type（AssetService 生成 key 必带 .jpg/.png/.pdf 扩展名）。 */
    private static String contentTypeFromKey(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private static void closeQuietly(AutoCloseable closeable, String name) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("[close] 关闭 {} 失败（{}）", name, e.getClass().getSimpleName());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ========== 内部：响应体读取守卫（codex round-1 P2 修复） ==========

    /**
     * {@link #getObject} 返回的响应体读取守卫流：包装 SDK 的 {@link ResponseInputStream}，为「完整读取」提供
     * <b>累计截止时间</b>、<b>超时中止</b>与<b>净化异常分类</b>——弥补 SDK {@code apiCallTimeout} 不约束响应体流式读取的缺口。
     *
     * <ul>
     *   <li><b>累计截止</b>：以流创建（{@link #getObject} 调用）为起点、{@code readTimeoutMs} 为预算，每次 read/skip 前检查；
     *       超预算即<b>中止</b>（{@code abort()} 强制切断连接、不排空剩余响应体，区别于会排空复用的 {@code close()}）并抛 {@link ObjectStorageException} {@code TIMEOUT}。这界定「慢滴」
     *       （多次 read 各自返回、累计却超预算）；单次 read 阻塞则由 SDK 客户端 socket timeout 界定，其触发的
     *       {@link SocketTimeoutException} 由本流捕获并同样分类为 {@code TIMEOUT}。</li>
     *   <li><b>净化分类</b>：底层 read 抛出的 {@link IOException}（含 {@link SocketTimeoutException}）分类为 {@code TIMEOUT}
     *       （瞬态、可重试），其它 {@link RuntimeException}（含 SdkException）分类为 {@code SERVICE_ERROR}；<b>绝不带 cause、
     *       绝不 {@code log e.getMessage()}</b>（SDK 异常消息可能含带签名参数的请求 URL），只记异常类型名。</li>
     *   <li><b>fail-loud 穿透（刻意设计）</b>：{@link ObjectStorageException} 属 {@link IllegalStateException}（非 {@link IOException}），
     *       故会<b>穿透</b> {@code AssetService.completeUpload}/{@code readForTicket} 的 {@code catch(IOException)}——这是正确的：
     *       瞬态存储故障应 fail-loud 并令资产停留在可重试的 {@code VALIDATING} 态，而非被误判为 {@code READ_FAILED} 永久 {@code REJECTED}；
     *       而 {@code readReviewTicket}/{@code readCasePreviewTicket} 的 {@code catch(IOException|IllegalStateException)} 仍会捕获它并转友好提示。</li>
     * </ul>
     */
    private static final class ReadDeadlineGuardedInputStream extends InputStream {

        /** 保留 {@link ResponseInputStream} 类型（而非窄化为 {@link InputStream}）：中止时须调用其 {@code abort()} 强制切断连接、不排空剩余响应体（见 {@link #abortQuietly()}）。 */
        private final ResponseInputStream<GetObjectResponse> in;
        private final String objectKey;
        private final long readTimeoutMs;
        private final long deadlineNanos;
        private boolean closed;

        ReadDeadlineGuardedInputStream(ResponseInputStream<GetObjectResponse> in, String objectKey, long readTimeoutMs) {
            this.in = in;
            this.objectKey = objectKey;
            this.readTimeoutMs = readTimeoutMs;
            this.deadlineNanos = System.nanoTime() + Duration.ofMillis(readTimeoutMs).toNanos();
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            try {
                return in.read();
            } catch (IOException | RuntimeException e) {
                throw readFailure(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkDeadline();
            try {
                return in.read(b, off, len);
            } catch (IOException | RuntimeException e) {
                throw readFailure(e);
            }
        }

        @Override
        public long skip(long n) throws IOException {
            checkDeadline();
            try {
                return in.skip(n);
            } catch (IOException | RuntimeException e) {
                throw readFailure(e);
            }
        }

        @Override
        public int available() {
            // 净化（codex round-2 P1）：available 底层异常绝不上抛裸 IOException（其 cause 可能含签名 URL），只记类型名后返回 0（保守估计，readAllBytes 不依赖它）。
            try {
                return in.available();
            } catch (IOException | RuntimeException e) {
                log.warn("[getObject] COS GET 响应体 available 失败 key={}（{}）", objectKey, e.getClass().getSimpleName());
                return 0;
            }
        }

        @Override
        public void close() {
            // 幂等 + 净化（codex round-2 P1）：已 abort/关闭则直接跳过（避免 close 再触发 Apache5 排空）；关闭阶段异常绝不上抛——
            // 否则 try-with-resources 会把裸 IOException 作 suppressed 附着到净化异常上，被 GlobalExceptionHandler 打印全链而泄露签名 URL，
            // 或裸 IOException 被 AssetService.completeUpload 的 catch(IOException) 误判 READ_FAILED。只记类型名，绝不 log e.getMessage()。
            if (closed) {
                return;
            }
            closed = true;
            try {
                in.close();
            } catch (Exception e) {
                log.warn("[getObject] COS GET 响应体关闭失败 key={}（{}）", objectKey, e.getClass().getSimpleName());
            }
        }

        /** 累计读取超预算即中止并抛 TIMEOUT（界定慢滴；单次阻塞 read 由 SDK socket timeout 界定后同样分类）。 */
        private void checkDeadline() {
            if (!closed && System.nanoTime() - deadlineNanos >= 0) {
                abortQuietly();
                log.warn("[getObject] COS GET 响应体读取超预算未完成 key={} budgetMs={}", objectKey, readTimeoutMs);
                throw new ObjectStorageException(ObjectStorageException.Reason.TIMEOUT, objectKey,
                        "GET 响应体读取超时（超过 " + readTimeoutMs + "ms 未完成）");
            }
        }

        /** 净化底层读取异常：绝不带 cause、绝不回显 e.getMessage()（可能含签名 URL），只记异常类型名。 */
        private ObjectStorageException readFailure(Exception e) {
            abortQuietly();
            ObjectStorageException.Reason reason = e instanceof IOException
                    ? ObjectStorageException.Reason.TIMEOUT        // 含 SocketTimeoutException：响应体读取瞬态网络故障，可重试
                    : ObjectStorageException.Reason.SERVICE_ERROR; // SdkException 等运行时异常
            log.warn("[getObject] COS GET 响应体读取失败 key={} reason={}（{}）",
                    objectKey, reason, e.getClass().getSimpleName());
            return new ObjectStorageException(reason, objectKey,
                    reason == ObjectStorageException.Reason.TIMEOUT ? "GET 响应体读取中断/超时" : "GET 响应体读取失败");
        }

        /**
         * 中止读取：调用 {@link ResponseInputStream#abort()} <b>强制切断连接、不排空剩余响应体</b>（codex round-2 P2）。
         * <b>绝不用 {@code close()}</b>——Apache5 客户端下 {@code close()} 对未读完的流会<b>继续读取剩余内容以复用连接</b>（drain），
         * 对慢滴/无限流将在 close 内部长时间阻塞，绕过本守卫的累计截止，令「读取预算」形同虚设。{@code abort()} 为 best-effort、
         * 不排空；置 {@code closed} 令本包装流后续的 {@code close()} 幂等跳过。abort 异常绝不上抛（其异常可能含签名 URL）。
         *
         * <p><b>字节码实证的 abort() 内部顺序</b>（awssdk 2.54.7，{@code javap -c ResponseInputStream}）：
         * {@code timeoutTask.cancel(false)} -> {@code abortable.abort()}（先强制切断连接）->
         * {@code IoUtils.closeQuietlyV2(in, log)}。故 <b>SDK 内部仍会调用底层流的 {@code close()}</b>；
         * 「不排空」成立的依据是连接在此之前已被 {@code abortable.abort()} 标记为不可复用，
         * 而非底层 close 未被调用。本包装流 {@code close()} 的幂等跳过是另一层独立保护：
         * 避免调用方（如 try-with-resources）在 abort 之后再走一次本类的关闭逻辑。
         */
        private void abortQuietly() {
            if (closed) {
                return;
            }
            closed = true;
            // abort 为 best-effort，连接已判定不可用；绝不上抛（其异常可能含签名 URL）。
            // codex round-4 P1：in.abort() -> IoUtils.closeQuietlyV2(in, log) 在底层 close() 抛异常时会调用
            // Logger.debug(Supplier, Throwable)，Throwable 即该原始异常（Supplier 只返回常量
            // "Ignore failure in closing the Closeable"），其消息/异常链可能含签名 URL。
            // 本方法的 catch 无法撤回 SDK 内部已执行的日志调用，故该泄露通道不靠此处代码封堵，而是靠
            // logback-spring.xml 显式将 software.amazon.awssdk（含 ResponseInputStream）钉为 INFO：
            // Logger.debug(Supplier, Throwable) 内部先判 isDebugEnabled()，有效级别 >= INFO 时 Supplier 不求值、
            // Throwable 不交给任何 appender，通道结构性关闭——不依赖 root 默认级别（logging.level.root=DEBUG
            // 即可绕过 root 默认，但绕不过显式声明的 logger 级别）。该加固由 SdkLoggingHardeningContractTest 锁定。
            try {
                in.abort();
            } catch (RuntimeException ignore) {
            }
        }
    }
}
