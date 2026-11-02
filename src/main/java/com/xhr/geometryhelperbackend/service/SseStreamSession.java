package com.xhr.geometryhelperbackend.service;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 一次 SSE 流式响应。
 *
 * <p>负责发送事件、序列化 JSON、心跳和关闭连接。所有 {@code send} 操作都加了锁，
 * 避免模型正文和心跳定时任务同时写入造成 SSE 帧交错。</p>
 *
 * <p>事件约定：</p>
 * <ul>
 *     <li>{@code delta}：正文增量，{@code {"text":"..."}}；</li>
 *     <li>{@code meta}：不落库的阶段性信息；</li>
 *     <li>{@code commit}：数据已经成功入库，携带可替换页面内容的最终结果；</li>
 *     <li>{@code error}：请求失败，{@code {"message":"...","retryable":true}}；</li>
 *     <li>{@code done}：流正常结束。</li>
 * </ul>
 */
public final class SseStreamSession {

    private static final Logger log = LoggerFactory.getLogger(SseStreamSession.class);

    /**
     * 单条 SSE 连接的最长存活时间。正常模型请求远小于这个时长。
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    /**
     * 心跳间隔。模型思考阶段可能很久没有正文，注释帧可以防止代理断开连接。
     */
    private static final long HEARTBEAT_SECONDS = 10;

    private final SseEmitter emitter;

    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeatScheduler;

    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile ScheduledFuture<?> heartbeatTask;

    public SseStreamSession(ObjectMapper objectMapper,
            ScheduledExecutorService heartbeatScheduler) {
        this.objectMapper = objectMapper;
        this.heartbeatScheduler = heartbeatScheduler;
        this.emitter = new SseEmitter(TIMEOUT.toMillis());
        this.emitter.onCompletion(this::closeQuietly);
        this.emitter.onTimeout(this::completeQuietly);
        this.emitter.onError(error -> closeQuietly());
        startHeartbeat();
    }

    /**
     * 交给 Spring MVC 的响应对象。
     *
     * @return SSE emitter
     */
    public SseEmitter emitter() {
        return this.emitter;
    }

    /**
     * 当前连接是否已经断开。
     *
     * @return 已关闭时为 {@code true}
     */
    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * 发送正文增量。连接已经断开时抛出 {@link StreamClosedException}，
     * 让模型流立即取消，不继续消耗额度。
     *
     * @param text 正文增量
     */
    public void sendDelta(String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        if (!send("delta", Map.of("text", text))) {
            throw new StreamClosedException();
        }
    }

    /**
     * [Claude 添加] 正文回调。同时实现 {@link ThinkingListener}，模型思考期间会以
     * {@code thinking} 事件把思考内容推给页面；思考增量很碎，这里按时间合并后再发送。
     *
     * @return 可传给服务层的正文回调
     */
    public Consumer<String> deltaSink() {
        return new DeltaSink();
    }

    private final class DeltaSink implements Consumer<String>, ThinkingListener {

        private static final long FLUSH_INTERVAL_NANOS = 200_000_000L;

        private final StringBuilder pending = new StringBuilder();

        private long lastFlush = System.nanoTime();

        @Override
        public void accept(String text) {
            sendDelta(text);
        }

        @Override
        public void onThinking(String text) {
            if (!StringUtils.hasLength(text)) {
                return;
            }
            this.pending.append(text);
            long now = System.nanoTime();
            if (now - this.lastFlush < FLUSH_INTERVAL_NANOS) {
                return;
            }
            this.lastFlush = now;
            String merged = this.pending.toString();
            this.pending.setLength(0);
            requireOpen(send("thinking", Map.of("text", merged)));
        }
    }

    /**
     * 发送阶段信息。
     *
     * @param payload 任意可序列化对象
     */
    public void sendMeta(Object payload) {
        requireOpen(send("meta", payload));
    }

    /**
     * 发送已经入库的最终结果。
     *
     * @param payload 任意可序列化对象
     */
    public void sendCommit(Object payload) {
        requireOpen(send("commit", payload));
    }

    /**
     * 发送结束事件并关闭连接。
     */
    public void finish() {
        if (!this.closed.get()) {
            send("done", Map.of());
        }
        completeQuietly();
    }

    /**
     * 把异常转换成 SSE 错误事件并关闭连接。
     *
     * <p>参数或资源不存在属于不可重试错误；模型调用和未知异常交给前端自动重试。</p>
     *
     * @param error 异常
     */
    public void fail(Throwable error) {
        if (error instanceof StreamClosedException) {
            completeQuietly();
            return;
        }

        String message;
        boolean retryable;
        if (error instanceof IllegalArgumentException) {
            message = fallbackMessage(error.getMessage(), "请求参数有误，请检查后重试");
            retryable = false;
        } else if (error instanceof NoSuchElementException) {
            message = fallbackMessage(error.getMessage(), "未找到对应记录");
            retryable = false;
        } else if (error instanceof AiServiceException) {
            message = fallbackMessage(error.getMessage(), "大模型服务暂时不可用，请稍后重试");
            retryable = true;
            log.error("流式请求失败 {}", message, error);
        } else {
            message = "服务器内部错误，请稍后重试";
            retryable = true;
            log.error("流式请求出现未处理异常", error);
        }

        send("error", Map.of("message", message, "retryable", retryable));
        completeQuietly();
    }

    /**
     * 发送一帧事件。序列化或写入失败时视为连接已关闭。
     */
    private boolean send(String event, Object payload) {
        if (this.closed.get()) {
            return false;
        }

        String json;
        try {
            json = this.objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            log.warn("SSE 事件序列化失败 event={}", event, exception);
            return false;
        }

        synchronized (this.emitter) {
            if (this.closed.get()) {
                return false;
            }
            try {
                this.emitter.send(SseEmitter.event().name(event).data(json));
                return true;
            } catch (Exception exception) {
                log.debug("SSE 写入失败 event={} 浏览器可能已断开", event);
                closeQuietly();
                return false;
            }
        }
    }

    private void requireOpen(boolean sent) {
        if (!sent) {
            throw new StreamClosedException();
        }
    }

    private void startHeartbeat() {
        this.heartbeatTask = this.heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (this.closed.get()) {
                return;
            }
            synchronized (this.emitter) {
                if (this.closed.get()) {
                    return;
                }
                try {
                    this.emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception exception) {
                    closeQuietly();
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void completeQuietly() {
        this.closed.set(true);
        cancelHeartbeat();
        try {
            this.emitter.complete();
        } catch (Exception exception) {
            log.debug("SSE 关闭时出现异常，通常表示浏览器已经断开");
        }
    }

    private void closeQuietly() {
        if (this.closed.getAndSet(true)) {
            return;
        }
        cancelHeartbeat();
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = this.heartbeatTask;
        if (task != null) {
            task.cancel(false);
            this.heartbeatTask = null;
        }
    }

    private String fallbackMessage(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }
}
