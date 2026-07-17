package com.zoufx.ai.agent.memory.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.zoufx.ai.agent.memory.api.ColdMemoryDao;
import com.zoufx.ai.agent.memory.model.ColdMemory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 冷内存（ColdMemoryDao）的 SQLite 实现——经历流原文的唯一权威源（system of record）。
 *
 * <p>检索走向量语义召回（{@code RecallService} + Qdrant），不用 FTS5：cold_memory 只存原文 + id，
 * 供向量索引作 sourceId、召回 hydration 回查正文；启动时幂等 DROP 旧库可能残留的 FTS 虚表/触发器。
 * 与 ChatMemoryDaoImpl 共用 memoryDataSource（HikariCP + WAL）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ColdMemoryDaoImpl implements ColdMemoryDao {

    @Qualifier("memoryJdbcTemplate")
    private final JdbcTemplate jdbc;
    @Qualifier("memoryTxTemplate")
    private final TransactionTemplate tx;

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS cold_memory (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    TEXT    NOT NULL,
                    role       TEXT    NOT NULL,
                    content    TEXT    NOT NULL,
                    mood       TEXT,
                    created_at INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cold_memory_user_time ON cold_memory(user_id, created_at)");

        // FTS5 下线：清理旧库残留的虚表 + 触发器（幂等，新库无影响）
        jdbc.execute("DROP TRIGGER IF EXISTS cold_memory_ad");
        jdbc.execute("DROP TABLE IF EXISTS cold_memory_fts");

        // 存量表迁移：metadata 列全库恒传 null，未曾使用，删除（SQLite 3.35+ 支持 DROP COLUMN，幂等先探列）
        if (columnExists("cold_memory", "metadata")) {
            jdbc.execute("ALTER TABLE cold_memory DROP COLUMN metadata");
            log.info("Migrated cold_memory: dropped unused metadata column");
        }

        log.info("ColdMemoryDaoImpl schema ready (cold_memory; FTS5 retired)");
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equals(row.get("name")));
    }

    @Override
    public long append(String userId, String role, String content, @Nullable String mood) {
        // 内层事务双保险：唯一调用方 ChatService.persistTurn 已在外层事务（REQUIRED join，本层无实际起停），
        // 但保证一旦脱离外层事务被误调，INSERT 与 last_insert_rowid() 仍落同一连接、取到正确自增 id
        return tx.execute(status -> {
            jdbc.update(
                    "INSERT INTO cold_memory (user_id, role, content, mood, created_at) VALUES (?, ?, ?, ?, ?)",
                    userId, role, content, mood, System.currentTimeMillis());
            Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            return id == null ? -1L : id;
        });
    }

    @Override
    public Long windowLowerBound(String userId, int windowSize) {
        if (windowSize <= 0) return null;
        List<Long> r = jdbc.query(
                "SELECT created_at FROM cold_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT 1 OFFSET ?",
                (rs, i) -> rs.getLong("created_at"), userId, windowSize - 1);
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public List<ColdMemory> fetchByIds(String userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(userId);
        args.addAll(ids);
        return jdbc.query(
                "SELECT id, role, content, mood, created_at FROM cold_memory WHERE user_id = ? AND id IN (" + placeholders + ")",
                (rs, i) -> new ColdMemory(
                        rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("mood"), rs.getLong("created_at")),
                args.toArray());
    }

}
