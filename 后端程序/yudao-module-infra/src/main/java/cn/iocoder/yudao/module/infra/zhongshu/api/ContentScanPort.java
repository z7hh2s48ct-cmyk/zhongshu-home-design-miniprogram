package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 内容扫描端口（P4B：Core 校验器对 AI 输出的格式/安全/质量底线检查）
 *
 * 实现方：design 模块（复用 AssetContentScanner 全套检查）。
 */
public interface ContentScanPort {

    ScanOutcome scan(String declaredMime, byte[] content);

    record ScanOutcome(boolean passed, java.util.List<String> failures,
                       Integer width, Integer height, Integer pageCount, byte[] sanitizedContent, String evidence) {
        public ScanOutcome(boolean passed, java.util.List<String> failures, Integer width, Integer height, Integer pageCount) {
            this(passed, failures, width, height, pageCount, null, "test-scanner");
        }
    }

}
