package com.zoufx.ai.agent.memory.model;

import org.jspecify.annotations.Nullable;

/**
 * 锚点元数据——一次对话窗口的归属信息。
 *
 * <p>{@code summary} 在锚点活跃时为 null（用户正在用，不压缩）；切走该锚点的瞬间由
 * {@code AnchorService} 异步生成并写入。回访锚点时被置回 null，等下次切走再压。
 *
 * <p>{@code title} 创建时可为 null，{@code ChatService.persistTurn} 用首条 user 消息
 * 截取自动 backfill；前端 PATCH /ai/anchors/{anchorId}/title 也可无条件覆盖。
 *
 * <p>{@code lastMood} 是本锚点最近一轮 AI 回复结束时的情绪关键词（参见 {@code Moods.ALL}）；
 * {@code ChatService.persistTurn} 从 {@code MoodEventProcessor} 取最后一次 mood 写入。
 * 无 mood 事件本轮维持旧值（COALESCE 语义），让前端"AI 还记得上次跟你聊时的情绪"。
 */
public record AnchorMemory(
        String id,
        String userId,
        @Nullable String title,
        @Nullable String summary,
        @Nullable String lastMood,
        long createdAt,
        long lastActiveAt) {
}
