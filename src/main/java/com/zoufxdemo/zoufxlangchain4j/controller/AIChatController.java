package com.zoufxdemo.zoufxlangchain4j.controller;

import com.zoufxdemo.zoufxlangchain4j.model.ChatRequest;
import com.zoufxdemo.zoufxlangchain4j.service.ChatMemoryService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 控制器 - 使用 LangChain4J Anthropic 模块调用 MiniMax API
 * 支持会话记忆功能和 thinking 解析
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AIChatController {

    @Autowired
    @Qualifier("thinkingChatModel")
    private StreamingChatModel thinkingChatModel;

    @Autowired
    @Qualifier("nonThinkingChatModel")
    private StreamingChatModel nonThinkingChatModel;

    @Autowired
    private ChatMemoryService chatMemoryService;

    /**
     * 对话接口 - 使用 Server-Sent Events (SSE) 返回响应
     * 格式: "thinking:xxx\n\ncontent:xxx" 前端需要解析
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        String sessionId = StringUtils.hasText(request.getSessionId()) ? request.getSessionId() : "default";
        String prompt = StringUtils.hasText(request.getPrompt()) ? request.getPrompt() : "";
        log.info("Received prompt [sessionId={}]: {}", sessionId, prompt);

        // 获取历史消息
        List<String> history = chatMemoryService.getHistory(sessionId);

        // 构建完整提示词
        String fullPrompt = buildPrompt(history, prompt);

        // 根据请求选择是否启用思考模式
        StreamingChatModel model = request.isThinking() ? thinkingChatModel : nonThinkingChatModel;

        // 用于累积完整 content
        AtomicReference<String> fullContent = new AtomicReference<>("");

        return Flux.<ServerSentEvent<String>>create(sink -> model.chat(fullPrompt, new SseResponseHandler(sink, fullContent)))
                .doOnComplete(onComplete(prompt, sessionId, fullContent))
                .doOnCancel(onCancel(sessionId));
    }

    /**
     * 构建完整的提示词（包含历史对话）
     */
    private String buildPrompt(List<String> history, String prompt) {
        if (history.isEmpty()) {
            return prompt;
        }
        return String.join("\n", history) + "\n\nUser: " + prompt;
    }

    /**
     * 流完成时的处理
     */
    private Runnable onComplete(String prompt, String sessionId, AtomicReference<String> fullContent) {
        return () -> {
            // 保存用户消息
            chatMemoryService.addUserMessage(sessionId, prompt);

            // 保存助手回复
            String responseText = fullContent.get();
            if (StringUtils.hasText(responseText)) {
                chatMemoryService.addAssistantMessage(sessionId, responseText);
                log.info("Saved assistant message to session {}: {}", sessionId, responseText);
            }
            log.info("Stream completed [sessionId={}]", sessionId);
        };
    }

    /**
     * 流取消时的处理
     */
    private Runnable onCancel(String sessionId) {
        return () -> log.info("Stream cancelled [sessionId={}]", sessionId);
    }

    /**
     * SSE 响应处理器 - 处理流式响应
     */
    private static class SseResponseHandler implements StreamingChatResponseHandler {

        private final FluxSink<ServerSentEvent<String>> sink;
        private final AtomicReference<String> fullContent;

        SseResponseHandler(FluxSink<ServerSentEvent<String>> sink, AtomicReference<String> fullContent) {
            this.sink = sink;
            this.fullContent = fullContent;
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            if (partialThinking != null && partialThinking.text() != null) {
                // 直接发送 thinking 事件给前端
                ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                        .data(partialThinking.text())
                        .event("thinking")
                        .build();
                sink.next(event);
            }
        }

        @Override
        public void onPartialResponse(String partialText) {
            fullContent.updateAndGet(current -> current + partialText);
            // 直接发送 content 事件给前端
            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .data(partialText)
                    .event("content")
                    .build();
            sink.next(event);
        }

        @Override
        public void onCompleteResponse(ChatResponse fullResponse) {
            log.info("Stream completed, full content: {}", fullContent.get());
            sink.complete();
        }

        @Override
        public void onError(Throwable error) {
            log.error("Stream error: {}", error.getMessage());
            sink.error(error);
        }
    }
}
