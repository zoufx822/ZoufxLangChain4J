package com.zoufx.ai.agent.memory.api;

import dev.langchain4j.data.message.ChatMessage;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 会话窗口纯落库接口——业务/缓存已上移至
 * {@link com.zoufx.ai.agent.memory.service.ChatMemoryService}（实现 LC4J ChatMemoryStore，
 * write-back 缓冲）。本接口只剩 SQL 读写，供 Service 的 seed/commit/deleteMessages 调用。
 *
 * <p>save 时需把 user_id 列也写入（冗余兜底）——由实现侧通过 {@link AnchorMemoryDao#findUserId}
 * 反查。anchorId 不存在视为异常状态，fail-fast。
 *
 * <p>同步方法体供已脱离 event loop 的调用方（boundedElastic 上的阻塞流水线）；
 * {@code loadByAnchorIdAsync} 是唯一仍有反应式调用方的壳（controller 的 GET /ai/anchors/{id}/messages）。
 */
public interface ChatMemoryDao {

    /** 同步加载某锚点全部消息（按写入顺序）。 */
    List<ChatMessage> loadByAnchorId(String anchorId);

    Mono<List<ChatMessage>> loadByAnchorIdAsync(String anchorId);

    /** 同步整锚点全量替换写入（DELETE + INSERT）——调用方需已在事务/boundedElastic 上下文。 */
    void saveByAnchorId(String anchorId, List<ChatMessage> messages);

    /** 同步清空该锚点全部消息——供 {@code ChatMemoryStore.deleteMessages} 契约的防御性实现。 */
    void deleteByAnchorId(String anchorId);
}
