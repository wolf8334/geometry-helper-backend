package com.xhr.geometryhelperbackend.controller;

import java.util.Map;

import com.xhr.geometryhelperbackend.dto.ChatRequest;
import com.xhr.geometryhelperbackend.dto.ChatResponse;
import com.xhr.geometryhelperbackend.service.ChatService;
import com.xhr.geometryhelperbackend.service.ModelCallResult;
import com.xhr.geometryhelperbackend.service.StreamTaskRunner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 普通文本对话接口。
 *
 * <p>与图片识别接口 {@code POST /api/solve} 使用的视觉引擎、正式解题引擎
 * 相互独立，三个引擎可以同时配置、同时调用。</p>
 *
 * <p>响应使用 SSE：正文通过 {@code delta} 事件实时返回，最终结果通过
 * {@code commit} 事件返回。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    private final StreamTaskRunner streamTaskRunner;

    public ChatController(ChatService chatService, StreamTaskRunner streamTaskRunner) {
        this.chatService = chatService;
        this.streamTaskRunner = streamTaskRunner;
    }

    /**
     * 发送文本消息并流式返回普通对话引擎的回复。
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
     * @param request 对话请求
     * @return SSE 流
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        return this.streamTaskRunner.run(session -> {
            session.sendMeta(Map.of("stage", "chat"));
            ModelCallResult result = this.chatService.chatStream(
                    request.message(), request.history(), session.deltaSink());
            session.sendCommit(new ChatResponse("对话成功", result.answer()));
        });
    }
}
