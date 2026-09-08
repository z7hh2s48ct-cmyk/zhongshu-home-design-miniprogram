package cn.iocoder.yudao.module.aiorchestration.controller.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * internal-api 服务身份鉴权（审查 C6，合同 §7.4）：
 * HMAC-SHA256(sharedSecret, "{timestamp}\n{method}\n{path}\n{sha256(body)}")
 *
 * 合同：
 * - 请求头 X-ZS-Timestamp（epoch 秒）+ X-ZS-Signature（hex）；
 * - 时间戳与服务器时差超过 replayWindowSeconds 拒绝（防重放）；
 * - sharedSecret 经 zhongshu.ai.internal-secret 注入（生产密钥托管）；缺失时全部拒绝（快速失败）。
 * mTLS 属网络层加固，由部署侧（网关/Ingress）承担。
 */
@Component
public class InternalSignatureVerifier {

    @Value("${zhongshu.ai.internal-secret:}")
    private String sharedSecret;

    @Value("${zhongshu.ai.internal-replay-window:300}")
    private long replayWindowSeconds;

    public boolean verify(String timestamp, String signature, String method, String path, byte[] body) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            return false; // 未配置密钥：拒绝（生产必须配置；zsdev profile 提供联调占位值）
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > replayWindowSeconds) {
            return false;
        }
        String expected = sign(timestamp, method, path, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String timestamp, String method, String path, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
            String payload = timestamp + "\n" + method.toUpperCase() + "\n" + path + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("internal 签名计算失败", e);
        }
    }

}
