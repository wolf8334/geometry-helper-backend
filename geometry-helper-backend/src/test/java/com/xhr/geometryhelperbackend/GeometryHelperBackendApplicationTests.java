package com.xhr.geometryhelperbackend;

import com.xhr.geometryhelperbackend.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@SpringBootTest(properties = {
        "app.ai.vision.model=vision-test",
        "app.ai.chat.model=chat-test"
})
class GeometryHelperBackendApplicationTests {

    @Autowired
    @Qualifier(AiConfig.VISION_CHAT_CLIENT)
    private ChatClient visionChatClient;

    @Autowired
    @Qualifier(AiConfig.CHAT_CHAT_CLIENT)
    private ChatClient chatChatClient;

    @Test
    void contextLoads() {
    }

    /**
     * 两个引擎必须同时存在，并且是两个独立的 ChatClient 实例。
     */
    @Test
    void visionAndChatClientsAreIndependent() {
        assertNotNull(visionChatClient);
        assertNotNull(chatChatClient);
        assertNotSame(visionChatClient, chatChatClient);
    }
}
