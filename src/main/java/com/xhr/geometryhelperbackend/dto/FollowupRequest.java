package com.xhr.geometryhelperbackend.dto;

/**
 * {@code POST /api/followup} 的请求体。
 *
 * @param questionId 题目主键
 * @param message    用户追问内容
 * @param step       提问时用户已经展开到的讲解段落；为空时使用已生成的最大段落
 */
public record FollowupRequest(Long questionId, String message, Integer step) {
}
