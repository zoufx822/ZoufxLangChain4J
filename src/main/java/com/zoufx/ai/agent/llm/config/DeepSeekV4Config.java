package com.zoufx.ai.agent.llm.config;

import com.zoufx.ai.agent.chat.api.ChatAssistant;
import com.zoufx.ai.agent.chat.api.StreamingChatPort;
import com.zoufx.ai.agent.chat.model.Thinking;
import com.zoufx.ai.agent.chat.support.AssistantFactory;
import com.zoufx.ai.agent.llm.model.Features;
import com.zoufx.ai.agent.llm.property.DeepSeekV4Props;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek v4 profile：把项目的模型角色映射到 DeepSeek 实现（OpenAI 兼容协议）。
 *
 * <p>仅在 {@code ai.llm.profile.active=deepseek-v4} 时激活。配置来自 {@link DeepSeekV4Props}。
 *
 * <p>OpenAI 协议支持 per-call {@code OpenAiChatRequestParameters}，故只装一个流式模型：
 * 思考档 / 快档的差异（模型名 pro/flash + reasoning_effort + thinking enabled/disabled）
 * 由 {@link #streamingChatPort} 按请求现场拼参数覆盖，无需多个模型 Bean。
 * {@code thinking.type} 是 DeepSeek 私有扩展字段，通过 {@code customParameters} 注入请求体根级。
 *
 * <p>returnThinking + sendThinking 统一开启：思考档与快档共享同一份会话记忆（按 anchorId 分桶），
 * 上一轮产出的 reasoning_content 必须在下一轮原样回传，否则 API 以
 * "reasoning_content must be passed back" 拒绝。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(DeepSeekV4Props.class)
@ConditionalOnProperty(name = "ai.llm.profile.active", havingValue = "deepseek-v4")
public class DeepSeekV4Config {

    private final DeepSeekV4Props props;

    /**
     * 前端档位值。DeepSeek API 可传 5 值（low/medium/high/max/xhigh）但实测行为仅两档：
     * 非 max 全等价（官方档名 high，含不传）；max/xhigh 等价（服务端注入增强思考指令）。
     * 故前端只设两档，映射见 {@link #apiEffort}。
     */
    private static final String EFFORT_NORMAL = "normal";
    private static final String EFFORT_MAX = "max";
    /** 前端默认选档（thinking 开启但未指定时也回落到它）。 */
    private static final String EFFORT_DEFAULT = EFFORT_NORMAL;
    /** 两档（value=前端档位值，label=前端文案）。 */
    private static final List<Features.EffortOption> EFFORT_OPTIONS = List.of(
            new Features.EffortOption(EFFORT_NORMAL, "标准"),
            new Features.EffortOption(EFFORT_MAX, "极致"));

    /** DeepSeek 私有 thinking 字段，序列化为请求体根级 {@code "thinking": {"type": ...}}。 */
    private static Map<String, Object> thinkingParam(String type) {
        return Map.of("thinking", Map.of("type", type));
    }

    /**
     * 流式对话模型：故意不设默认 modelName——模型名恒由 per-call 参数指定（见 streamingChatPort）。
     * 绕过端口直调且未传 modelName 时，请求体缺 model 字段会被 API 拒绝，fail-fast。
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        log.info("Creating streamingChatModel [deepseek-v4] (modelName 由 per-call 指定: thinking={} / fast={})",
                props.getChat().getThinkingModel(), props.getChat().getFastModel());
        return OpenAiStreamingChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .maxTokens(props.getChat().getMaxTokens())
                .timeout(props.getTimeout())
                .returnThinking(true)
                .sendThinking(true)
                // 测试期开启完整请求/响应日志，便于观察发给 LLM 的真实 JSON
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /** 流式对话端口：单模型 + per-call 参数切换思考档（pro + effort + enabled）/ 快档（flash + disabled）。 */
    @Bean
    public StreamingChatPort streamingChatPort(AssistantFactory factory, StreamingChatModel streamingChatModel) {
        ChatAssistant assistant = factory.create(streamingChatModel);
        return (anchorId, userMessage, thinking) -> assistant.chat(anchorId, userMessage, perCallParams(thinking));
    }

    private ChatRequestParameters perCallParams(Thinking thinking) {
        if (!thinking.enabled()) {
            return OpenAiChatRequestParameters.builder()
                    .modelName(props.getChat().getFastModel())
                    .customParameters(thinkingParam("disabled"))
                    .build();
        }
        return OpenAiChatRequestParameters.builder()
                .modelName(props.getChat().getThinkingModel())
                .customParameters(thinkingParam("enabled"))
                .reasoningEffort(apiEffort(resolveEffort(thinking.effort())))
                .build();
    }

    /** 前端档位 → API 值：normal→high（官方默认档，显式钉死防上游默认漂移），max→max。 */
    private static String apiEffort(String effort) {
        return EFFORT_NORMAL.equals(effort) ? "high" : EFFORT_MAX;
    }

    /** 校验 effort 是否在支持档位内；非法/为空回落默认档。 */
    private String resolveEffort(@Nullable String effort) {
        if (effort != null && EFFORT_OPTIONS.stream().anyMatch(o -> o.value().equals(effort))) {
            return effort;
        }
        if (effort != null) log.warn("Unknown effort='{}', fallback to default='{}'", effort, EFFORT_DEFAULT);
        return EFFORT_DEFAULT;
    }

    /** 同步模型：锚点摘要压缩，不参与流式聊天主路（恒用快档 + thinking 关闭）。 */
    @Bean
    public ChatModel syncChatModel() {
        String model = props.getChat().getFastModel();
        log.info("Creating syncChatModel [deepseek-v4] model={} thinking=disabled", model);
        return OpenAiChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(model)
                .maxTokens(props.getChat().getMaxTokens())
                .timeout(props.getTimeout())
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .customParameters(thinkingParam("disabled"))
                        .build())
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    @Bean
    public Features features() {
        return new Features("deepseek-v4",
                new Features.ThinkEffort(true, EFFORT_DEFAULT, EFFORT_OPTIONS));
    }
}
