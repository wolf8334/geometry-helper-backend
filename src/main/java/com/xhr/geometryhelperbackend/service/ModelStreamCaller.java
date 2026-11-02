package com.xhr.geometryhelperbackend.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 消费 Spring AI 的流式响应。
 *
 * <p>只把真正的正文增量传给前端：Anthropic 的 thinking 增量正文为空，
 * OpenAI 兼容协议的推理内容放在 {@code reasoningContent} 元数据里，
 * 还有一些模型用 {@code isThought/thinking} 标记思考片段。这些内容都不会
 * 出现在页面上，但正文仍会完整累积，等模型结束后再统一入库。</p>
 */
@Service
public class ModelStreamCaller {

    private static final Logger log = LoggerFactory.getLogger(ModelStreamCaller.class);

    private static final String METADATA_REASONING_CONTENT = "reasoningContent";

    private static final String METADATA_THOUGHT = "isThought";

    private static final String METADATA_THINKING = "thinking";

    /**
     * 订阅模型流并收集完整回答。
     *
     * @param responses       模型流
     * @param onDelta         每个正文增量回调
     * @param operation       日志中的阶段名称
     * @param configuredModel 配置中的模型名称
     * @return 完整回答及调用元数据
     */
    public ModelCallResult collect(Flux<ChatResponse> responses, Consumer<String> onDelta,
            String operation, String configuredModel) {
        long startNanos = System.nanoTime();
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicReference<String> modelName = new AtomicReference<>(configuredModel);
        AtomicReference<String> stopReason = new AtomicReference<>();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        // [Claude 添加] 诊断流式是否逐段到达：统计事件数、正文增量数和首个正文增量耗时。
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger deltaCount = new AtomicInteger();
        AtomicLong firstEventMillis = new AtomicLong(-1);
        AtomicLong firstDeltaMillis = new AtomicLong(-1);
        AtomicLong lastDeltaMillis = new AtomicLong(-1);

        try {
            if (responses == null) {
                throw new AiServiceException("大模型未返回有效内容，请稍后重试");
            }

            responses.doOnNext(response -> {
                // [Claude 添加] 记录每个上游事件到达的相对时间。
                long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
                eventCount.incrementAndGet();
                firstEventMillis.compareAndSet(-1, elapsed);
                int before = answer.length();
                accumulate(response, answer, reasoning,
                        modelName, inputTokens, outputTokens, stopReason, onDelta);
                if (answer.length() > before) {
                    deltaCount.incrementAndGet();
                    firstDeltaMillis.compareAndSet(-1, elapsed);
                    lastDeltaMillis.set(elapsed);
                }
            }).blockLast();

            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            if (!StringUtils.hasText(answer)) {
                log.warn("{} 流式响应没有有效正文 耗时={}s 输出Token={}",
                        operation, costMillis / 1000, outputTokens.get());
                throw new AiServiceException("大模型未返回有效内容，请稍后重试");
            }

            // [Claude 添加] 首个正文增量到最后一个正文增量的间隔很短、增量数很少，
            // 说明上游（网关或思考阶段）没有逐段输出，而不是本服务缓冲。
            log.info("{} 流式诊断 上游事件数={} 正文增量数={} 首个事件={}ms 首个正文={}ms 最后正文={}ms 总耗时={}ms",
                    operation, eventCount.get(), deltaCount.get(), firstEventMillis.get(),
                    firstDeltaMillis.get(), lastDeltaMillis.get(), costMillis);

            log.info("{} 流式调用完成 耗时={}s 输入Token={} 输出Token={}",
                    operation, costMillis / 1000,
                    inputTokens.get() == 0 ? null : inputTokens.get(),
                    outputTokens.get() == 0 ? null : outputTokens.get());

            return new ModelCallResult(answer.toString().trim(), modelName.get(),
                    inputTokens.get() == 0 ? null : inputTokens.get(),
                    outputTokens.get() == 0 ? null : outputTokens.get(),
                    costMillis,
                    StringUtils.hasText(reasoning) ? reasoning.toString().trim() : null,
                    stopReason.get());
        } catch (AiServiceException | StreamClosedException exception) {
            throw exception;
        } catch (Exception exception) {
            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("{} 流式调用失败 耗时={}s", operation, costMillis / 1000, exception);
            throw new AiServiceException("大模型服务调用失败，请检查 API Key、模型名称或网络后重试",
                    exception);
        }
    }

    private void accumulate(ChatResponse response, StringBuilder answer, StringBuilder reasoning,
            AtomicReference<String> modelName, AtomicInteger inputTokens,
            AtomicInteger outputTokens, AtomicReference<String> stopReason,
            Consumer<String> onDelta) {
        if (response == null) {
            return;
        }

        Generation generation = response.getResult();
        if (generation != null) {
            AssistantMessage output = generation.getOutput();
            Map<String, Object> metadata = output.getMetadata();
            boolean thought = Boolean.TRUE.equals(metadata.get(METADATA_THOUGHT)) || Boolean.TRUE.equals(metadata.get(METADATA_THINKING));
            String chunk = output.getText();
            if (!thought && StringUtils.hasLength(chunk)) {
                answer.append(chunk);
                onDelta.accept(chunk);
            } else if (thought && StringUtils.hasLength(chunk)
                    && onDelta instanceof ThinkingListener listener) {
                // [Claude 添加] 标记为思考的片段也交给页面显示进度。
                listener.onThinking(chunk);
            }

            Object reasoningContent = metadata.get(METADATA_REASONING_CONTENT);
            if (reasoningContent instanceof String reasoningText
                    && StringUtils.hasText(reasoningText)) {
                // [Claude 修改] 推理内容按增量到达，应追加而不是覆盖，
                // 否则入库的只剩最后一块。
                reasoning.append(reasoningText);
                if (onDelta instanceof ThinkingListener listener) {
                    listener.onThinking(reasoningText);
                }
            }

            if (StringUtils.hasText(generation.getMetadata().getFinishReason())) {
                stopReason.set(generation.getMetadata().getFinishReason());
            }
        }

        ChatResponseMetadata metadata = response.getMetadata();
        if (StringUtils.hasText(metadata.getModel())) {
            modelName.set(metadata.getModel());
        }

        Usage usage = metadata.getUsage();
        if (usage.getPromptTokens() > 0) {
            inputTokens.set(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() > 0) {
            outputTokens.set(usage.getCompletionTokens());
        }
    }
}
