package com.zoufx.ai.agent.chat.support;

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
import org.springframework.stereotype.Component;

/**
 * 从一个 {@link StreamingChatModel} 装出一个 {@link ChatAssistant}，统一挂载共享的会话记忆、
 * 动态 system prompt 与工具集——profile 无关的部分集中在此，避免各 profile Config 重复布线。
 *
 * <p>每个 profile 按自身需要调用 {@link #create}：能 per-call 切 thinking 的建一个，
 * 不能的建两个（思考档 / 快档各一个模型）。
 */
@Component
@RequiredArgsConstructor
public class AssistantFactory {

    private final ChatMemoryProvider chatMemoryProvider;
    private final PromptComposer composer;
    private final TavilySearchTool tavilySearchTool;
    private final ColdMemorySearchTool coldMemorySearchTool;
    private final UserImpressionUpdateTool userImpressionUpdateTool;
    private final SignificantEventRecordTool significantEventRecordTool;
    private final CommitmentRecordTool commitmentRecordTool;

    public ChatAssistant create(StreamingChatModel chatModel) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(composer.asProvider())
                .tools(tavilySearchTool, coldMemorySearchTool, userImpressionUpdateTool,
                        significantEventRecordTool, commitmentRecordTool)
                .build();
    }
}
