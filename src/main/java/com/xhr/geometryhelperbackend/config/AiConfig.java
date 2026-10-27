package com.xhr.geometryhelperbackend.config;

import java.util.Locale;

import com.anthropic.models.messages.OutputConfig;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
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
 * Spring AI 多引擎配置。
 *
 * <p>项目里同时存在三个互相独立的模型客户端：</p>
 * <ul>
 *     <li>{@code visionChatClient}：视觉理解，使用 {@code app.ai.vision} 配置；</li>
 *     <li>{@code solveChatClient}：正式解题，使用 {@code app.ai.solve} 配置；</li>
 *     <li>{@code chatChatClient}：普通文本对话，使用 {@code app.ai.chat} 配置。</li>
 * </ul>
 *
 * <p>三个客户端分别持有自己的 endpoint、API Key、模型和超时参数，
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
     * 解题引擎 ChatClient 的 Bean 名称。
     */
    public static final String SOLVE_CHAT_CLIENT = "solveChatClient";

    /**
     * 对话引擎 ChatClient 的 Bean 名称。
     */
    public static final String CHAT_CHAT_CLIENT = "chatChatClient";

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    private static final String ANTHROPIC_PROVIDER = "ofox";

    private static final String ANTHROPIC_MODEL_PREFIX = "anthropic";

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
     * 正式解题模型。
     *
     * @param aiProperties               业务配置
     * @param observationRegistryProvider 可选的观测注册表
     * @return 解题引擎的 ChatModel
     */
    @Bean("solveChatModel")
    public ChatModel solveChatModel(AiProperties aiProperties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        AiProperties.Solve solve = aiProperties.getSolve();
        AiProperties.Provider provider = resolveProvider(aiProperties, solve.getProvider(), "solve");
        return buildChatModel("solve", solve, provider,
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
     * 正式解题 ChatClient。
     *
     * @param solveChatModel 解题模型
     * @param aiProperties   业务配置
     * @return 带默认系统提示词的解题 ChatClient
     */
    @Bean(SOLVE_CHAT_CLIENT)
    public ChatClient solveChatClient(@Qualifier("solveChatModel") ChatModel solveChatModel,
            AiProperties aiProperties) {
        return ChatClient.builder(solveChatModel)
                .defaultSystem(aiProperties.getSolve().getSystemPrompt())
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
     * 根据引擎配置创建独立的 ChatModel。Ofox + anthropic 前缀模型走 Anthropic
     * 协议，其余情况走 OpenAI 兼容协议。
     *
     * @param engineName 引擎名称，仅用于日志和错误提示
     * @param engine     引擎参数
     * @param provider   供应商连接信息
     * @param observationRegistry 观测注册表
     * @return ChatModel
     */
    private ChatModel buildChatModel(String engineName, AiProperties.Engine engine,
            AiProperties.Provider provider, ObservationRegistry observationRegistry) {

        if (!StringUtils.hasText(engine.getModel())) {
            throw new IllegalStateException("AI 引擎[" + engineName + "]的 model 不能为空");
        }

        // 先决定协议，再校验对应 endpoint：Anthropic 引擎可以只配置
        // anthropic-base-url，OpenAI 兼容引擎则使用 base-url。
        boolean anthropicProtocol = usesAnthropicProtocol(engine);
        String baseUrl = anthropicProtocol ? resolveAnthropicBaseUrl(provider) : provider.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("AI 引擎[" + engineName + "]的 "
                    + (anthropicProtocol ? "anthropic-base-url" : "base-url") + " 不能为空");
        }

        // 只打印协议、模型和超时，不打印 API Key。
        log.info("大模型初始化 engine={} provider={} protocol={} model={} timeout={}",
                engineName, engine.getProvider(),
                anthropicProtocol ? "anthropic" : "openai-compatible",
                engine.getModel(), engine.getTimeout());

        if (anthropicProtocol) {
            AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(provider.getApiKey())
                    .model(engine.getModel())
                    .temperature(engine.getTemperature())
                    .maxTokens(engine.getMaxTokens())
                    .timeout(engine.getTimeout())
                    .maxRetries(engine.getMaxRetries());

            // 正式解题使用高强度思考：对应 Anthropic 请求中的
            // output_config.effort=high。
            if ("solve".equals(engineName)) {
                optionsBuilder.effort(OutputConfig.Effort.HIGH);
            }

            return AnthropicChatModel.builder()
                    .options(optionsBuilder.build())
                    .observationRegistry(observationRegistry)
                    .build();
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(provider.getApiKey())
                .model(engine.getModel())
                .temperature(engine.getTemperature())
                .maxTokens(engine.getMaxTokens())
                .timeout(engine.getTimeout())
                .maxRetries(engine.getMaxRetries())
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 仅当 provider 是 Ofox 且模型 ID 以 anthropic 开头时切换为 Anthropic 协议。
     */
    private boolean usesAnthropicProtocol(AiProperties.Engine engine) {
        if (!StringUtils.hasText(engine.getModel()) || !StringUtils.hasText(engine.getProvider())) {
            return false;
        }
        String provider = engine.getProvider().trim();
        return ANTHROPIC_PROVIDER.equalsIgnoreCase(provider)
                && engine.getModel().regionMatches(true, 0, ANTHROPIC_MODEL_PREFIX, 0,
                        ANTHROPIC_MODEL_PREFIX.length());
    }

    private String resolveAnthropicBaseUrl(AiProperties.Provider provider) {
        return StringUtils.hasText(provider.getAnthropicBaseUrl())
                ? provider.getAnthropicBaseUrl().trim()
                : provider.getBaseUrl();
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
