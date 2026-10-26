package com.xhr.geometryhelperbackend.dto;

import java.util.List;

/**
 * {@code POST /api/chat} 的请求体。
 *
 * <p>{@code message} 是当前这一轮的用户输入；{@code history} 是可选的
 * 历史消息，用于支持多轮对话。服务端会按顺序拼接历史消息，再发送当前
 * {@code message}。</p>
 *
 * <p>示例：</p>
 * <pre>
 * {
 *   "message": "那它的面积是多少？",
 *   "history": [
 *     { "role": "user", "content": "一个三角形的底是 6，高是 4。" },
 *     { "role": "assistant", "content": "它的面积是 12。" }
 *   ]
 * }
 * </pre>
 *
 * @param message 当前用户消息
 * @param history 历史消息，可为空
 */
public record ChatRequest(String message, List<ChatMessage> history) {
}
