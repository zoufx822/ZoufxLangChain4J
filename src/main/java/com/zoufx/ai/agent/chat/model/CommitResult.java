package com.zoufx.ai.agent.chat.model;

import com.zoufx.ai.agent.memory.service.HotMemoryService;

import java.util.List;

/**
 * {@code ChatService.persistTurn} 事务的返回值：cold 行 id（未写入为 -1）+ 本轮已落库的 hot 条目，
 * 供事务提交成功后 fire-and-forget 索引 Qdrant 用（向量是派生索引，绝不能先于正本提交）。
 */
public record CommitResult(long coldUserId, long coldAssistantId, List<HotMemoryService.Entry> hotEntries) {
}
