package com.xhr.geometryhelperbackend.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.xhr.geometryhelperbackend.dto.PingResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务健康检查接口。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    /**
     * 时间格式说明：
     * 需求中的 {@code yyyy-mm-dd hh24:mi:ss} 是 Oracle 的格式写法，
     * Java 的 {@link DateTimeFormatter} 对应写法为 {@code yyyy-MM-dd HH:mm:ss}：
     * <ul>
     *     <li>{@code MM}：月份（{@code mm} 在 Java 中表示分钟）</li>
     *     <li>{@code HH}：24 小时制（{@code hh24} 是 Oracle 写法）</li>
     *     <li>{@code mm}：分钟（{@code mi} 是 Oracle 写法）</li>
     * </ul>
     *
     * <p>DateTimeFormatter 是线程安全且不可变的，因此可以安全地作为静态常量复用。</p>
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 健康检查。
     *
     * <p>示例响应：</p>
     * <pre>
     * {
     *   "msg": "服务正常",
     *   "timestamp": "2026-10-26 15:30:00"
     * }
     * </pre>
     *
     * @return 服务状态和当前服务器时间
     */
    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public PingResponse ping() {
        return new PingResponse("服务正常", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
    }
}
