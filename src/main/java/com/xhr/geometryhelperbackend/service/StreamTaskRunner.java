package com.xhr.geometryhelperbackend.service;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 在后台线程执行流式业务，并把返回的 {@link SseEmitter} 交给 Spring MVC。
 *
 * <p>统一处理任务完成和异常，控制器只需要关心“发送 meta、模型增量、落库、
 * 发送 commit”这条主流程。</p>
 */
@Component
public class StreamTaskRunner {

    private final ExecutorService streamExecutor;

    private final SseStreamFactory streamFactory;

    public StreamTaskRunner(
            @Qualifier("aiStreamExecutor") ExecutorService streamExecutor,
            SseStreamFactory streamFactory) {
        this.streamExecutor = streamExecutor;
        this.streamFactory = streamFactory;
    }

    /**
     * 启动一次流式任务。
     *
     * @param work 业务逻辑
     * @return SSE 响应
     */
    public SseEmitter run(Consumer<SseStreamSession> work) {
        SseStreamSession session = this.streamFactory.create();
        this.streamExecutor.execute(() -> {
            try {
                work.accept(session);
                if (!session.isClosed()) {
                    session.finish();
                }
            } catch (Throwable error) {
                session.fail(error);
            }
        });
        return session.emitter();
    }
}
