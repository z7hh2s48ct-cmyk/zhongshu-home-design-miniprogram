package cn.iocoder.yudao.module.design.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统对象存储适配器（开发/测试用）。
 *
 * 装配条件（T13-01）：zhongshu.design.asset.storage.provider=local；真实腾讯云 COS 由 B2 的 cos 实现替换。
 * 生产禁止 local，由 ZhongshuWiringEnvironmentPostProcessor 启动守卫强制。
 */
@Component
@ConditionalOnProperty(prefix = "zhongshu.design.asset.storage", name = "provider", havingValue = "local")
public class LocalObjectStorageAdapter implements ObjectStoragePort {

    private final Path root;

    public LocalObjectStorageAdapter(@Value("${zhongshu.design.asset.storage-root:${java.io.tmpdir}/zhongshu-assets}") String root) {
        this.root = Path.of(root);
    }

    private Path resolve(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法 object key: " + objectKey);
        }
        return path;
    }

    @Override
    public String presignUploadUrl(String objectKey, long ttlSeconds) {
        return "local://" + objectKey;
    }

    @Override
    public boolean existsWithSize(String objectKey, long expectedSize) {
        Path path = resolve(objectKey);
        try {
            return Files.exists(path) && Files.size(path) == expectedSize;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException e) {
            throw new IllegalStateException("对象不存在: " + objectKey, e);
        }
    }

    @Override
    public void putObject(String objectKey, byte[] content) {
        Path path = resolve(objectKey);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("对象写入失败: " + objectKey, e);
        }
    }

    @Override
    public String presignDownloadUrl(String objectKey, long ttlSeconds) {
        return "local://" + objectKey + "?expires=" + (System.currentTimeMillis() + ttlSeconds * 1000);
    }

}
