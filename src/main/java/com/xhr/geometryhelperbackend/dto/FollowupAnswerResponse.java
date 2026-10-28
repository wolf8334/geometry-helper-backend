package com.xhr.geometryhelperbackend.dto;

import java.time.OffsetDateTime;

/**
 * 追问回复结果。
 *
 * @param msg         结果说明
 * @param questionId  题目主键
 * @param step        本次追问关联的讲解段落
 * @param answer      模型回复
 * @param modelName   模型名称
 * @param createdAt   回复创建时间
 * @param followupStep 该题所有追问中最大的讲解段落
 */
public record FollowupAnswerResponse(String msg, long questionId, int step, String answer,
        String modelName, OffsetDateTime createdAt, int followupStep) {
}
