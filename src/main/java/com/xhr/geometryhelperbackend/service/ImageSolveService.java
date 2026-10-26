package com.xhr.geometryhelperbackend.service;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.xhr.geometryhelperbackend.config.AiConfig;
import com.xhr.geometryhelperbackend.config.AiProperties;
import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片理解业务服务。
 *
 * <p>职责：把上传的图片转换成 Spring AI 的 {@link Media}，连同提示词发送给
 * 多模态大模型，并返回模型生成的文本描述。</p>
 *
 * <p>这里选择“一次性读取到内存”的方式，原因是当前上传上限只有 10MB，
 * 且 Spring AI 的 {@code Media} 需要拿到完整字节或 Resource。若后续要支持
 * 更大的图片，建议先压缩/缩放，或者改为对象存储 URL 再传给模型。</p>
 */
@Service
public class ImageSolveService {

    private static final Logger log = LoggerFactory.getLogger(ImageSolveService.class);

    /**
     * 当浏览器/客户端没有提供可靠的 Content-Type 时，根据文件扩展名兜底推断 MIME。
     * 只列出主流且多模态模型普遍支持的图片格式。
     */
    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif"
    );

    /**
     * 主流多模态模型普遍支持的图片子类型。
     * 提前拦截 BMP、TIFF 等格式，避免请求发出后才被供应商拒绝。
     */
    private static final Set<String> SUPPORTED_IMAGE_SUBTYPES = Set.of("jpeg", "png", "webp", "gif");

    private final ChatClient chatClient;

    private final AiProperties aiProperties;

    public ImageSolveService(@Qualifier(AiConfig.VISION_CHAT_CLIENT) ChatClient chatClient,
            AiProperties aiProperties) {
        this.chatClient = chatClient;
        this.aiProperties = aiProperties;
    }

    /**
     * 调用多模态大模型描述图片内容。
     *
     * @param file 上传的图片文件，字段名由 Controller 约定为 {@code file}
     * @return 大模型返回的图片内容描述
     * @throws IllegalArgumentException 文件为空或不是受支持的图片格式
     * @throws AiServiceException      读取文件失败或大模型调用失败
     */
    public String describeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        // 1. 解析并校验 MIME 类型。不能只相信客户端传来的 Content-Type，
        //    因此 Content-Type 缺失或不合法时再根据扩展名兜底。
        MimeType mimeType = resolveMimeType(file);
        if (!"image".equalsIgnoreCase(mimeType.getType())) {
            throw new IllegalArgumentException("只支持图片文件，当前文件类型为：" + mimeType);
        }

        // 2. 读取图片字节。MultipartFile 的临时文件会在请求结束后被清理，
        //    所以必须在请求线程内完成读取。
        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException exception) {
            log.error("image read failed file={}", file.getOriginalFilename(), exception);
            throw new AiServiceException("读取上传图片失败，请稍后重试", exception);
        }

        // 3. 构造 Spring AI 多媒体对象。Media 内部会把字节转换成模型需要的
        //    base64 data URL（由具体模型实现负责）。
        Media imageMedia = Media.builder().mimeType(mimeType).data(imageBytes).build();

        long startNanos = System.nanoTime();
        try {
            // 4. 发送“用户提示词 + 图片”。系统提示词已在 AiConfig 中设置为默认值。
            String answer = this.chatClient.prompt()
                    .user(userSpec -> userSpec
                            .text(this.aiProperties.getVision().getUserPrompt())
                            .media(imageMedia))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                throw new AiServiceException("大模型未返回有效内容，请稍后重试");
            }

            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("image solve ok file={} size={}B mime={} cost={}ms",
                    file.getOriginalFilename(), imageBytes.length, mimeType, costMillis);
            return answer.trim();
        } catch (AiServiceException exception) {
            // 保留业务异常的原始信息，避免被下面的通用捕获再次包装。
            throw exception;
        } catch (Exception exception) {
            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("image solve failed file={} size={}B cost={}ms",
                    file.getOriginalFilename(), imageBytes.length, costMillis, exception);
            throw new AiServiceException("大模型服务调用失败，请检查 API Key、模型名称或网络后重试", exception);
        }
    }

    /**
     * 解析上传文件的 MIME 类型。
     *
     * <p>优先使用客户端提供的 Content-Type；如果缺失、格式错误或不是 image/*，
     * 再根据文件扩展名兜底。两者都无法识别时直接抛出参数异常。</p>
     *
     * @param file 上传文件
     * @return 图片 MIME 类型
     */
    private MimeType resolveMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)) {
            try {
                MimeType parsed = MimeType.valueOf(contentType);
                if ("image".equalsIgnoreCase(parsed.getType())) {
                    return normalizeImageMimeType(parsed);
                }
            } catch (IllegalArgumentException ignored) {
                // Content-Type 格式不合法时继续尝试扩展名兜底。
            }
        }

        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        if (StringUtils.hasText(extension)) {
            String mimeType = EXTENSION_TO_MIME_TYPE.get(extension.toLowerCase(Locale.ROOT));
            if (mimeType != null) {
                return MimeType.valueOf(mimeType);
            }
        }

        throw new IllegalArgumentException("无法识别图片格式，仅支持 jpg、jpeg、png、webp、gif");
    }

    /**
     * 规范化图片 MIME 类型，并拦截模型普遍不支持的格式。
     *
     * <p>部分客户端会发送 {@code image/jpg} 或 {@code image/pjpeg}，
     * 它们都不是标准 MIME，统一转换成 {@code image/jpeg}，
     * 避免 OpenAI 兼容接口因格式不合法而拒绝请求。</p>
     *
     * @param mimeType 原始 MIME 类型
     * @return 规范化后的 MIME 类型
     */
    private MimeType normalizeImageMimeType(MimeType mimeType) {
        String subtype = mimeType.getSubtype().toLowerCase(Locale.ROOT);
        if ("jpg".equals(subtype) || "pjpeg".equals(subtype)) {
            return MimeType.valueOf("image/jpeg");
        }
        if (!SUPPORTED_IMAGE_SUBTYPES.contains(subtype)) {
            throw new IllegalArgumentException("仅支持 jpg、jpeg、png、webp、gif 格式的图片");
        }
        return MimeType.valueOf("image/" + subtype);
    }
}
