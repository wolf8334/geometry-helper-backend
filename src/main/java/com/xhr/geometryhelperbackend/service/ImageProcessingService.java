package com.xhr.geometryhelperbackend.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

/**
 * 上传图片预处理服务。
 *
 * <p>Bedrock / Claude 对单张图片有 5MB 的大小限制。移动端上传的原图可能超过该限制，
 * 因此在把图片交给 Spring AI 之前统一在这里检查、缩放到合理尺寸并重新编码。
 * 原始图片字节不会被修改，仍可保留给历史记录；模型只接收 {@link ProcessedImage#bytes()}。</p>
 *
 * <p>压缩策略：</p>
 * <ol>
 *     <li>原本就是模型支持的格式，且 Base64 编码后仍小于 5MB 时直接使用，避免无意义的重编码；</li>
 *     <li>BMP、TIFF 等格式即使很小也转换为模型支持的格式；</li>
 *     <li>最长边优先缩放到 2400 像素，JPEG 初始质量 0.85；</li>
 *     <li>如果仍超过限制，先降低 JPEG 质量，再逐步降低分辨率，直到满足限制。</li>
 * </ol>
 */
@Service
public class ImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingService.class);

    /**
     * Bedrock / Claude 单张图片的硬限制：5MB。
     *
     * <p>Bedrock 按 Base64 编码后的内容计算大小，而 Base64 会膨胀约 1/3。
     * 因此这里把原始图片控制在 3.7MB 以内：3.7MB 原始字节 Base64 后约 4.93MB，
     * 再给 data URI 前缀和 JSON 字段留出余量。</p>
     */
    static final long MAX_MODEL_PAYLOAD_BYTES = 5L * 1024 * 1024;

    static final long MAX_IMAGE_BYTES = 3_700_000L;

    /**
     * 压缩后图片的最长边。2400 像素通常足以保留题目中的小字、上下标和几何标记。
     */
    static final int MAX_LONG_SIDE = 2400;

    /**
     * 为避免文字完全不可读，分辨率最低只降到这个值。
     */
    static final int MIN_LONG_SIDE = 800;

    static final float INITIAL_JPEG_QUALITY = 0.85f;

    static final float MIN_JPEG_QUALITY = 0.55f;

    private static final float JPEG_QUALITY_STEP = 0.05f;

    private static final double RESOLUTION_STEP = 0.82d;

    /**
     * 这些格式模型可以直接接收，不需要为了格式而重编码。
     */
    private static final Set<String> MODEL_SUPPORTED_SUBTYPES = Set.of(
            "jpeg", "jpg", "png", "webp", "gif");

    public ImageProcessingService() {
        // 避免 ImageIO 把临时文件写到系统盘；图片本身已经在内存中。
        ImageIO.setUseCache(false);
    }

    /**
     * 处理上传图片。返回值只用于本次模型调用，原始字节不会被修改。
     *
     * @param originalBytes   原始图片字节
     * @param sourceMimeType  上传时解析出的 MIME 类型
     * @param originalFilename 原始文件名，仅用于日志和错误提示
     * @return 可以直接交给 Spring AI 的图片
     */
    public ProcessedImage process(byte[] originalBytes, MimeType sourceMimeType,
            String originalFilename) {
        if (originalBytes == null || originalBytes.length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String subtype = normalizeSubtype(sourceMimeType);
        boolean modelSupported = MODEL_SUPPORTED_SUBTYPES.contains(subtype);

        if (modelSupported && originalBytes.length < MAX_IMAGE_BYTES) {
            log.debug("图片无需压缩 filename={} mime={} size={}KB",
                    safeFilename(originalFilename), subtype, originalBytes.length / 1024);
            return new ProcessedImage(originalBytes, sourceMimeType, 0, 0, false);
        }

        log.info("开始处理图片 filename={} mime={} size={}KB",
                safeFilename(originalFilename), subtype, originalBytes.length / 1024);

        BufferedImage decoded = readImage(originalBytes, originalFilename);
        BufferedImage opaque = toOpaqueRgb(decoded);
        BufferedImage current = resizeToMaxLongSide(opaque, MAX_LONG_SIDE);
        int longSide = Math.max(current.getWidth(), current.getHeight());
        float quality = INITIAL_JPEG_QUALITY;
        byte[] encoded = null;

        while (true) {
            encoded = writeJpeg(current, quality);
            if (encoded.length < MAX_IMAGE_BYTES) {
                break;
            }

            log.debug("图片压缩后仍超限 size={}KB quality={} longSide={}",
                    encoded.length / 1024, quality, longSide);

            if (quality > MIN_JPEG_QUALITY + 0.001f) {
                quality = Math.max(MIN_JPEG_QUALITY, quality - JPEG_QUALITY_STEP);
                continue;
            }

            if (longSide <= MIN_LONG_SIDE) {
                throw new IllegalArgumentException(
                        "图片压缩后仍超过模型大小限制，请裁剪图片或降低分辨率后重试");
            }

            int nextLongSide = Math.max(MIN_LONG_SIDE,
                    (int) Math.round(longSide * RESOLUTION_STEP));
            current = resizeToLongSide(current, nextLongSide);
            longSide = Math.max(current.getWidth(), current.getHeight());
            quality = INITIAL_JPEG_QUALITY;
        }

        long estimatedBase64Bytes = ((long) encoded.length + 2) / 3 * 4;
        log.info("图片处理完成 filename={} 原始={}KB 输出={}KB 预计Base64={}KB 上限={}KB 尺寸={}x{}",
                safeFilename(originalFilename), originalBytes.length / 1024, encoded.length / 1024,
                estimatedBase64Bytes / 1024, MAX_MODEL_PAYLOAD_BYTES / 1024,
                current.getWidth(), current.getHeight());

        return new ProcessedImage(encoded, MimeType.valueOf("image/jpeg"),
                current.getWidth(), current.getHeight(), true);
    }

    private String normalizeSubtype(MimeType mimeType) {
        if (mimeType == null || !StringUtils.hasText(mimeType.getSubtype())) {
            throw new IllegalArgumentException("无法识别图片格式，仅支持 jpg、jpeg、png、webp、gif、bmp、tiff");
        }
        String subtype = mimeType.getSubtype().toLowerCase(Locale.ROOT);
        return switch (subtype) {
            case "jpg", "pjpeg", "x-jpg" -> "jpeg";
            case "tif", "x-tiff" -> "tiff";
            case "x-ms-bmp", "x-bmp" -> "bmp";
            case "x-png" -> "png";
            case "x-webp" -> "webp";
            default -> subtype;
        };
    }

    private BufferedImage readImage(byte[] bytes, String originalFilename) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException exception) {
            log.warn("图片解码失败 filename={}", safeFilename(originalFilename), exception);
            throw new IllegalArgumentException(
                    "图片读取失败，请确认图片未损坏，或转换为 JPG/PNG 后重试", exception);
        }
        if (image == null) {
            throw new IllegalArgumentException(
                    "无法读取图片内容，请确认图片未损坏，或转换为 JPG/PNG 后重试");
        }
        return image;
    }

    /**
     * JPEG 不支持透明通道。透明 PNG/WebP 统一铺白底，避免文字周围变黑。
     */
    private BufferedImage toOpaqueRgb(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private BufferedImage resizeToMaxLongSide(BufferedImage source, int maxLongSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longSide = Math.max(width, height);
        if (longSide <= maxLongSide) {
            return source;
        }
        double scale = (double) maxLongSide / longSide;
        return resizeTo(source, Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    private BufferedImage resizeToLongSide(BufferedImage source, int targetLongSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longSide = Math.max(width, height);
        if (longSide == targetLongSide) {
            return source;
        }
        double scale = (double) targetLongSide / longSide;
        return resizeTo(source, Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
    }

    /**
     * 逐级缩小，避免一次性大幅缩放导致细线和小字出现明显锯齿。
     */
    private BufferedImage resizeTo(BufferedImage source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        while (width > targetWidth * 2 && height > targetHeight * 2) {
            width = Math.max(targetWidth, width / 2);
            height = Math.max(targetHeight, height / 2);
            current = drawScaled(current, width, height);
        }
        return drawScaled(current, targetWidth, targetHeight);
    }

    private BufferedImage drawScaled(BufferedImage source, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private byte[] writeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new AiServiceException("当前环境缺少 JPEG 编码器，无法压缩图片");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionQuality(quality);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            if (imageOutput == null) {
                throw new AiServiceException("创建图片输出流失败，请稍后重试");
            }
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), writeParam);
            imageOutput.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AiServiceException("图片压缩失败，请稍后重试", exception);
        } finally {
            writer.dispose();
        }
    }

    private String safeFilename(String originalFilename) {
        return StringUtils.hasText(originalFilename) ? originalFilename : "(unknown)";
    }

    /**
     * 处理后的图片。{@code width}/{@code height} 为 0 表示直接透传、未解码。
     */
    public record ProcessedImage(byte[] bytes, MimeType mimeType, int width, int height,
            boolean compressed) {
    }
}
