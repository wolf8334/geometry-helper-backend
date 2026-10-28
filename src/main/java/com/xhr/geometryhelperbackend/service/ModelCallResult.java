package com.xhr.geometryhelperbackend.service;

/**
 * 单次大模型调用的结果。
 *
 * <p>除了正文之外，还保留模型名称、Token 消耗、耗时、推理内容以及停止原因，
 * 便于把每次调用完整写入历史记录。</p>
 *
 * @param answer       模型返回的正文
 * @param modelName    实际使用的模型名称
 * @param inputTokens  输入 Token，模型未返回时为空
 * @param outputTokens 输出 Token，模型未返回时为空
 * @param durationMs   本次调用耗时，单位毫秒
 * @param reasoning    模型返回的推理内容，没有时为空
 * @param stopReason   模型停止原因，没有时为空
 */
public record ModelCallResult(String answer, String modelName, Integer inputTokens,
        Integer outputTokens, Long durationMs, String reasoning, String stopReason) {
}
