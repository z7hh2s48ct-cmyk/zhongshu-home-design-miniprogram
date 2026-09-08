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
            return in.readAllBytes();
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
        return new ScanOutcome(report.passed(), report.failures(), report.width(), report.height(), report.pageCount());
    }

}
