package cn.iocoder.yudao.module.design.asset;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 资产内容安全扫描（上传完成后、入库前执行；架构 §6.9 / §10.4）
 *
 * 检查项：魔数 vs 声明 MIME、像素守卫（先查头再解码，防解压炸弹）、
 * JPEG EXIF/GPS 检测、EICAR 恶意样本标记；图片统一经 ImageIO 重编码剥离元数据。
 */
@Component
public class AssetContentScanner {

    public record ScanReport(boolean passed, List<String> failures, byte[] sanitizedContent,
                             Integer width, Integer height, Integer pageCount,
                             boolean exifDetected) {
    }

    private static final long MAX_PIXELS = 40_000_000L; // 40MP
    private static final int MAX_DIMENSION = 10_000;

    public ScanReport scan(String declaredMime, byte[] content) {
        List<String> failures = new ArrayList<>();
        Integer width = null;
        Integer height = null;
        Integer pageCount = null;
        boolean exifDetected = false;
        byte[] sanitized = content;

        // 1. 魔数 vs 声明 MIME
        String actualMime = sniffMime(content);
        if (actualMime == null || !actualMime.equals(declaredMime)) {
            failures.add("MAGIC_NUMBER: 声明 " + declaredMime + " 实际 " + actualMime);
        }

        // 2. 恶意样本标记（EICAR；真实杀毒 Adapter 后续接入）
        if (new String(content, 0, Math.min(content.length, 68), java.nio.charset.StandardCharsets.US_ASCII)
                .contains("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR")) {
            failures.add("MALWARE: EICAR 测试样本");
        }

        // 3. 图片类：像素守卫（仅读头部）→ EXIF 检测 → 重编码剥离
        if ("image/png".equals(actualMime)) {
            long[] dims = pngDimensions(content);
            if (dims == null || dims[0] <= 0 || dims[1] <= 0) {
                failures.add("DECODE: PNG 头解析失败");
            } else if (dims[0] * dims[1] > MAX_PIXELS || dims[0] > MAX_DIMENSION || dims[1] > MAX_DIMENSION) {
                failures.add("PIXEL_GUARD: " + dims[0] + "x" + dims[1] + " 超限");
            } else {
                width = (int) dims[0];
                height = (int) dims[1];
                exifDetected = containsPngExifChunk(content); // 重编码统一剥离，留痕审计
                try {
                    byte[] reEncoded = reEncode(content, "png");
                    if (reEncoded != null) {
                        sanitized = reEncoded;
                    } else {
                        failures.add("DECODE: PNG 重编码失败");
                    }
                } catch (IOException e) {
                    failures.add("DECODE: 图片解码失败（疑似损坏或炸弹）");
                }
            }
        } else if ("image/jpeg".equals(actualMime)) {
            long[] dims = jpegDimensions(content);
            boolean dimensionOk = dims != null && dims[0] > 0 && dims[1] > 0
                    && dims[0] * dims[1] <= MAX_PIXELS && dims[0] <= MAX_DIMENSION && dims[1] <= MAX_DIMENSION;
            if (dims == null) {
                failures.add("DECODE: JPEG 头解析失败");
            } else if (!dimensionOk) {
                // 守卫失败绝不解码：防止头声明超大尺寸的解压炸弹（OOM）
                failures.add("PIXEL_GUARD: " + dims[0] + "x" + dims[1] + " 超限");
            } else {
                width = (int) dims[0];
                height = (int) dims[1];
            }
            exifDetected = containsJpegExif(content); // 重编码统一剥离，留痕审计
            if (dimensionOk) {
                try {
                    byte[] reEncoded = reEncode(content, "jpg");
                    if (reEncoded != null) {
                        sanitized = reEncoded;
                    } else {
                        failures.add("DECODE: JPEG 重编码失败");
                    }
                } catch (IOException e) {
                    failures.add("DECODE: 图片解码失败（疑似损坏或炸弹）");
                }
            }
        } else if ("application/pdf".equals(actualMime)) {
            pageCount = countPdfPages(content);
        }

        return new ScanReport(failures.isEmpty(), failures, sanitized, width, height, pageCount, exifDetected);
    }

    private String sniffMime(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "image/png";
        }
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-') {
            return "application/pdf";
        }
        return null;
    }

    /** PNG IHDR：宽度/高度（大端）位于固定偏移 */
    private long[] pngDimensions(byte[] c) {
        if (c.length < 24) {
            return null;
        }
        long w = ((c[16] & 0xFFL) << 24) | ((c[17] & 0xFFL) << 16) | ((c[18] & 0xFFL) << 8) | (c[19] & 0xFFL);
        long h = ((c[20] & 0xFFL) << 24) | ((c[21] & 0xFFL) << 16) | ((c[22] & 0xFFL) << 8) | (c[23] & 0xFFL);
        return new long[]{w, h};
    }

    /** JPEG SOF0~SOF15（跳过 C4/CC/DA）解析尺寸 */
    private long[] jpegDimensions(byte[] c) {
        int i = 2;
        while (i + 9 < c.length) {
            if ((c[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = c[i + 1] & 0xFF;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9) || marker == 0x01) {
                i += 2;
                continue;
            }
            int length = ((c[i + 2] & 0xFF) << 8) | (c[i + 3] & 0xFF);
            if ((marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                long h = ((c[i + 5] & 0xFFL) << 8) | (c[i + 6] & 0xFFL);
                long w = ((c[i + 7] & 0xFFL) << 8) | (c[i + 8] & 0xFFL);
                return new long[]{w, h};
            }
            i += 2 + length;
        }
        return null;
    }

    private boolean containsJpegExif(byte[] c) {
        int limit = Math.min(c.length, 64 * 1024);
        for (int i = 0; i + 6 < limit; i++) {
            if ((c[i] & 0xFF) == 0xFF && (c[i + 1] & 0xFF) == 0xE1) {
                // APP1 段：FF E1 <2 字节长度> "Exif\0\0"；在 E1 后 4 字节内匹配，兼容不同实现
                for (int offset = 2; offset <= 4 && i + offset + 6 <= limit; offset++) {
                    if (c[i + offset] == 'E' && c[i + offset + 1] == 'x' && c[i + offset + 2] == 'i'
                            && c[i + offset + 3] == 'f' && c[i + offset + 4] == 0 && c[i + offset + 5] == 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean containsPngExifChunk(byte[] c) {
        String header = new String(c, 0, Math.min(c.length, 1024), java.nio.charset.StandardCharsets.US_ASCII);
        return header.contains("eXIf");
    }

    private int countPdfPages(byte[] c) {
        String text = new String(c, java.nio.charset.StandardCharsets.US_ASCII);
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("/Type /Page", idx)) >= 0) {
            if (idx + 12 > text.length() || text.charAt(idx + 11) != 's') {
                count++;
            }
            idx += 11;
        }
        return Math.max(count, 1);
    }

    /** ImageIO 重编码：剥离全部元数据（EXIF/GPS）；失败返回 null */
    private byte[] reEncode(byte[] content, String format) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = ImageIO.write(image, format, out);
        return ok ? out.toByteArray() : null;
    }

}
