package com.zoufx.ai.agent.vector.api;

import dev.langchain4j.data.embedding.Embedding;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * 把一条记忆写入语义索引（Qdrant）。embedding 由调用方算好传入——调用方场景各异
 * （召回 query 复用同一向量 / @Tool 即时文本 / backfill 批量遍历），embed 统一留在调用处，
 * 本接口只管写入。写入失败仅记日志不抛错（不阻断主流 / 工具返回 / backfill 遍历）。
 *
 * <p>pointId 由 (userId|memType|sourceId) ==确定性派生==，无需 upsert 标志：append-only 三类
 * sourceId 唯一 → 天然不重复且 backfill 幂等；user-impression 同字段复用 → 同 id 覆盖（天然 UPSERT）。
 *
 * <p>Qdrant ==只存向量 + 指针元数据，不存正文==。
 */
public interface IndexerService {

    /**
     * 同步索引（阻塞 Qdrant 写，调用方需保证不在 event loop 上）。
     * importance 由索引器内部 {@code ScorerService} 算（调用方无需关心评分细节）。
     *
     * @param memType  cold | significant-event | commitment | user-impression
     * @param sourceId 来源主键：cold_memory.id（字符串化）/ hot_memory key（UUID）/ impression 字段名
     * @param content  记忆文本（仅用于算 importance，不写进 payload）
     * @param role     cold 的 'user' / 'assistant'；hot 类传 null
     * @param username 已知称呼，供评分「提及称呼」加分；调用方按自己场景取（不便获取传 null）
     */
    void index(String userId, String memType, String sourceId, String content,
               @Nullable String role, long createdAt, Embedding embedding, @Nullable String username);

    Mono<Void> indexAsync(String userId, String memType, String sourceId, String content,
                          @Nullable String role, long createdAt, Embedding embedding, @Nullable String username);

    /**
     * 嵌入 + 异步索引——embed 放在 boundedElastic 上跑，不阻塞调用线程（@Tool / event loop 均安全）。
     * 调用方只需传原始文本；embed 失败记日志吞掉、不传播错误（同步本体 try/catch 全量吞）。
     *
     * @param memType  cold | significant-event | commitment | user-impression
     * @param sourceId 来源主键
     * @param text     原始文本（用于 embed + importance）
     */
    Mono<Void> indexTextAsync(String userId, String memType, String sourceId, String text,
                              @Nullable String role, long createdAt, @Nullable String username);
}
