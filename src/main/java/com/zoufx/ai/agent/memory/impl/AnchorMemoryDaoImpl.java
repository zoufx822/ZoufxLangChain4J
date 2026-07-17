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
import java.util.UUID;

/**
 * anchor_memory 元数据表的 SQLite 实现。
 *
 * <p>schema：
 * <pre>
 *   anchor_memory(id PK, user_id, title, summary, mood, created_at, last_active_at, summarized_at)
 *   INDEX (user_id, last_active_at DESC)
 * </pre>
 * {@code mood} 存本轮完整情绪轨迹（逗号连接，语义与 chat_memory.mood / cold_memory.mood 对齐）；
 * {@link AnchorMemory#lastMood()} 只读取轨迹末尾一个词——JSON 契约不变，前端零改动。
 * {@code summarized_at} 是滚动摘要的水位：summary 覆盖到的 last_active_at；小于当前 last_active_at 即有新内容待压。
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
                    mood           TEXT,
                    created_at     INTEGER NOT NULL,
                    last_active_at INTEGER NOT NULL,
                    summarized_at  INTEGER
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_anchor_memory_user_active ON anchor_memory(user_id, last_active_at DESC)");
        // 存量表迁移：补 summarized_at 水位列（SQLite 无 ADD COLUMN IF NOT EXISTS，先探列再加）
        if (!columnExists("anchor_memory", "summarized_at")) {
            jdbc.execute("ALTER TABLE anchor_memory ADD COLUMN summarized_at INTEGER");
            log.info("Migrated anchor_memory: added summarized_at column");
        }
        // 存量表迁移：last_mood → mood 改名（幂等：只在旧列存在且新列不存在时执行）
        if (!columnExists("anchor_memory", "mood") && columnExists("anchor_memory", "last_mood")) {
            jdbc.execute("ALTER TABLE anchor_memory RENAME COLUMN last_mood TO mood");
            log.info("Migrated anchor_memory: renamed last_mood column to mood");
        }
        log.info("AnchorMemoryDaoImpl schema ready (anchor_memory)");
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equals(row.get("name")));
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
    @Nullable
    public String loadSummary(String anchorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT summary FROM anchor_memory WHERE id = ?",
                    String.class, anchorId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<String> findAnchorsNeedingCompaction(long idleBefore) {
        return jdbc.queryForList("""
                SELECT id FROM anchor_memory
                WHERE last_active_at < ?
                  AND (summarized_at IS NULL OR summarized_at < last_active_at)
                """, String.class, idleBefore);
    }

    @Override
    public List<AnchorMemory> listOtherAnchors(String userId, String excludeAnchorId) {
        return jdbc.query("""
                SELECT id, user_id, title, summary, mood, created_at, last_active_at
                FROM anchor_memory
                WHERE user_id = ? AND id != ?
                ORDER BY last_active_at DESC
                """,
                (rs, i) -> new AnchorMemory(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        lastMoodOf(rs.getString("mood")),
                        rs.getLong("created_at"),
                        rs.getLong("last_active_at")),
                userId, excludeAnchorId);
    }

    // ====== 同步写 ======

    @Override
    public String create(String userId) {
        String anchorId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO anchor_memory (id, user_id, title, summary, mood, created_at, last_active_at)
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
                "UPDATE anchor_memory SET summary = ?, summarized_at = ? WHERE id = ? AND last_active_at = ?",
                summary, snapshotAt, anchorId, snapshotAt);
        if (rows == 0) {
            log.info("Summary CAS skipped [anchorId={}]: anchor was touched during compression", anchorId);
        }
    }

    @Override
    public void bumpSummarizedAt(String anchorId, long snapshotAt) {
        jdbc.update("UPDATE anchor_memory SET summarized_at = ? WHERE id = ? AND last_active_at = ?",
                snapshotAt, anchorId, snapshotAt);
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
    public void touch(String anchorId, @Nullable String moodTrail) {
        // last_active_at 推到 now；summary 是滚动摘要不动它（last_active_at 推进后 summarized_at 落后 → 下次扫描重压）
        // mood 存本轮完整轨迹（非跨轮累积）、走 COALESCE：本轮无 mood 事件时保留上一轮轨迹，不被 null 覆盖
        jdbc.update("UPDATE anchor_memory SET last_active_at = ?, mood = COALESCE(?, mood) WHERE id = ?",
                System.currentTimeMillis(), moodTrail, anchorId);
    }

    /** 逗号连接轨迹取末尾一个词——供 {@link AnchorMemory#lastMood()} 只读契约，空/null 原样返回。 */
    private static @Nullable String lastMoodOf(@Nullable String trail) {
        if (trail == null || trail.isBlank()) return trail;
        int comma = trail.lastIndexOf(',');
        return comma < 0 ? trail : trail.substring(comma + 1);
    }

    private List<AnchorMemory> listByUser(String userId) {
        return jdbc.query("""
                SELECT id, user_id, title, summary, mood, created_at, last_active_at
                FROM anchor_memory
                WHERE user_id = ?
                ORDER BY last_active_at DESC
                """,
                (rs, i) -> new AnchorMemory(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        lastMoodOf(rs.getString("mood")),
                        rs.getLong("created_at"),
                        rs.getLong("last_active_at")),
                userId);
    }
}
