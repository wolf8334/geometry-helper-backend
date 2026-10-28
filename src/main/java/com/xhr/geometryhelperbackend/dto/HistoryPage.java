package com.xhr.geometryhelperbackend.dto;

import java.util.List;

/**
 * 历史记录分页结果。
 *
 * @param items 当前页的题目
 * @param total 符合条件的总题目数
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 */
public record HistoryPage(List<QuestionDetail> items, long total, int page, int size) {
}
