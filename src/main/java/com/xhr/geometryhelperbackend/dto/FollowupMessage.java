package com.xhr.geometryhelperbackend.dto;

import java.time.OffsetDateTime;

/**
 * 历史记录中的一条追问消息。
 *
 * @param id        消息主键
 * @param role      角色：user 或 assistant
 * @param content   消息正文
 * @param stepNo    提问时用户已经展开到的讲解段落
 * @param modelName 模型名称，用户消息为空
 * @param createdAt 创建时间
 */
public record FollowupMessage(long id, String role, String content, Integer stepNo,
        String modelName, OffsetDateTime createdAt) {
}
