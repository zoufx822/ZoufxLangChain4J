package com.zoufx.ai.agent.vector.impl;

import com.zoufx.ai.agent.vector.api.ScorerService;
import com.zoufx.ai.agent.vector.api.IndexerService;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.zoufx.ai.agent.base.support.Blocking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * {@link IndexerService} 的 Qdrant 实现。
 *
 * <p>写入 = 组装 {@link TextSegment} + 指针元数据 → {@code addAll(id, emb, seg)}。
 * text 字段==只放 sourceId 占位，不放正文==——正文留在 SQLite 正本，召回时按指针回查。
 * pointId 由 (userId|memType|sourceId) 确定性派生，重复索引覆盖同一 point（幂等 / 天然 UPSERT）。
 * 失败吞掉仅记日志的语义在同步本体内实现（try/catch），异步包装天然继承。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexerServiceImpl implements IndexerService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ScorerService scorer;
    private final EmbeddingModel embeddingModel;

    @Override
    public void index(String userId, String memType, String sourceId, String content,
                      @Nullable String role, long createdAt, Embedding embedding, @Nullable String username) {
        try {
            double importance = scorer.score(memType, role, content, username);
            Metadata md = new Metadata();
            md.put(VectorPayload.USER_ID, userId);
            md.put(VectorPayload.MEM_TYPE, memType);
            md.put(VectorPayload.SOURCE_ID, sourceId);
            md.put(VectorPayload.CREATED_AT, createdAt);
            md.put(VectorPayload.IMPORTANCE, importance);
            if (role != null && !role.isBlank()) md.put(VectorPayload.ROLE, role);

            // 空正文：text 字段放 sourceId 占位（非内容），正文留 SQLite 正本
            TextSegment seg = TextSegment.from(sourceId, md);
            String pid = pointId(userId, memType, sourceId);
            embeddingStore.addAll(List.of(pid), List.of(embedding), List.of(seg));
            log.debug("Indexed vector [userId={}] memType={} sourceId={} importance={}", userId, memType, sourceId, importance);
        } catch (Exception e) {
            log.warn("IndexerService failed [memType={} sourceId={}]: {}", memType, sourceId, e.toString());
        }
    }

    @Override
    public Mono<Void> indexAsync(String userId, String memType, String sourceId, String content,
                                 @Nullable String role, long createdAt, Embedding embedding, @Nullable String username) {
        return Blocking.run(() -> index(userId, memType, sourceId, content, role, createdAt, embedding, username));
    }

    @Override
    public Mono<Void> indexTextAsync(String userId, String memType, String sourceId, String text,
                                     @Nullable String role, long createdAt, @Nullable String username) {
        return Blocking.run(() -> {
            Embedding emb;
            try {
                emb = embeddingModel.embed(text).content();
            } catch (Exception e) {
                log.warn("Embed failed, skip vector index [memType={} sourceId={}]: {}", memType, sourceId, e.toString());
                return;
            }
            index(userId, memType, sourceId, text, role, createdAt, emb, username);
        });
    }

    /** 确定性 point id = UUIDv3(userId|memType|sourceId)。 */
    static String pointId(String userId, String memType, String sourceId) {
        String key = userId + "|" + memType + "|" + sourceId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
