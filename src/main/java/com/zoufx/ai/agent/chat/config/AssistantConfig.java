package com.zoufx.ai.agent.chat.config;

import com.zoufx.ai.agent.chat.api.ChatAssistant;
import com.zoufx.ai.agent.prompt.support.PromptComposer;
import com.zoufx.ai.agent.tool.impl.ColdMemorySearchTool;
import com.zoufx.ai.agent.tool.impl.CommitmentRecordTool;
import com.zoufx.ai.agent.tool.impl.SignificantEventRecordTool;
import com.zoufx.ai.agent.tool.impl.TavilySearchTool;
import com.zoufx.ai.agent.tool.impl.UserImpressionUpdateTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配两个 {@link ChatAssistant} Bean，分别绑定当前激活 profile 的流式模型角色：
 * thinkingStreamingChatModel（思考档，前端开思考）/ fastStreamingChatModel（快档，前端关思考）。
 *
 * <p>LC4J AiServices 不支持 per-call 参数覆盖，thinking 策略只能在模型 builder 期固定，
 * 因此每档一个 assistant，由 ChatService 按请求的 thinking 开关路由。两个 assistant 共享同一
 * ChatMemoryProvider（同 anchorId 同记忆），切档不丢上下文。
 *
 * <p>System prompt 由 {@link PromptComposer} 按 Prompt 序列动态组装。
 */
@Configuration
@RequiredArgsConstructor
public class AssistantConfig {

    private final ChatMemoryProvider chatMemoryProvider;
    private final TavilySearchTool tavilySearchTool;
    private final ColdMemorySearchTool coldMemorySearchTool;
    private final UserImpressionUpdateTool userImpressionUpdateTool;
    private final SignificantEventRecordTool significantEventRecordTool;
    private final CommitmentRecordTool commitmentRecordTool;
    private final PromptComposer composer;

    @Bean("thinkingAssistant")
    public ChatAssistant thinkingAssistant(
            @Qualifier("thinkingStreamingChatModel") StreamingChatModel thinkingStreamingChatModel) {
        return build(thinkingStreamingChatModel);
    }

    @Bean("fastAssistant")
    public ChatAssistant fastAssistant(
            @Qualifier("fastStreamingChatModel") StreamingChatModel fastStreamingChatModel) {
        return build(fastStreamingChatModel);
    }

    private ChatAssistant build(StreamingChatModel chatModel) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(composer.asProvider())
                .tools(tavilySearchTool, coldMemorySearchTool, userImpressionUpdateTool,
                        significantEventRecordTool, commitmentRecordTool)
                .build();
    }
}
