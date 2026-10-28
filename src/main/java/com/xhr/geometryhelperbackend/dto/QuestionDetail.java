package com.xhr.geometryhelperbackend.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一道题的完整历史记录。
 *
 * @param id               题目主键
 * @param userId           用户主键，当前默认 0
 * @param questionText     视觉模型识别出的题目复述
 * @param imageContentType 原始图片 MIME 类型
 * @param imageFilename    原始图片文件名
 * @param hasImage         是否保存了图片
 * @param createdAt        题目创建时间
 * @param maxSolvedPart    已生成的最大讲解段落，没有讲解时为 0
 * @param messages         全部消息，按 sequenceNo 升序
 * @param solutionParts    模型讲解段落
 * @param followups        追问消息
 * @param followupStep     追问记录中最大的讲解段落，没有追问时为 null
 * @param followupCount    已发起的追问次数，只统计用户提问
 * @param maxFollowupQuestions 每道题最多允许的追问次数
 */
public record QuestionDetail(long id, long userId, String questionText,
        String imageContentType, String imageFilename, boolean hasImage,
        OffsetDateTime createdAt, int maxSolvedPart, List<HistoryMessage> messages,
        List<SolutionPart> solutionParts, List<FollowupMessage> followups,
        Integer followupStep, int followupCount, int maxFollowupQuestions) {
}
