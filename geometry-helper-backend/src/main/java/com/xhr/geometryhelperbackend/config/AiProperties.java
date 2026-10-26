package com.xhr.geometryhelperbackend.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 业务配置。
 *
 * <p>当前项目同时使用两个独立的引擎：</p>
 * <ul>
 *     <li>{@code app.ai.vision}：视觉理解引擎，负责 {@code POST /api/solve} 的图片理解；</li>
 *     <li>{@code app.ai.chat}：普通对话引擎，负责 {@code POST /api/chat} 的文本对话。</li>
 * </ul>
 *
 * <p>两个引擎各自选择供应商（{@code provider}）和模型（{@code model}），
 * 因此可以同时使用，例如：视觉引擎用硅基流动的 VL 模型，对话引擎用 Ofox
 * 的文本模型。供应商的 endpoint 和 API Key 统一放在
 * {@code app.ai.providers} 下，避免重复配置。</p>
 *
 * <p>配置示例：</p>
 * <pre>
 * app:
 *   ai:
 *     providers:
 *       siliconflow:
 *         base-url: https://api.siliconflow.cn/v1
 *         api-key: ${SILICONFLOW_API_KEY:}
 *       ofox:
 *         base-url: https://api.ofox.ai/v1
 *         api-key: ${OFOX_API_KEY:}
 *     vision:
 *       provider: siliconflow
 *       model: Qwen/Qwen2.5-VL-72B-Instruct
 *     chat:
 *       provider: siliconflow
 *       model: Qwen/Qwen2.5-72B-Instruct
 * </pre>
 *
 * <p>这里不使用 Spring Profile 切换供应商，因为 Profile 是全局开关，
 * 无法表达“视觉用 A 供应商、对话用 B 供应商”这种组合。通过
 * {@code provider} 字段按引擎选择供应商，才能真正做到两个引擎同时工作。</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /**
     * 所有可用的 OpenAI 兼容供应商。
     *
     * <p>key 是业务侧使用的供应商标识，例如 {@code siliconflow}、{@code ofox}；
     * value 是该供应商的 endpoint 和 API Key。</p>
     */
    private final Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * 视觉理解引擎配置。
     */
    private final Vision vision = new Vision();

    /**
     * 普通对话引擎配置。
     */
    private final Chat chat = new Chat();

    public Map<String, Provider> getProviders() {
        return this.providers;
    }

    public Vision getVision() {
        return this.vision;
    }

    public Chat getChat() {
        return this.chat;
    }

    /**
     * 单个供应商的连接配置。
     */
    public static class Provider {

        /**
         * OpenAI 兼容 endpoint，通常需要带 {@code /v1}。
         */
        private String baseUrl;

        /**
         * API Key。允许为空，方便本地只验证健康检查或使用无鉴权的内网模型。
         */
        private String apiKey = "";

        public String getBaseUrl() {
            return this.baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return this.apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * 两个引擎共用的模型参数。
     */
    public static class Engine {

        /**
         * 供应商标识，必须能在 {@link AiProperties#getProviders()} 中找到。
         */
        private String provider = "siliconflow";

        /**
         * 模型 ID。不同供应商的命名方式不同，例如硅基流动的
         * {@code Qwen/Qwen2.5-VL-72B-Instruct}、Ofox 的
         * {@code openai/gpt-6.1-sol}。
         */
        private String model;

        /**
         * 采样温度。
         */
        private double temperature = 0.2;

        /**
         * 最大输出 token 数。
         */
        private int maxTokens = 1024;

        /**
         * 单次请求超时时间。视觉请求通常比纯文本请求慢，可以单独设置。
         */
        private Duration timeout = Duration.ofSeconds(120);

        /**
         * SDK 层重试次数。
         */
        private int maxRetries = 2;

        /**
         * 系统提示词，用于约束模型角色和回答风格。
         */
        private String systemPrompt;

        public String getProvider() {
            return this.provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return this.model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return this.temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return this.maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getTimeout() {
            return this.timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return this.maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public String getSystemPrompt() {
            return this.systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

    /**
     * 视觉理解引擎配置。
     *
     * <p>该引擎必须选择支持图片输入（Vision）的模型。</p>
     */
    public static class Vision extends Engine {

        /**
         * 与图片一起发送给多模态模型的用户提示词。
         */
        private String userPrompt;

        public Vision() {
            // 构造函数中设置默认值，YAML 中存在对应配置时会被覆盖。
            setSystemPrompt("""
                    你是一名专业、严谨的图片内容描述助手。
                    请始终使用简体中文回答，客观描述图片中能够确认的信息；
                    看不清或无法判断的内容不要编造。
                    """);
            setUserPrompt("""
                    请仔细观察这张图片，并用简体中文详细描述图片内容，包括：
                    1. 图片的主体对象和场景；
                    2. 图中可见的文字、公式或标注（如有）；
                    3. 与几何图形相关的形状、线段、角度、坐标等信息（如有）。
                    只输出描述内容，不要输出多余的前后缀。
                    """);
        }

        public String getUserPrompt() {
            return this.userPrompt;
        }

        public void setUserPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
        }
    }

    /**
     * 普通对话引擎配置。
     *
     * <p>该引擎只处理文本，不要求模型支持图片输入，因此可以使用成本更低的
     * 纯文本模型。支持携带最近若干轮历史消息，实现多轮对话。</p>
     */
    public static class Chat extends Engine {

        /**
         * 最多携带多少条历史消息。过长的历史会显著增加 token 成本，
         * 默认只保留最近 20 条。
         */
        private int maxHistoryMessages = 20;

        public Chat() {
            // 对话引擎的默认参数与视觉引擎不同：温度更高、输出更长、超时更短。
            setTemperature(0.7);
            setMaxTokens(2048);
            setTimeout(Duration.ofSeconds(60));
            setSystemPrompt("""
                    你是一名专业、友好的中文 AI 助手。
                    请使用简体中文回答，表达清晰、准确；
                    如果用户的问题涉及你不确定的信息，请明确说明，不要编造。
                    """);
        }

        public int getMaxHistoryMessages() {
            return this.maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }
    }
}
