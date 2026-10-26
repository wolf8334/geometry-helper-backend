package com.xhr.geometryhelperbackend.exception;

/**
 * AI 调用失败时抛出的业务异常。
 *
 * <p>使用独立的异常类型，便于全局异常处理器把“模型服务不可用”与其他
 * 程序错误区分开，向调用方返回 502（Bad Gateway）而不是 500。</p>
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
