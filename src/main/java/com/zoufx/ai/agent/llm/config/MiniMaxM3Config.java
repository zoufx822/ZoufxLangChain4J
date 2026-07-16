package com.zoufx.ai.agent.llm.config;

import com.zoufx.ai.agent.chat.api.ChatAssistant;
import com.zoufx.ai.agent.chat.api.StreamingChatPort;
import com.zoufx.ai.agent.chat.model.Thinking;
import com.zoufx.ai.agent.chat.support.AssistantFactory;
import com.zoufx.ai.agent.llm.model.Features;
import com.zoufx.ai.agent.llm.property.MiniMaxM3Props;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
 * profile 名对齐官方模型 ID（连的是 MiniMax 不是真 Claude）。
 *
 * <p>M3 无快慢模型分层，思考档 / 快档差异只在 thinkingType 上，故 modelName 用单一
 * {@code chat.model} 配置、在 builder 期固定、无需 per-call 覆盖。
 * LC4J 1.17+ 的 {@code AnthropicChatRequestParameters} 支持 per-call 覆盖 thinkingType，
 * 与 DeepSeek（OpenAI 协议）同构地只装一个 {@code streamingChatModel}，
 * 由 {@link #streamingChatPort} 按请求现场拼参数覆盖。思考档 = {@code adaptive}；
 * 快档 = {@code disabled}（M3 默认即关闭，显式声明防上游默认漂移）。
 * 官方 M3 thinking schema 只有 adaptive/disabled 两态、无 budget_tokens 字段
 * （不同于 Claude 原生 extended thinking 的分级 budget 控制），故不发该参数。
 *
 * <p>returnThinking + sendThinking 统一开启：思考档与快档共享同一份会话记忆，
 * 官方要求历史中的 thinking 内容块在后续轮次原样回传（尤其工具调用对话）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MiniMaxM3Props.class)
@ConditionalOnProperty(name = "ai.llm.profile.active", havingValue = "MiniMax-M3")
public class MiniMaxM3Config {

    private final MiniMaxM3Props props;

    /** 流式对话模型：单一 modelName，思考/快档差异全部由 per-call thinkingType 覆盖（见 streamingChatPort）。 */
    @Bean
    public StreamingChatModel streamingChatModel() {
        String model = props.getChat().getModel();
        log.info("Creating streamingChatModel [MiniMax-M3] model={} (thinking/fast 靠 per-call thinkingType 区分)", model);
        return AnthropicStreamingChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .version(props.getVersion())
                .modelName(model)
                .maxTokens(props.getChat().getMaxTokens())
                .timeout(props.getTimeout())
                .returnThinking(true)
                .sendThinking(true)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /** 流式对话端口：单模型 + per-call thinkingType 切换思考档（adaptive）/ 快档（disabled）。 */
    @Bean
    public StreamingChatPort streamingChatPort(AssistantFactory factory, StreamingChatModel streamingChatModel) {
        ChatAssistant assistant = factory.create(streamingChatModel);
        return (anchorId, userMessage, thinking) -> assistant.chat(anchorId, userMessage, perCallParams(thinking));
    }

    /** effort 对 Anthropic 协议无业务分档需求，忽略（能力声明里已对前端标 unsupported）。 */
    private ChatRequestParameters perCallParams(Thinking thinking) {
        if (!thinking.enabled()) {
            return AnthropicChatRequestParameters.builder()
                    .thinkingType("disabled")
                    .build();
        }
        return AnthropicChatRequestParameters.builder()
                .thinkingType("adaptive")
                .build();
    }

    /** 同步模型：锚点摘要压缩，不参与流式聊天主路（恒用 thinking 关闭）。 */
    @Bean
    public ChatModel syncChatModel() {
        String model = props.getChat().getModel();
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
