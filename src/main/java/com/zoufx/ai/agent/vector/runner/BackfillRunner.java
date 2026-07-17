package com.zoufx.ai.agent.vector.runner;

import com.zoufx.ai.agent.memory.support.UserImpressionFields;
import com.zoufx.ai.agent.vector.api.IndexerService;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 存量记忆回填到向量库——仅 {@code ai.recall.backfill-on-start=true} 时启用（默认关）。
 *
 * <p>遍历 cold_memory + hot_memory 全量，逐条 embed + 写入 Qdrant（确定性 id，重跑幂等不重复）。
 * 同步逐条串行执行以自然限流（ApplicationRunner 线程允许阻塞）；这是一次性手动操作，慢可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.recall.backfill-on-start", havingValue = "true")
public class BackfillRunner implements ApplicationRunner {

    @Qualifier("memoryJdbcTemplate")
    private final JdbcTemplate jdbc;
    private final IndexerService indexer;
    private final EmbeddingModel embeddingModel;

    @Override
    public void run(ApplicationArguments args) {
        log.info("VectorBackfill START (this may take a while)");
        Map<String, String> usernameByUser = loadUsernames();

        AtomicInteger cold = new AtomicInteger();
        jdbc.query("SELECT id, user_id, role, content, created_at FROM cold_memory ORDER BY id", rs -> {
            String userId = rs.getString("user_id");
            String role = rs.getString("role");
            String content = rs.getString("content");
            long createdAt = rs.getLong("created_at");
            indexRow(userId, VectorPayload.COLD, String.valueOf(rs.getLong("id")), content, role, createdAt, usernameByUser.get(userId));
            cold.incrementAndGet();
        });

        AtomicInteger hot = new AtomicInteger();
        jdbc.query("SELECT user_id, type, key, value, updated_at FROM hot_memory", rs -> {
            String userId = rs.getString("user_id");
            String type = rs.getString("type");
            String key = rs.getString("key");
            String value = rs.getString("value");
            long updatedAt = rs.getLong("updated_at");
            String embedText = embedTextFor(type, key, value);
            indexRow(userId, type, key, embedText, null, updatedAt, usernameByUser.get(userId));
            hot.incrementAndGet();
        });

        log.info("VectorBackfill DONE cold={} hot={}", cold.get(), hot.get());
    }

    /** userId → username 查表——评分「提及称呼」加分用，保证与写入路径（ChatService.prepare）评分口径一致。 */
    private Map<String, String> loadUsernames() {
        Map<String, String> result = new HashMap<>();
        jdbc.query("SELECT user_id, value FROM hot_memory WHERE type = ? AND key = 'username'",
                rs -> { result.put(rs.getString("user_id"), rs.getString("value")); },
                VectorPayload.USER_IMPRESSION);
        return result;
    }

    /** 单行 embed + 索引——失败仅记日志继续遍历，不让个别坏行中断 backfill / 应用启动。 */
    private void indexRow(String userId, String type, String sourceId, String text, @Nullable String role, long ts, @Nullable String username) {
        try {
            indexer.index(userId, type, sourceId, text, role, ts, embeddingModel.embed(text).content(), username);
        } catch (Exception e) {
            log.warn("Backfill index failed [type={} sourceId={}]: {}", type, sourceId, e.toString());
        }
    }

    /** user-impression 嵌入带字段语义的短句（与 UserImpressionUpdateTool 共用 helper）；其余直接 embed value。 */
    private String embedTextFor(String type, String key, String value) {
        return VectorPayload.USER_IMPRESSION.equals(type)
                ? UserImpressionFields.embedText(key, value)
                : value;
    }
}
