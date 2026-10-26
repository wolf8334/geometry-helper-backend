package com.xhr.geometryhelperbackend.dto;

/**
 * {@code GET /api/ping} 的响应体。
 *
 * @param msg       服务状态说明，固定为“服务正常”
 * @param timestamp 当前服务器时间，格式为 {@code yyyy-MM-dd HH:mm:ss}
 */
public record PingResponse(String msg, String timestamp) {
}
