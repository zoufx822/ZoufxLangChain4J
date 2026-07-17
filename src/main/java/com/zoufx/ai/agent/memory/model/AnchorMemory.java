package com.zoufx.ai.agent.memory.model;

import org.jspecify.annotations.Nullable;

/**
 * 锚点元数据——一次对话窗口的归属信息。
 *
 * <p>{@code summary} 是滚动摘要：由定时压缩扫描器对「空闲超阈值且有新内容」的锚点增量更新
 * （旧摘要 + 最近对话 → 更新后的摘要）。首次压缩前为 null；此后聊新内容会让摘要落后，下次扫描再增量重压。
 *
 * <p>{@code title} 创建时可为 null，{@code ChatService.persistTurn} 用首条 user 消息
 * 截取自动 backfill；前端 PATCH /ai/anchors/{anchorId}/title 也可无条件覆盖。
 *
 * <p>{@code lastMood} 是本锚点最近一轮 AI 回复结束时的情绪关键词（参见 {@code Moods.ALL}）——
 * DB 层 {@code anchor_memory.mood} 存的是本轮完整情绪轨迹（逗号连接），本字段只取轨迹末尾一个词，
 * JSON 契约保持不变。无 mood 事件本轮维持旧轨迹（COALESCE 语义），让前端"AI 还记得上次跟你聊时的情绪"。
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
