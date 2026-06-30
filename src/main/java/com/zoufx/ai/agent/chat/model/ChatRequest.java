package com.zoufx.ai.agent.chat.model;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * 聊天请求 DTO。
 *
 * <p>{@code anchorId} 非空时指向已存在的对话锚点；为空时表示新对话，由 ChatService.prepare 自动创建。
 * {@code userId} 必传——后端不再反查 anchor→user 映射。
 *
 * <p>{@code prevAnchorId} 仅在客户端发生"锚点切换"时携带（即上一条消息所在锚点 ≠ 本次 anchorId）。
 * 后端据此 fire-and-forget 触发对前一锚点消息流的 LLM 摘要压缩，写入 anchor.summary 缓存。
 *
 * <p>{@code thinking} 收纳思考相关配置（是否开启 + 思考深度），见 {@link Thinking}；
 * 整体缺省时按"未开启思考"处理。
 */
public record ChatRequest(
        @NotBlank(message = "不能为空") String prompt,
        @Nullable String anchorId,
        @Nullable String prevAnchorId,
        @Nullable Thinking thinking,
        @NotBlank(message = "不能为空") String userId) {
    public ChatRequest {
        if (thinking == null) thinking = Thinking.OFF;
    }
}
