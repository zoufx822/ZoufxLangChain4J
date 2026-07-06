package com.zoufx.ai.agent.memory.config;

import com.zoufx.ai.agent.chat.property.ChatProps;
import com.zoufx.ai.agent.memory.service.ChatMemoryService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆装配。
 * {@link ChatMemoryService} 实现 LC4J ChatMemoryStore（write-back 缓冲），可直接作为
 * MessageWindowChatMemory.builder().chatMemoryStore() 入参；纯落库交给其内部的 ChatMemoryDao。
 * 未来切 Redis / Postgres 只需替换 ChatMemoryDao 的实现。
 */
@Configuration
@RequiredArgsConstructor
public class MemoryConfig {

    private final ChatProps chatProps;

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryService chatMemoryService) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(chatProps.getLoadMessage())
                .chatMemoryStore(chatMemoryService)
                // 强制 system 消息位于 messages[0]：LC4J 默认 false 会把 system 追加到末尾，
                // 导致后续轮次 system 卡在历史中间，违反 OpenAI/Anthropic API 期望
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }
}
