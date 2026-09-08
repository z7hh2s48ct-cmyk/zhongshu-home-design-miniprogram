package cn.iocoder.yudao.module.design.asset;

import java.util.List;
import java.util.Set;

/**
 * 资产类型上传策略（服务端强制，架构 §6.9）：
 * 用户草图 JPG/PNG ≤10MB；后台公司案例 JPG/PNG/PDF ≤20MB。
 */
public enum AssetTypePolicy {

    USER_SKETCH(10L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    CASE_IMAGE(20L * 1024 * 1024, Set.of("image/jpeg", "image/png")),
    CASE_PDF(20L * 1024 * 1024, Set.of("application/pdf")),
    AI_OUTPUT(20L * 1024 * 1024, Set.of("image/jpeg", "image/png"));

    private final long maxBytes;

    private final Set<String> allowedMimes;

    AssetTypePolicy(long maxBytes, Set<String> allowedMimes) {
        this.maxBytes = maxBytes;
        this.allowedMimes = allowedMimes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public List<String> allowedMimes() {
        return List.copyOf(allowedMimes);
    }

    public boolean mimeAllowed(String mime) {
        return allowedMimes.contains(mime);
    }

    /** 上传前缀（AI 输出隔离区：Runtime 仅可写此前缀） */
    public String keyPrefix() {
        return switch (this) {
            case USER_SKETCH -> "user-sketches";
            case CASE_IMAGE, CASE_PDF -> "company-cases";
            case AI_OUTPUT -> "ai-quarantine";
        };
    }

}
