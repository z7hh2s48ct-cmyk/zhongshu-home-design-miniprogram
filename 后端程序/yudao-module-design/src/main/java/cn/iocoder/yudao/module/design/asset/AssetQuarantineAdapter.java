package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
import org.springframework.stereotype.Component;

/**
 * infra 共享端口的 design 侧实现：把私有对象存储与内容扫描能力暴露给 ai-orchestration
 */
@Component
public class AssetQuarantineAdapter implements QuarantineObjectPort, ContentScanPort {

    private final ObjectStoragePort storage;

    private final AssetContentScanner scanner;
    @jakarta.annotation.Resource
    private ContentModerationPort moderation;

    public AssetQuarantineAdapter(ObjectStoragePort storage, AssetContentScanner scanner) {
        this.storage = storage;
        this.scanner = scanner;
    }

    @Override
    public boolean existsWithSize(String objectKey, long expectedSize) {
        return storage.existsWithSize(objectKey, expectedSize);
    }

    @Override
    public byte[] getObject(String objectKey) {
        try (var in = storage.getObject(objectKey)) {
            byte[] bytes = in.readNBytes(20 * 1024 * 1024 + 1);
            if (bytes.length > 20 * 1024 * 1024) throw new IllegalStateException("AI_OBJECT_LIMIT");
            return bytes;
        } catch (Exception e) {
            throw new IllegalStateException("对象读取失败: " + objectKey, e);
        }
    }

    @Override
    public void putObject(String objectKey, byte[] content) {
        storage.putObject(objectKey, content);
    }

    @Override
    public ScanOutcome scan(String declaredMime, byte[] content) {
        var report = scanner.scan(declaredMime, content);
        if (!report.passed()) return new ScanOutcome(false, report.failures(), report.width(), report.height(), report.pageCount());
        // Required bean in Spring; explicitly supplied in non-Spring integration tests.
        if (moderation == null) throw new IllegalStateException("CONTENT_MODERATION_NOT_WIRED");
        var decision = moderation.review("AI_OUTPUT", report.sanitizedContent());
        return new ScanOutcome(decision.accepted(), decision.accepted() ? java.util.List.of() : java.util.List.of("CONTENT_MODERATION"),
                report.width(), report.height(), report.pageCount(), report.sanitizedContent(),
                decision.provider() + ":" + decision.requestId() + ":" + decision.suggestion() + ":" + decision.policy());
    }

}
