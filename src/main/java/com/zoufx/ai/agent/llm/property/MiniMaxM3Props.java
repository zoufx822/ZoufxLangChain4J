package com.zoufx.ai.agent.llm.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MiniMax M3 profile 配置。仅在 {@code ai.llm.profile.active=MiniMax-M3} 时由
 * {@code MiniMaxM3Config} 装配读取。走 Anthropic 兼容协议（LC4J 1.17+ {@code AnthropicChatRequestParameters}
 * 支持 per-call thinkingType），连接 MiniMax 上游。
 *
 * <p>thinking 挡位（adaptive/disabled）是架构固定值（按业务角色路由 per-call 参数），不进配置；
 * 官方 M3 thinking schema 无 budget_tokens 字段，不可调思考预算。
 */
@Data
@ConfigurationProperties(prefix = "ai.llm.minimax-m3")
public class MiniMaxM3Props {

    private String baseUrl;
    private String apiKey;
    /** Anthropic 协议 API 版本头 */
    private String version = "2023-06-01";
    private Duration timeout = Duration.ofSeconds(60);
    private Chat chat = new Chat();

    @Data
    public static class Chat {
        /**
         * 模型 ID，思考档 / 快档共用，靠 thinkingType 参数区分。官方 API 现实际提供
         * {@code MiniMax-M3-highspeed}（结果相同、推理更快）——是否支持 thinking 尚未验证
         * （key 欠费中，见 minimax-m3-runtime-validation-pending 记忆），验证通过前不引入模型分层。
         */
        private String model;
        private int maxTokens = 16384;
    }
}
