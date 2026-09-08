package cn.iocoder.yudao.module.identity.accesscode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 授权码密码学组件（架构 §6.1 / §10.2）
 *
 * 合同：
 * - 明文码由 SecureRandom 生成 128 bit 熵（Crockford Base32，26 字符）；
 * - 数据库只存 HMAC-SHA-256(pepper, 规范化明文)，pepper 版本随行记录、支持轮换；
 *   pepper 通过 zhongshu.identity.access-code-pepper 注入（生产走密钥托管/环境变量），缺失时在使用处快速失败；
 * - TICKET 交付制品：AES-256-GCM 加密整批明文，制品密钥 zhongshu.identity.access-code-artifact-key（Base64 32 字节）。
 */
@Component
public class AccessCodeCipher {

    /** Crockford Base32：不含 I/L/O/U，避免人工转录歧义 */
    private static final String BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final String PEPPER_BLANK_MESSAGE =
            "缺少 zhongshu.identity.access-code-pepper 配置（生产必须由密钥托管提供）";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${zhongshu.identity.access-code-pepper:}")
    private String pepper;

    @Value("${zhongshu.identity.access-code-pepper-version:v1}")
    private String pepperVersion;

    @Value("${zhongshu.identity.access-code-artifact-key:}")
    private String artifactKeyBase64;

    public static AccessCodeCipher forTesting(String pepper, String pepperVersion, String artifactKeyBase64) {
        AccessCodeCipher cipher = new AccessCodeCipher();
        cipher.pepper = pepper;
        cipher.pepperVersion = pepperVersion;
        cipher.artifactKeyBase64 = artifactKeyBase64;
        return cipher;
    }

    /** 生成一条明文授权码：ZS + 26 位 Crockford Base32（5+5+5+5+6 分组连字符），128 bit 熵 */
    public String generate() {
        byte[] raw = new byte[16];
        SECURE_RANDOM.nextBytes(raw);
        String encoded = base32Encode(raw);
        StringBuilder sb = new StringBuilder("ZS");
        for (int i = 0; i < encoded.length(); i += 5) {
            sb.append('-').append(encoded, i, Math.min(i + 5, encoded.length()));
        }
        return sb.toString();
    }

    /** 规范化：去分隔符、大写（兑换输入容错） */
    public String normalize(String code) {
        return code == null ? "" : code.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
    }

    /** HMAC-SHA-256(pepper, 规范化明文) 的 hex；pepper 缺失快速失败 */
    public String hash(String plaintextCode) {
        requirePepper();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(normalize(plaintextCode).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public String pepperVersion() {
        return pepperVersion;
    }

    /** 掩码：ZS-ABCDE-****-FGHIJ（保留前 7 位与后 5 位） */
    public String mask(String plaintextCode) {
        String normalized = normalize(plaintextCode);
        return "ZS-" + normalized.substring(2, 7) + "-****-" + normalized.substring(normalized.length() - 5);
    }

    // ========== TICKET 交付制品（AES-256-GCM） ==========

    public byte[] encryptArtifact(List<String> plaintextCodes) {
        SecretKeySpec key = artifactKey();
        byte[] nonce = new byte[12];
        SECURE_RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD("access-code-batch".getBytes(StandardCharsets.UTF_8));
            byte[] plain = String.join("\n", plaintextCodes).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("授权码制品加密失败", e);
        }
    }

    public List<String> decryptArtifact(byte[] artifactWithNonce) {
        SecretKeySpec key = artifactKey();
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(artifactWithNonce, 0, nonce, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD("access-code-batch".getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(
                    java.util.Arrays.copyOfRange(artifactWithNonce, 12, artifactWithNonce.length));
            return new ArrayList<>(List.of(new String(plain, StandardCharsets.UTF_8).split("\n")));
        } catch (Exception e) {
            throw new IllegalStateException("授权码制品解密失败", e);
        }
    }

    private SecretKeySpec artifactKey() {
        if (artifactKeyBase64 == null || artifactKeyBase64.isBlank()) {
            throw new IllegalStateException("缺少 zhongshu.identity.access-code-artifact-key 配置（Base64 32 字节）");
        }
        byte[] key = java.util.Base64.getDecoder().decode(artifactKeyBase64);
        if (key.length != 32) {
            throw new IllegalStateException("access-code-artifact-key 必须是 32 字节");
        }
        return new SecretKeySpec(key, "AES");
    }

    private void requirePepper() {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(PEPPER_BLANK_MESSAGE);
        }
        if (pepper.length() < 32) {
            throw new IllegalStateException("access-code-pepper 熵不足（至少 32 字符）");
        }
    }

    static String base32Encode(byte[] bytes) {
        // 逐 5 bit 取值，字母表 32 选 1；16 字节 = 128 bit → 26 字符
        StringBuilder sb = new StringBuilder(26);
        int bitBuffer = 0;
        int bitCount = 0;
        for (byte b : bytes) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                sb.append(BASE32_ALPHABET.charAt((bitBuffer >> (bitCount - 5)) & 31));
                bitCount -= 5;
            }
        }
        if (bitCount > 0) {
            sb.append(BASE32_ALPHABET.charAt((bitBuffer << (5 - bitCount)) & 31));
        }
        return sb.toString();
    }

}
