package com.zoufx.ai.agent.llm.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * DeepSeek v4 profile 配置。仅在 {@code ai.llm.profile.active=deepseek-v4} 时由
 * {@code DeepSeekV4Config} 装配读取。走 OpenAI 兼容协议（LC4J OpenAI builder），
 * pro / flash 分别承担思考档 / 快档。
 *
 * <p>thinking 开关（enabled/disabled）与深度档（high/max）是架构固定值（按业务角色分 Bean），
 * 不进配置；returnThinking / sendThinking 在 Config 中固定开启
 * （多轮 / tool-call 后续请求需把 reasoning_content 原样回传，否则 API 拒绝）。
 */
@Data
@ConfigurationProperties(prefix = "ai.llm.deepseek-v4")
public class DeepSeekV4Props {

    private String baseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(60);
    private Chat chat = new Chat();

    @Data
    public static class Chat {
        /** 思考档模型 ID（前端开思考时的流式主聊天），如 deepseek-v4-pro */
        private String thinkingModel;
        /** 快档模型 ID（前端关思考的对话 + 锚点摘要压缩），如 deepseek-v4-flash */
        private String fastModel = "deepseek-v4-flash";
        private int maxTokens = 4096;
    }
}
