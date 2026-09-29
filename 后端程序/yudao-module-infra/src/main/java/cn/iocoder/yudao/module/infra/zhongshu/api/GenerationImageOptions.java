package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.Locale;
import java.util.Set;

/** 用户选择的出图规格；像素映射由 Runtime 负责。 */
public record GenerationImageOptions(String resolution, String orientation) {

    private static final Set<String> STAGES = Set.of("FLAT", "ELEVATION");
    private static final Set<String> RESOLUTIONS = Set.of("2K", "4K");
    private static final Set<String> ORIENTATIONS = Set.of("LANDSCAPE", "PORTRAIT");

    public static GenerationImageOptions normalize(String stage, String resolution, String orientation) {
        String normalizedStage = upper(stage);
        if (!STAGES.contains(normalizedStage)) {
            throw new IllegalArgumentException("生成阶段无效");
        }
        String normalizedResolution = blank(resolution) ? "2K" : upper(resolution);
        String normalizedOrientation = blank(orientation)
                ? ("FLAT".equals(normalizedStage) ? "PORTRAIT" : "LANDSCAPE")
                : upper(orientation);
        if (!RESOLUTIONS.contains(normalizedResolution)) {
            throw new IllegalArgumentException("仅支持 2K 或 4K 分辨率");
        }
        if (!ORIENTATIONS.contains(normalizedOrientation)) {
            throw new IllegalArgumentException("仅支持横屏或竖屏");
        }
        return new GenerationImageOptions(normalizedResolution, normalizedOrientation);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
