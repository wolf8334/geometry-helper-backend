package com.xhr.geometryhelperbackend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.xhr.geometryhelperbackend.config.AiConfig;
import com.xhr.geometryhelperbackend.config.AiProperties;
import com.xhr.geometryhelperbackend.dto.ChatMessage;
import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 普通文本对话服务。
 *
 * <p>该服务只使用 {@code chatChatClient}，与图片识别使用的
 * {@code visionChatClient}、正式解题使用的 {@code solveChatClient} 完全独立。
 * 三个引擎可以分别配置不同的供应商、API Key、模型和超时时间。</p>
 *
 * <p>支持两种调用方式：</p>
 * <ul>
 *     <li>单轮对话：只传 {@code message}；</li>
 *     <li>多轮对话：额外传 {@code history}，服务端会把历史消息按顺序拼接。</li>
 * </ul>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String ROLE_USER = "user";

    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatClient chatClient;

    private final AiProperties aiProperties;

    public ChatService(@Qualifier(AiConfig.CHAT_CHAT_CLIENT) ChatClient chatClient,
            AiProperties aiProperties) {
        this.chatClient = chatClient;
        this.aiProperties = aiProperties;
    }

    /**
     * 调用普通对话引擎。
     *
     * @param message 当前这一轮的用户消息
     * @param history 历史消息，可为 {@code null} 或空列表
     * @return 模型返回的文本
     * @throws IllegalArgumentException 消息为空、历史角色不合法等参数问题
     * @throws AiServiceException      模型调用失败
     */
    public String chat(String message, List<ChatMessage> history) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        List<Message> messages = buildHistoryMessages(history);
        messages.add(new UserMessage(message.trim()));

        long startNanos = System.nanoTime();
        try {
            // 系统提示词已经在 AiConfig 的 ChatClient 中设置为默认值。
            // 这里传入历史消息和当前用户消息，ChatClient 会自动把系统提示词放在最前面。
            String answer = this.chatClient.prompt()
                    .messages(messages)
                    .call()
                    .content();

            if (!StringUtils.hasText(answer)) {
                throw new AiServiceException("对话引擎未返回有效内容，请稍后重试");
            }

            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("chat ok provider={} model={} history={} cost={}ms",
                    this.aiProperties.getChat().getProvider(),
                    this.aiProperties.getChat().getModel(),
                    messages.size() - 1,
                    costMillis);
            return answer.trim();
        } catch (AiServiceException exception) {
            // 保留业务异常，避免被下面的通用异常再次包装。
            throw exception;
        } catch (Exception exception) {
            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("chat failed provider={} model={} history={} cost={}ms",
                    this.aiProperties.getChat().getProvider(),
                    this.aiProperties.getChat().getModel(),
                    messages.size() - 1,
                    costMillis,
                    exception);
            throw new AiServiceException("对话服务调用失败，请检查 API Key、模型名称或网络后重试", exception);
        }
    }

    /**
     * 把 DTO 中的历史消息转换成 Spring AI 的 {@link Message}。
     *
     * <p>为了避免一次请求携带过多 token，只保留最近
     * {@code app.ai.chat.max-history-messages} 条历史消息。</p>
     *
     * @param history 历史消息
     * @return Spring AI 消息列表
     */
    private List<Message> buildHistoryMessages(List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        if (history == null || history.isEmpty()) {
            return messages;
        }

        int maxHistoryMessages = Math.max(0, this.aiProperties.getChat().getMaxHistoryMessages());
        if (maxHistoryMessages == 0) {
            return messages;
        }

        int startIndex = Math.max(0, history.size() - maxHistoryMessages);
        if (startIndex > 0) {
            log.debug("chat history truncated total={} kept={}", history.size(), maxHistoryMessages);
        }

        for (int index = startIndex; index < history.size(); index++) {
            ChatMessage item = history.get(index);
            if (item == null) {
                throw new IllegalArgumentException("history 中不能包含空消息");
            }
            if (!StringUtils.hasText(item.content())) {
                throw new IllegalArgumentException("history.content 不能为空");
            }

            String role = item.role() == null ? "" : item.role().trim().toLowerCase(Locale.ROOT);
            if (ROLE_USER.equals(role)) {
                messages.add(new UserMessage(item.content().trim()));
            } else if (ROLE_ASSISTANT.equals(role)) {
                messages.add(new AssistantMessage(item.content().trim()));
            } else {
                throw new IllegalArgumentException("history.role 仅支持 user 或 assistant");
            }
        }

        return messages;
    }
}
