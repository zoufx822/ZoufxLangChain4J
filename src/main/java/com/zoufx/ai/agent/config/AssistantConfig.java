package com.zoufx.ai.agent.config;

import com.zoufx.ai.agent.assistant.ChatAssistant;
import com.zoufx.ai.agent.tool.TavilySearchTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配两个 ChatAssistant Bean：分别绑定 thinking / nonThinking 的 StreamingChatModel。
 * 共享同一个 ChatMemoryProvider——同 sessionId 跨模式切换历史连续。
 * 两者均挂载 TavilySearchTool 提供网络检索能力。
 */
@Configuration
public class AssistantConfig {

    @Bean
    public ChatAssistant thinkingAssistant(
            @Qualifier("thinkingChatModel") StreamingChatModel model,
            ChatMemoryProvider chatMemoryProvider,
            TavilySearchTool tavilySearchTool) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tavilySearchTool)
                .build();
    }

    @Bean
    public ChatAssistant nonThinkingAssistant(
            @Qualifier("nonThinkingChatModel") StreamingChatModel model,
            ChatMemoryProvider chatMemoryProvider,
            TavilySearchTool tavilySearchTool) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tavilySearchTool)
                .build();
    }
}
