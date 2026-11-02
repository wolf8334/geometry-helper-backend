package com.xhr.geometryhelperbackend.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流式响应所需的线程池。
 *
 * <p>模型流本身由 Spring AI 的响应式客户端驱动，但数据库写入和 SSE 输出都是
 * 阻塞操作，因此统一放到虚拟线程中执行，避免占用 Servlet 请求线程。</p>
 */
@Configuration
public class StreamingConfig {

    /**
     * 每个流式请求使用一个虚拟线程。虚拟线程在阻塞等待模型或数据库时几乎不占平台线程。
     */
    @Bean(name = "aiStreamExecutor", destroyMethod = "close")
    public ExecutorService aiStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * SSE 心跳调度器。模型思考阶段可能长时间没有正文输出，定时发送注释行可以
     * 防止代理或网关把空闲连接提前断开。
     */
    @Bean(name = "aiHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService aiHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
