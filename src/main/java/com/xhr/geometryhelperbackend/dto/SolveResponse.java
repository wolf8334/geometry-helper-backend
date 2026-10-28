package com.xhr.geometryhelperbackend.dto;

/**
 * {@code POST /api/solve} 的响应体。
 *
 * <p>该接口分阶段返回：{@code stage=confirm} 时返回题目复述，等待用户核对；
 * {@code stage=part} 时返回当前段落的讲解内容。</p>
 *
 * @param msg        阶段说明
 * @param stage      当前阶段，{@code confirm} 或 {@code part}
 * @param analysis   题目复述（确认阶段）
 * @param answer     当前段落的讲解内容（分步讲解阶段）
 * @param part       当前讲解段落，从 1 开始；确认阶段为 0
 * @param totalParts 分步讲解总段数
 * @param done       分步讲解是否已经全部完成
 * @param questionId 题目历史记录 ID，后续讲解和追问需要回传
 */
public record SolveResponse(String msg, String stage, String analysis, String answer,
        int part, int totalParts, boolean done, Long questionId) {
}
