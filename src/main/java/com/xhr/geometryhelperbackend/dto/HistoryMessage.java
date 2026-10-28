package com.xhr.geometryhelperbackend.dto;

import java.time.OffsetDateTime;

/**
 * 历史记录中的一条对话消息。
 *
 * @param id           消息主键
 * @param sequenceNo   同一道题内的消息序号
 * @param role         消息角色：user、assistant 等
 * @param phase        阶段：recognize、solve、followup
 * @param messageType  消息类型
 * @param stepNo       关联的讲解段落，没有时为 null
 * @param modelName    模型名称，用户消息为空
 * @param content      消息正文
 * @param reasoning    模型推理内容
 * @param inputTokens  输入 Token
 * @param outputTokens 输出 Token
 * @param durationMs   调用耗时
 * @param createdAt    创建时间
 */
public record HistoryMessage(long id, int sequenceNo, String role, String phase,
        String messageType, Integer stepNo, String modelName, String content,
        String reasoning, Integer inputTokens, Integer outputTokens, Long durationMs,
        OffsetDateTime createdAt) {
}
