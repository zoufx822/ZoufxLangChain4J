package com.zoufx.ai.agent.controller;

import com.zoufx.ai.agent.assistant.ChatAssistant;
import com.zoufx.ai.agent.model.ChatRequest;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * AI 控制器 —— 基于 LangChain4J AiServices + TokenStream。
 * Controller 只做 HTTP 适配：assistant 路由、TokenStream 回调 → SSE 事件翻译。
 */
@Slf4j
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:3001"})
@RestController
@RequestMapping("/ai")
public class AIChatController {

    @Autowired
    @Qualifier("thinkingAssistant")
    private ChatAssistant thinkingAssistant;

    @Autowired
    @Qualifier("nonThinkingAssistant")
    private ChatAssistant nonThinkingAssistant;

    @Autowired
    private ChatMemoryStore chatMemoryStore;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        String sessionId = StringUtils.hasText(request.getSessionId()) ? request.getSessionId() : "default";
        String prompt = StringUtils.hasText(request.getPrompt()) ? request.getPrompt().trim() : "";
        log.info("Received prompt [sessionId={}, thinking={}]: {}", sessionId, request.isThinking(), prompt);

        if (prompt.isEmpty()) {
            return Flux.just(sse("error", "prompt 不能为空"));
        }

        ChatAssistant assistant = request.isThinking() ? thinkingAssistant : nonThinkingAssistant;

        return Flux.<ServerSentEvent<String>>create(sink ->
                assistant.chat(sessionId, prompt)
                        .onPartialThinking(pt -> {
                            if (pt != null && pt.text() != null) {
                                sink.next(sse("thinking", pt.text()));
                            }
                        })
                        .onPartialResponse(ct -> sink.next(sse("content", ct)))
                        .onError(err -> {
                            log.error("Stream error [sessionId={}]", sessionId, err);
                            sink.next(sse("error", err.getMessage() != null ? err.getMessage() : "AI 服务异常，请稍后重试"));
                            sink.complete();
                        })
                        .onCompleteResponse(r -> {
                            log.info("Stream completed [sessionId={}]", sessionId);
                            sink.complete();
                        })
                        .start()
        ).doOnCancel(() -> log.info("Stream cancelled [sessionId={}]", sessionId));
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        log.info("Clearing session memory: {}", sessionId);
        chatMemoryStore.deleteMessages(sessionId);
        return Map.of("cleared", sessionId);
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
