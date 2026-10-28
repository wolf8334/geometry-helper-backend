package com.xhr.geometryhelperbackend.dto;

import java.time.OffsetDateTime;

/**
 * 历史记录中的一段模型讲解。
 *
 * @param part      段落序号，从 1 开始
 * @param content   讲解正文
 * @param modelName 生成该段落的模型
 * @param createdAt 生成时间
 */
public record SolutionPart(int part, String content, String modelName,
        OffsetDateTime createdAt) {
}
