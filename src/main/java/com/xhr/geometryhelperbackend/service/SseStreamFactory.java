package com.xhr.geometryhelperbackend.service;

import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 创建 SSE 流会话。
 *
 * <p>把 {@link ObjectMapper} 和心跳线程池集中注入，控制器只负责组织业务逻辑。</p>
 */
@Component
public class SseStreamFactory {

    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeatScheduler;

    public SseStreamFactory(ObjectMapper objectMapper,
            @Qualifier("aiHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.objectMapper = objectMapper;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * 新建一次流式响应。
     *
     * @return SSE 会话
     */
    public SseStreamSession create() {
        return new SseStreamSession(this.objectMapper, this.heartbeatScheduler);
    }
}
