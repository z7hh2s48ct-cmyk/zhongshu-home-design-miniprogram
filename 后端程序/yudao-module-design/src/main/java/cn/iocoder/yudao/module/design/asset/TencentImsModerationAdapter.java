package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.module.infra.zhongshu.api.BoundedHttpBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Tencent IMS ImageModeration 2020-12-29; only exact Pass is accepted. */
@Component
@ConditionalOnProperty(prefix = "zhongshu.design.asset.moderation", name = "provider", havingValue = "real")
public class TencentImsModerationAdapter implements ContentModerationPort {
    private final String secretId, secretKey, region, policy;
    private final URI endpoint;
    private final HttpClient http;
    private final Runnable reserveQuota;
    private final Clock clock;
    private final Semaphore concurrency = new Semaphore(2);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    public TencentImsModerationAdapter(DataSource dataSource,
            @Value("${zhongshu.design.asset.moderation.secret-id:}") String secretId,
            @Value("${zhongshu.design.asset.moderation.secret-key:}") String secretKey,
            @Value("${zhongshu.design.asset.moderation.region:ap-guangzhou}") String region,
            @Value("${zhongshu.design.asset.moderation.biz-type:}") String policy,
            @Value("${zhongshu.design.asset.moderation.daily-limit:0}") int dailyLimit) {
        this(secretId, secretKey, region, policy, URI.create("https://ims.tencentcloudapi.com/"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                () -> {
                    if (dailyLimit < 1 || new JdbcTemplate(dataSource).update(
                            "INSERT INTO moderation_daily_usage (usage_day, calls) VALUES ((now() AT TIME ZONE 'UTC')::date,1) "
                            + "ON CONFLICT (usage_day) DO UPDATE SET calls=moderation_daily_usage.calls+1 "
                            + "WHERE moderation_daily_usage.calls < ?", dailyLimit) != 1) throw new ModerationUnavailableException();
                }, Clock.systemUTC());
        if (dailyLimit < 1 || dailyLimit > 1000000) throw new IllegalStateException("请配置审核 daily-limit (1..1000000)");
    }

    // Local fake HTTP server tests use this constructor; the Spring endpoint is fixed to Tencent HTTPS.
    TencentImsModerationAdapter(String secretId, String secretKey, String region, String policy,
            URI endpoint, HttpClient http, Runnable reserveQuota, Clock clock) {
        if (secretId.isBlank() || secretKey.isBlank() || !region.matches("[a-z-]{3,32}") || !policy.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalStateException("腾讯 IMS 审核配置缺失或非法（secret-id/secret-key/region/biz-type）");
        this.secretId = secretId; this.secretKey = secretKey; this.region = region; this.policy = policy;
        this.endpoint = endpoint; this.http = http; this.reserveQuota = reserveQuota; this.clock = clock;
    }

    public boolean pass(String type, byte[] content) { return review(type, content).accepted(); }

    public Decision review(String type, byte[] content) {
        // IMS is an image API. Never silently pass an unsupported PDF or oversized Base64 request.
        if ("CASE_PDF".equals(type) || content.length > 7 * 1024 * 1024)
            return new Decision(false, "tencent-ims", "", "UnsupportedInput", policy);
        if (!concurrency.tryAcquire()) throw new ModerationUnavailableException();
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = null;
        try {
            reserveQuota.run();
            byte[] body = JSON.writeValueAsBytes(Map.of("BizType", policy, "DataId", sha(content),
                    "FileContent", Base64.getEncoder().encodeToString(content)));
            long timestamp = clock.instant().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-TC-Action", "ImageModeration").header("X-TC-Version", "2020-12-29")
                    .header("X-TC-Region", region).header("X-TC-Timestamp", Long.toString(timestamp))
                    .header("Authorization", authorization(body, timestamp))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = http.sendAsync(request, info -> new BoundedHttpBody(128 * 1024));
            var response = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new ModerationUnavailableException();
            var value = JSON.readTree(response.body()).path("Response");
            if (value.has("Error")) throw new ModerationUnavailableException();
            String suggestion = value.path("Suggestion").asText(), requestId = value.path("RequestId").asText();
            if (!List.of("Pass", "Review", "Block").contains(suggestion) || !requestId.matches("[A-Za-z0-9-]{1,128}"))
                throw new ModerationUnavailableException();
            return new Decision("Pass".equals(suggestion), "tencent-ims", requestId, suggestion, policy);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ModerationUnavailableException(); // no upstream text, key or image in logs
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            concurrency.release();
        }
    }

    String authorization(byte[] body, long timestamp) throws Exception {
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString();
        String scope = date + "/ims/tc3_request";
        String canonical = "POST\n/\n\ncontent-type:application/json; charset=utf-8\nhost:" + endpoint.getHost()
                + "\n\ncontent-type;host\n" + sha(body);
        String signing = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] dateKey = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] signingKey = hmac(hmac(dateKey, "ims"), "tc3_request");
        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + scope + ", SignedHeaders=content-type;host, Signature="
                + HexFormat.of().formatHex(hmac(signingKey, signing));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
