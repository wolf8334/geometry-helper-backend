package com.xhr.geometryhelperbackend.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.NoSuchElementException;

import com.xhr.geometryhelperbackend.dto.HistoryPage;
import com.xhr.geometryhelperbackend.dto.QuestionDetail;
import com.xhr.geometryhelperbackend.service.QuestionHistoryService;
import com.xhr.geometryhelperbackend.service.QuestionImage;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史记录查询接口。
 *
 * <p>当前所有记录都属于 {@code user_id = 0} 的匿名用户，登录接入后再根据认证信息
 * 替换服务端的默认用户。</p>
 */
@RestController
@RequestMapping("/api")
public class HistoryController {

    private final QuestionHistoryService questionHistoryService;

    public HistoryController(QuestionHistoryService questionHistoryService) {
        this.questionHistoryService = questionHistoryService;
    }

    /**
     * 分页查询历史题目。
     *
     * @param from     开始日期，包含当天
     * @param to       结束日期，包含当天
     * @param keyword  题目、讲解或追问关键词
     * @param followup 是否只看有/无追问；为空时不过滤
     * @param page     页码，从 1 开始
     * @param size     每页条数，最大 50
     * @return 分页结果
     */
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public HistoryPage list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "followup", required = false) Boolean followup,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return this.questionHistoryService.listQuestions(from, to, keyword, followup, page, size);
    }

    /**
     * 查询单道题的完整历史，包括识别、分段讲解和追问。
     *
     * @param id 题目 ID
     * @return 题目详情
     */
    @GetMapping(value = "/history/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionDetail detail(@PathVariable("id") long id) {
        return this.questionHistoryService.getQuestionDetail(id);
    }

    /**
     * 读取历史题目保存的原图。
     *
     * @param id 题目 ID
     * @return 原图二进制
     */
    @GetMapping("/history/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable("id") long id) {
        QuestionImage image = this.questionHistoryService.getQuestionImage(id);
        if (image == null) {
            throw new NoSuchElementException("未找到题目图片");
        }

        return ResponseEntity.ok()
                .contentType(resolveImageMediaType(image))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(image.data());
    }

    /**
     * 优先使用上传时记录的 MIME；记录缺失或不是图片时，再按文件名推断。
     */
    private MediaType resolveImageMediaType(QuestionImage image) {
        if (StringUtils.hasText(image.contentType())) {
            try {
                MediaType mediaType = MediaType.parseMediaType(image.contentType());
                if ("image".equalsIgnoreCase(mediaType.getType())) {
                    return "image/jpg".equalsIgnoreCase(mediaType.getSubtype())
                            ? MediaType.IMAGE_JPEG
                            : mediaType;
                }
            } catch (IllegalArgumentException ignored) {
                // 继续按文件名推断。
            }
        }

        String extension = StringUtils.getFilenameExtension(image.filename());
        if (StringUtils.hasText(extension)) {
            return switch (extension.toLowerCase(Locale.ROOT)) {
                case "png" -> MediaType.IMAGE_PNG;
                case "gif" -> MediaType.IMAGE_GIF;
                case "webp" -> MediaType.parseMediaType("image/webp");
                case "bmp" -> MediaType.parseMediaType("image/bmp");
                case "tif", "tiff" -> MediaType.parseMediaType("image/tiff");
                default -> MediaType.IMAGE_JPEG;
            };
        }
        return MediaType.IMAGE_JPEG;
    }
}
