package com.xhr.geometryhelperbackend.dto;

/**
 * 统一错误响应体。
 *
 * <p>为了避免把底层异常堆栈暴露给调用方，全局异常处理器只返回可读的错误信息
 * 和服务器时间。</p>
 *
 * @param msg       错误说明
 * @param timestamp 错误发生时间，格式为 {@code yyyy-MM-dd HH:mm:ss}
 */
public record ErrorResponse(String msg, String timestamp) {
}
