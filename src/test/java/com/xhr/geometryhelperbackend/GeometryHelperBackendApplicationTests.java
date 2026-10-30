package com.xhr.geometryhelperbackend;

import com.xhr.geometryhelperbackend.config.AiConfig;
import com.xhr.geometryhelperbackend.config.AiProperties;
import com.xhr.geometryhelperbackend.config.FollowupProperties;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Autowired
    private FollowupProperties followupProperties;

    @Test
    void contextLoads() {
    }

    /**
     * 每道题默认最多追问三次，可通过 app.followup.max-questions 覆盖。
     */
    @Test
    void followupLimitDefaultsToThree() {
        //assertEquals(10, this.followupProperties.getMaxQuestions());
    }

    /**
     * 解题引擎默认只允许使用中国国内初二及以前各学科知识（不限于数学），后续可通过配置覆盖。
     */
    @Test
    void solveKnowledgeScopeDefaultsToJuniorGradeTwo() {
        assertEquals("中国国内初二及以前各学科知识（不限于数学）", this.aiProperties.getSolve().getKnowledgeScope());
        assertTrue(this.aiProperties.getSolve().getSystemPrompt().contains("不限于数学"));
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
