package com.xhr.geometryhelperbackend.exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.xhr.geometryhelperbackend.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 全局异常处理器。
 *
 * <p>目标：无论出现参数错误、文件过大还是 AI 调用失败，都返回结构一致的 JSON，
 * 避免把 Java 异常堆栈直接暴露给前端。</p>
 *
 * <p>注意：{@link MaxUploadSizeExceededException} 是 {@link MultipartException}
 * 的子类，Spring 会优先匹配更具体的异常处理方法。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 上传文件超过 spring.servlet.multipart.max-file-size 限制。
     *
     * @return 413 Payload Too Large
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        log.warn("upload too large msg={}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(error("上传文件过大，请压缩图片后重试"));
    }

    /**
     * 缺少 multipart 字段或普通请求参数。
     *
     * @return 400 Bad Request
     */
    @ExceptionHandler({ MissingServletRequestPartException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<ErrorResponse> handleMissingPart(Exception exception) {
        log.warn("missing part msg={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(error("缺少文件字段 file，请使用 multipart/form-data 上传图片"));
    }

    /**
     * 其他 multipart 解析异常，例如请求不是 multipart/form-data。
     *
     * @return 400 Bad Request
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException exception) {
        log.warn("multipart parse failed msg={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(error("文件上传失败，请确认请求格式为 multipart/form-data"));
    }

    /**
     * JSON 请求体格式错误，例如字段类型不匹配、JSON 语法错误。
     *
     * @return 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        log.warn("body parse failed msg={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(error("请求体格式错误，请检查 JSON 格式和字段类型"));
    }

    /**
     * 不支持的 Content-Type。
     *
     * @return 415 Unsupported Media Type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        log.warn("content type unsupported value={}", exception.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error("不支持的 Content-Type，请使用 application/json 或 multipart/form-data"));
    }

    /**
     * 业务参数错误，例如空文件、不支持的图片格式。
     *
     * @return 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("bad request msg={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(error(exception.getMessage()));
    }

    /**
     * 大模型调用失败。
     *
     * <p>使用 502 而不是 500，表示“网关/上游服务异常”，前端可以据此提示用户重试。</p>
     *
     * @return 502 Bad Gateway
     */
    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiService(AiServiceException exception) {
        log.error("ai call failed msg={}", exception.getMessage(), exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error(exception.getMessage()));
    }

    /**
     * 兜底异常处理。
     *
     * <p>日志中保留完整堆栈，响应中只返回通用提示，防止泄露内部实现。</p>
     *
     * @return 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("服务器内部错误，请稍后重试"));
    }

    /**
     * 构造统一错误响应。
     *
     * @param message 错误提示
     * @return 错误响应体
     */
    private ErrorResponse error(String message) {
        return new ErrorResponse(message, LocalDateTime.now().format(TIMESTAMP_FORMATTER));
    }
}
