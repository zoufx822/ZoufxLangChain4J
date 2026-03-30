package com.zoufxdemo.zoufxlangchain4j.config;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4J 配置类 - 使用 Anthropic 模块连接 MiniMax API
 * 注意：MiniMax 的 Anthropic 兼容 API 路径需要 /v1，但 LangChain4J 默认不加
 * 这里通过扩展类的方式来修正这个问题
 */
@Slf4j
@Configuration
public class LangChain4JConfig {

    @Value("${spring.ai.anthropic.base-url}")
    private String baseUrl;

    @Value("${spring.ai.anthropic.api-key}")
    private String apiKey;

    @Value("${spring.ai.anthropic.version:2023-06-01}")
    private String version;

    @Value("${spring.ai.anthropic.chat.options.model}")
    private String modelName;

    @Value("${spring.ai.anthropic.chat.options.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${spring.ai.anthropic.chat.options.thinking.budget-tokens:2048}")
    private Integer thinkingBudgetTokens;

    /**
     * 启用思考模式的流式 ChatModel
     */
    @Bean("thinkingChatModel")
    public StreamingChatModel thinkingChatModel() {
        log.info("Creating thinkingChatModel with baseUrl: {}", baseUrl);
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .version(version)
                .modelName(modelName)
                .thinkingType("enabled")
                .thinkingBudgetTokens(thinkingBudgetTokens)
                .returnThinking(true)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * 不启用思考模式的流式 ChatModel
     */
    @Bean("nonThinkingChatModel")
    public StreamingChatModel nonThinkingChatModel() {
        log.info("Creating nonThinkingChatModel with baseUrl: {}", baseUrl);
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .version(version)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }
}