package com.zoufx.ai.agent.llm.config;

import com.zoufx.ai.agent.chat.api.ChatAssistant;
import com.zoufx.ai.agent.chat.api.StreamingChatPort;
import com.zoufx.ai.agent.chat.support.AssistantFactory;
import com.zoufx.ai.agent.llm.model.Features;
import com.zoufx.ai.agent.llm.property.MiniMaxM3Props;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MiniMax M3 profile：把项目的模型角色映射到 MiniMax 实现（Anthropic 兼容协议）。
 *
 * <p>仅在 {@code ai.llm.profile.active=MiniMax-M3} 时激活。配置来自 {@link MiniMaxM3Props}。
 * profile 名对齐官方模型 ID（连的是 MiniMax 不是真 Claude）。三档同模型，靠 thinking 参数分档：
 * 思考档 = {@code adaptive}；快档 = {@code disabled}（M3 默认即关闭，显式声明防上游默认漂移）。
 *
 * <p>Anthropic 协议的 thinking 只能在 model builder 期固定（LC4J 1.13.1 无 per-call 载体），
 * 故思考档 / 快档各装一个流式模型，由 {@link #streamingChatPort} 按请求选其一、per-call 参数传 EMPTY。
 * 这与 DeepSeek 的单模型 + per-call 切换不同，差异被 {@link StreamingChatPort} 屏蔽在 ChatService 之外。
 *
 * <p>returnThinking + sendThinking 统一开启：两档共享同一份会话记忆，
 * 官方要求历史中的 thinking 内容块在后续轮次原样回传（尤其工具调用对话）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MiniMaxM3Props.class)
@ConditionalOnProperty(name = "ai.llm.profile.active", havingValue = "MiniMax-M3")
public class MiniMaxM3Config {

    private final MiniMaxM3Props props;

    /** 流式对话端口：thinking 档（adaptive）/ 快档（disabled）各一个模型，只按 enabled 选其一。
     *  effort 对 Anthropic 协议无 per-request 载体，忽略（能力声明里已对前端标 unsupported）。 */
    @Bean
    public StreamingChatPort streamingChatPort(AssistantFactory factory) {
        ChatAssistant thinkingAssistant = factory.create(streamingModel("adaptive"));
        ChatAssistant fastAssistant = factory.create(streamingModel("disabled"));
        return (anchorId, userMessage, thinking) ->
                (thinking.enabled() ? thinkingAssistant : fastAssistant)
                        .chat(anchorId, userMessage, ChatRequestParameters.builder().build());
    }

    /** 构造一个流式模型：thinking 档传 {@code adaptive}（带 budget），快档传 {@code disabled}。 */
    private AnthropicStreamingChatModel streamingModel(String thinkingType) {
        String model = props.getChat().getThinkingModel();
        log.info("Creating streamingChatModel [MiniMax-M3] model={} thinking={}", model, thinkingType);
        var builder = AnthropicStreamingChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .version(props.getVersion())
                .modelName(model)
                .maxTokens(props.getChat().getMaxTokens())
                .timeout(props.getTimeout())
                .thinkingType(thinkingType)
                .returnThinking(true)
                .sendThinking(true)
                .logRequests(true)
                .logResponses(true);
        if ("adaptive".equals(thinkingType)) {
            builder.thinkingBudgetTokens(props.getThinking().getBudgetTokens());
        }
        return builder.build();
    }

    /** 同步模型：锚点摘要压缩，不参与流式聊天主路（恒用 thinking 关闭）。 */
    @Bean
    public ChatModel syncChatModel() {
        String model = props.getChat().getFastModel();
        log.info("Creating syncChatModel [MiniMax-M3] model={} thinking=disabled", model);
        return AnthropicChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .version(props.getVersion())
                .modelName(model)
                .maxTokens(props.getChat().getMaxTokens())
                .timeout(props.getTimeout())
                .thinkingType("disabled")
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    @Bean
    public Features features() {
        return new Features("MiniMax-M3", Features.ThinkEffort.unsupported());
    }
}
