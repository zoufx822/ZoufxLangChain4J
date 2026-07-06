package com.zoufx.ai.agent.memory.impl;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.model.AnchorMemory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * anchor_memory 元数据表的 SQLite 实现。
 *
 * <p>schema：
 * <pre>
 *   anchor_memory(id PK, user_id, title, summary, created_at, last_active_at)
 *   INDEX (user_id, last_active_at DESC)
 * </pre>
 *
 * <p>同步读 / 反应式写——见 {@link AnchorMemoryDao} 接口文档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnchorMemoryDaoImpl implements AnchorMemoryDao {

    @Qualifier("memoryJdbcTemplate")
    private final JdbcTemplate jdbc;

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS anchor_memory (
                    id             TEXT    PRIMARY KEY,
                    user_id        TEXT    NOT NULL,
                    title          TEXT,
                    summary        TEXT,
                    last_mood      TEXT,
                    created_at     INTEGER NOT NULL,
                    last_active_at INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_anchor_memory_user_active ON anchor_memory(user_id, last_active_at DESC)");
        log.info("AnchorMemoryDaoImpl schema ready (anchor_memory)");
    }

    // ====== 同步读 ======

    @Override
    @Nullable
    public String findUserId(String anchorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT user_id FROM anchor_memory WHERE id = ?",
                    String.class, anchorId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    @Nullable
    public Long snapshotActiveAt(String anchorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT last_active_at FROM anchor_memory WHERE id = ?",
                    Long.class, anchorId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<AnchorMemory> listOtherAnchors(String userId, String excludeAnchorId) {
        return jdbc.query("""
                SELECT id, user_id, title, summary, last_mood, created_at, last_active_at
                FROM anchor_memory
                WHERE user_id = ? AND id != ?
                ORDER BY last_active_at DESC
                """,
                (rs, i) -> new AnchorMemory(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("last_mood"),
                        rs.getLong("created_at"),
                        rs.getLong("last_active_at")),
                userId, excludeAnchorId);
    }

    // ====== 同步写 ======

    @Override
    public String create(String userId) {
        String anchorId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO anchor_memory (id, user_id, title, summary, last_mood, created_at, last_active_at)
                VALUES (?, ?, NULL, NULL, NULL, ?, ?)
                """, anchorId, userId, now, now);
        return anchorId;
    }

    // ====== 反应式包装 ======

    @Override
    public Mono<List<AnchorMemory>> listByUserAsync(String userId) {
        return Blocking.call(() -> listByUser(userId));
    }

    @Override
    public Mono<Void> updateTitleAsync(String anchorId, String title) {
        return Blocking.run(() -> updateTitle(anchorId, title));
    }

    // ====== 同步实现 ======

    @Override
    public void updateSummaryIfUnchanged(String anchorId, String summary, long snapshotAt) {
        int rows = jdbc.update(
                "UPDATE anchor_memory SET summary = ? WHERE id = ? AND last_active_at = ?",
                summary, anchorId, snapshotAt);
        if (rows == 0) {
            log.info("Summary CAS skipped [anchorId={}]: anchor was touched during compression", anchorId);
        }
    }

    @Override
    public void updateTitleIfBlank(String anchorId, String title) {
        // 仅当 title IS NULL 或为空白时才填——避免覆盖用户手动改过的标题
        jdbc.update("""
                UPDATE anchor_memory
                SET title = ?
                WHERE id = ? AND (title IS NULL OR TRIM(title) = '')
                """, title, anchorId);
    }

    private void updateTitle(String anchorId, String title) {
        jdbc.update("UPDATE anchor_memory SET title = ? WHERE id = ?", title, anchorId);
    }

    @Override
    public void touch(String anchorId, @Nullable String lastMood) {
        // 回访锚点：last_active_at 推到 now + summary 置 NULL（旧摘要作废，下次切走再压）
        // last_mood 走 COALESCE：本轮无 mood 事件时保留旧值，不被 null 覆盖
        jdbc.update("UPDATE anchor_memory SET last_active_at = ?, summary = NULL, last_mood = COALESCE(?, last_mood) WHERE id = ?",
                System.currentTimeMillis(), lastMood, anchorId);
    }

    private List<AnchorMemory> listByUser(String userId) {
        return jdbc.query("""
                SELECT id, user_id, title, summary, last_mood, created_at, last_active_at
                FROM anchor_memory
                WHERE user_id = ?
                ORDER BY last_active_at DESC
                """,
                (rs, i) -> new AnchorMemory(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("last_mood"),
                        rs.getLong("created_at"),
                        rs.getLong("last_active_at")),
                userId);
    }
}
