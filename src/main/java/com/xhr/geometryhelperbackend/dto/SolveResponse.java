package com.xhr.geometryhelperbackend.dto;

/**
 * {@code POST /api/solve} 的响应体。
 *
 * <p>字段名严格按照接口约定：{@code msg} 固定为“图片上传成功”，
 * {@code answer} 为大模型返回的图片内容描述。</p>
 *
 * @param msg    上传结果说明
 * @param answer 大模型返回的图片内容描述
 */
public record SolveResponse(String msg, String answer) {
}
