package com.xhr.geometryhelperbackend.service;

/**
 * 浏览器已经断开 SSE 连接。
 *
 * <p>模型流在收到这个异常后会立刻取消，避免继续消耗模型额度，也不会把没有
 * 完整输出的回答写进数据库。</p>
 */
public class StreamClosedException extends RuntimeException {

    public StreamClosedException() {
        super("SSE 连接已关闭", null, false, false);
    }
}
