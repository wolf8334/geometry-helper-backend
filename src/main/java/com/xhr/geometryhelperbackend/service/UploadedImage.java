package com.xhr.geometryhelperbackend.service;

import java.io.IOException;

import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 已经读入内存的上传图片。
 *
 * <p>流式请求会在返回 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * 之后继续在后台线程运行，而 Servlet 容器的 multipart 临时文件会在请求结束时清理。
 * 因此必须在请求线程内一次性读出图片字节，后续模型调用和入库都使用这个对象。</p>
 *
 * @param data        原始图片字节
 * @param contentType 浏览器提供的 Content-Type，可能为空
 * @param filename    原始文件名，可能为空
 */
public record UploadedImage(byte[] data, String contentType, String filename) {

    /**
     * 读取上传文件。空文件返回 {@code null}，由业务层决定是否允许没有图片。
     *
     * @param file 上传文件，可为空
     * @return 内存中的图片
     */
    public static UploadedImage from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        try {
            return new UploadedImage(file.getBytes(), file.getContentType(),
                    file.getOriginalFilename());
        } catch (IOException exception) {
            throw new AiServiceException("读取上传图片失败，请稍后重试", exception);
        }
    }

    /**
     * 是否包含有效图片字节。
     *
     * @return 有图片时为 {@code true}
     */
    public boolean hasData() {
        return this.data != null && this.data.length > 0;
    }

    /**
     * 入库时使用的 Content-Type。浏览器没有提供时退回二进制类型。
     *
     * @return Content-Type
     */
    public String storedContentType() {
        return StringUtils.hasText(this.contentType)
                ? this.contentType
                : "application/octet-stream";
    }
}
