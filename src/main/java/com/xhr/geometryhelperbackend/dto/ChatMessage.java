package com.xhr.geometryhelperbackend.dto;

/**
 * 普通对话接口中的一条历史消息。
 *
 * @param role    角色，只支持 {@code user} 和 {@code assistant}
 * @param content 消息内容
 */
public record ChatMessage(String role, String content) {
}
