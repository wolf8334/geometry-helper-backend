package com.xhr.geometryhelperbackend;

import com.xhr.geometryhelperbackend.config.AiConfig;
import com.xhr.geometryhelperbackend.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@SpringBootTest(properties = {
        "app.ai.vision.model=vision-test",
        "app.ai.solve.provider=ofox",
        "app.ai.solve.model=anthropic/claude-test",
        "app.ai.chat.model=chat-test"
})
class GeometryHelperBackendApplicationTests {

    @Autowired
    @Qualifier(AiConfig.VISION_CHAT_CLIENT)
    private ChatClient visionChatClient;

    @Autowired
    @Qualifier("solveChatModel")
    private ChatModel solveChatModel;

    @Autowired
    @Qualifier(AiConfig.SOLVE_CHAT_CLIENT)
    private ChatClient solveChatClient;

    @Autowired
    @Qualifier(AiConfig.CHAT_CHAT_CLIENT)
    private ChatClient chatChatClient;

    @Autowired
    private AiProperties aiProperties;

    @Test
    void contextLoads() {
    }

    /**
     * 解题引擎默认只允许使用国内初二数学知识，后续可通过配置覆盖。
     */
    @Test
    void solveKnowledgeScopeDefaultsToJuniorGradeTwo() {
        assertEquals("国内初二数学知识", this.aiProperties.getSolve().getKnowledgeScope());
    }

    /**
     * 三个引擎必须同时存在，并且是三个独立的 ChatClient 实例。
     */
    @Test
    void anthropicSolveModelAndClientsAreIndependent() {
        assertInstanceOf(AnthropicChatModel.class, solveChatModel);
        assertNotNull(visionChatClient);
        assertNotNull(solveChatClient);
        assertNotNull(chatChatClient);
        assertNotSame(visionChatClient, solveChatClient);
        assertNotSame(visionChatClient, chatChatClient);
        assertNotSame(solveChatClient, chatChatClient);
    }
}
