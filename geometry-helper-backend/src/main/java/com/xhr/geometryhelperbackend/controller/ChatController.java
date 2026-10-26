package com.xhr.geometryhelperbackend.controller;

import com.xhr.geometryhelperbackend.dto.ChatRequest;
import com.xhr.geometryhelperbackend.dto.ChatResponse;
import com.xhr.geometryhelperbackend.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通文本对话接口。
 *
 * <p>与图片理解接口 {@code POST /api/solve} 使用不同的 AI 引擎，
 * 两个引擎可以同时配置、同时调用。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送文本消息并返回普通对话引擎的回复。
     *
     * <p>请求体示例（单轮）：</p>
     * <pre>
     * {
     *   "message": "你好，请介绍一下你自己"
     * }
     * </pre>
     *
     * <p>请求体示例（多轮）：</p>
     * <pre>
     * {
     *   "message": "那它的面积是多少？",
     *   "history": [
     *     { "role": "user", "content": "一个三角形的底是 6，高是 4。" },
     *     { "role": "assistant", "content": "它的面积是 12。" }
     *   ]
     * }
     * </pre>
     *
     * <p>响应示例：</p>
     * <pre>
     * {
     *   "msg": "对话成功",
     *   "answer": "你好，我是一个中文 AI 助手……"
     * }
     * </pre>
     *
     * @param request 对话请求
     * @return 对话结果
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String answer = this.chatService.chat(request.message(), request.history());
        return new ChatResponse("对话成功", answer);
    }
}
