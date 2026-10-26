package com.xhr.geometryhelperbackend.dto;

/**
 * {@code POST /api/chat} 的响应体。
 *
 * @param msg    对话结果说明，固定为“对话成功”
 * @param answer 普通对话引擎返回的文本
 */
public record ChatResponse(String msg, String answer) {
}
