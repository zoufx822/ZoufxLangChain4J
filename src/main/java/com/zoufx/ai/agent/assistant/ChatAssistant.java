package com.zoufx.ai.agent.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4J AiService 接口。
 * 由 AiServices.builder(...) 动态代理实现，自动接管会话记忆、流式、工具调用等。
 * 系统提示由 AssistantConfig#systemMessageProvider 在运行时动态生成（注入当前日期）。
 */
public interface ChatAssistant {
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
