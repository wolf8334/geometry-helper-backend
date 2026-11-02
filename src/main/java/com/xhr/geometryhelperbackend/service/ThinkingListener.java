package com.xhr.geometryhelperbackend.service;

/**
 * [Claude 添加] 接收模型的思考（推理）增量。
 *
 * <p>流式回调如果同时实现了这个接口，{@link ModelStreamCaller} 会把思考内容也交给它，
 * 页面就能在模型思考期间显示进度，而不是一片空白。</p>
 */
public interface ThinkingListener {

    /**
     * 收到一段思考增量。
     *
     * @param text 思考文本
     */
    void onThinking(String text);
}
