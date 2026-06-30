package com.zoufx.ai.agent.llm.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 当前激活 LLM profile 的能力声明，经 {@code GET /ai/features} 透传给前端做差异化渲染。
 *
 * <p>各 profile 的 Config 装配本 Bean（{@code @ConditionalOnProperty} 保证同期仅一个）。
 *
 * @param profile 当前激活的 profile 名（与模型官方命名一致，如 "deepseek-v4" / "MiniMax-M3"）
 * @param effort  思考深度（reasoning effort）能力声明
 */
public record Features(String profile, ThinkEffort effort) {

    /**
     * 思考深度能力。能逐请求调节的 profile（OpenAI 协议）声明 supported + 档位；
     * 不能的（Anthropic 协议无 per-request 载体）用 {@link #unsupported()}。
     *
     * @param supported    是否支持逐请求调节
     * @param defaultValue 默认档（前端默认选中、未指定时回落）；unsupported 时为 null
     * @param options      可选档位；unsupported 时为空
     */
    public record ThinkEffort(boolean supported, @Nullable String defaultValue, List<EffortOption> options) {
        public static ThinkEffort unsupported() {
            return new ThinkEffort(false, null, List.of());
        }
    }

    /** 单个档位：value = 传给后端/LLM 的 API 值；label = 前端显示文案。 */
    public record EffortOption(String value, String label) {}
}
