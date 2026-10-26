package com.xhr.geometryhelperbackend.config;

import java.util.Locale;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Spring AI 双引擎配置。
 *
 * <p>项目里同时存在两个互相独立的 OpenAI 兼容客户端：</p>
 * <ul>
 *     <li>{@code visionChatClient}：视觉理解，使用 {@code app.ai.vision} 配置；</li>
 *     <li>{@code chatChatClient}：普通文本对话，使用 {@code app.ai.chat} 配置。</li>
 * </ul>
 *
 * <p>两个客户端分别持有自己的 endpoint、API Key、模型和超时参数，
 * 因此可以同时使用不同供应商或不同模型。它们不会因为 Spring Profile
 * 只有一个激活值而互相影响。</p>
 *
 * <p>注意：{@code application.yaml} 中关闭了 Spring AI 的单模型自动配置
 * （{@code spring.ai.model.chat=none}）和自动 ChatClient.Builder
 * （{@code spring.ai.chat.client.enabled=false}），避免自动配置在存在多个
 * {@link ChatModel} Bean 时产生歧义。这里手动创建 Bean，控制更明确。</p>
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /**
     * 视觉引擎 ChatClient 的 Bean 名称。
     */
    public static final String VISION_CHAT_CLIENT = "visionChatClient";

    /**
     * 对话引擎 ChatClient 的 Bean 名称。
     */
    public static final String CHAT_CHAT_CLIENT = "chatChatClient";

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * 视觉理解模型。
     *
     * @param aiProperties               业务配置
     * @param observationRegistryProvider 可选的观测注册表
     * @return 视觉引擎的 ChatModel
     */
    @Bean("visionChatModel")
    public ChatModel visionChatModel(AiProperties aiProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        AiProperties.Vision vision = aiProperties.getVision();
        AiProperties.Provider provider = resolveProvider(aiProperties, vision.getProvider(), "vision");
        return buildChatModel("vision", vision, provider,
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * 普通对话模型。
     *
     * @param aiProperties               业务配置
     * @param observationRegistryProvider 可选的观测注册表
     * @return 对话引擎的 ChatModel
     */
    @Bean("chatChatModel")
    public ChatModel chatChatModel(AiProperties aiProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        AiProperties.Chat chat = aiProperties.getChat();
        AiProperties.Provider provider = resolveProvider(aiProperties, chat.getProvider(), "chat");
        return buildChatModel("chat", chat, provider,
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * 视觉理解 ChatClient。
     *
     * @param visionChatModel 视觉模型
     * @param aiProperties    业务配置
     * @return 带默认系统提示词的视觉 ChatClient
     */
    @Bean(VISION_CHAT_CLIENT)
    public ChatClient visionChatClient(@Qualifier("visionChatModel") ChatModel visionChatModel,
            AiProperties aiProperties) {
        return ChatClient.builder(visionChatModel)
                .defaultSystem(aiProperties.getVision().getSystemPrompt())
                .build();
    }

    /**
     * 普通对话 ChatClient。
     *
     * @param chatChatModel 对话模型
     * @param aiProperties  业务配置
     * @return 带默认系统提示词的对话 ChatClient
     */
    @Bean(CHAT_CHAT_CLIENT)
    public ChatClient chatChatClient(@Qualifier("chatChatModel") ChatModel chatChatModel,
            AiProperties aiProperties) {
        return ChatClient.builder(chatChatModel)
                .defaultSystem(aiProperties.getChat().getSystemPrompt())
                .build();
    }

    /**
     * 根据引擎配置创建独立的 OpenAI 兼容 ChatModel。
     *
     * @param engineName 引擎名称，仅用于日志和错误提示
     * @param engine     引擎参数
     * @param provider   供应商连接信息
     * @param observationRegistry 观测注册表
     * @return ChatModel
     */
    private ChatModel buildChatModel(String engineName, AiProperties.Engine engine,
            AiProperties.Provider provider, ObservationRegistry observationRegistry) {

        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalStateException("AI 引擎[" + engineName + "]的供应商 base-url 不能为空");
        }
        if (!StringUtils.hasText(engine.getModel())) {
            throw new IllegalStateException("AI 引擎[" + engineName + "]的 model 不能为空");
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .model(engine.getModel())
                .temperature(engine.getTemperature())
                .maxTokens(engine.getMaxTokens())
                .timeout(engine.getTimeout())
                .maxRetries(engine.getMaxRetries())
                .build();

        // 只打印 endpoint 和模型，不打印 API Key。
        log.info("ai init engine={} provider={} baseUrl={} model={} timeout={} maxRetries={}",
                engineName, engine.getProvider(), provider.getBaseUrl(), engine.getModel(),
                engine.getTimeout(), engine.getMaxRetries());

        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 根据供应商标识查找连接配置。
     *
     * <p>优先精确匹配；找不到时再按小写匹配，尽量避免配置中大小写不一致导致启动失败。</p>
     *
     * @param aiProperties 业务配置
     * @param providerName 供应商标识
     * @param engineName   引擎名称，用于错误提示
     * @return 供应商配置
     */
    private AiProperties.Provider resolveProvider(AiProperties aiProperties, String providerName,
            String engineName) {
        String configuredName = StringUtils.hasText(providerName) ? providerName.trim() : "siliconflow";
        AiProperties.Provider provider = aiProperties.getProviders().get(configuredName);
        if (provider == null) {
            provider = aiProperties.getProviders().get(configuredName.toLowerCase(Locale.ROOT));
        }
        if (provider == null) {
            throw new IllegalStateException("AI 引擎[" + engineName + "]配置的供应商不存在："
                    + configuredName + "，可用供应商：" + aiProperties.getProviders().keySet());
        }
        return provider;
    }
}
