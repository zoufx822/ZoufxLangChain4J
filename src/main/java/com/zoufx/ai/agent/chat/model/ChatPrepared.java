package com.zoufx.ai.agent.chat.model;

import dev.langchain4j.data.embedding.Embedding;
import org.jspecify.annotations.Nullable;

/**
 * {@link com.zoufx.ai.agent.chat.service.ChatService#prepare} 的返回值：
 * 解析后的 anchorId + 是否本次新建 + prompt 的预计算向量（召回复用，persistTurn 索引 cold user 行时复用，
 * 避免重复 embed；prepare 阶段 embed 失败时为 null）。
 */
public record ChatPrepared(String anchorId, boolean newAnchor, @Nullable Embedding userEmbedding) {
}
