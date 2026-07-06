package com.zoufx.ai.agent.memory.impl;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQLite 实现——纯落库，只实现 {@link ChatMemoryDao}。业务缓存/LC4J ChatMemoryStore 契约
 * 已上移至 {@link com.zoufx.ai.agent.memory.service.ChatMemoryService}。
 *
 * <p>隔离 key 是 anchor_id。user_id 列保留作冗余兜底，写入时通过
 * {@link AnchorMemoryDao#findUserId} 反查。anchorId 不存在视为异常状态，fail-fast 抛异常。
 *
 * <p>构造函数注入 {@link AnchorMemoryDao} 同时承担依赖排序：保证
 * {@code AnchorMemoryDaoImpl.init()} 在本类的 {@link #init()} 之前完成，
 * 因为后者的 backfill 需要 anchor 表已存在。
 *
 * <p>{@link #saveByAnchorId} 不自带事务——由调用方（{@code ChatService.persistTurn} 的外层
 * {@code memoryTxTemplate}）统一编排，JdbcTemplate 通过 DataSource 事务同步天然加入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryDaoImpl implements ChatMemoryDao {

    @Qualifier("memoryJdbcTemplate")
    private final JdbcTemplate jdbc;
    private final AnchorMemoryDao anchorMemoryDao;

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_memory (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id     TEXT    NOT NULL,
                    anchor_id   TEXT    NOT NULL,
                    role        TEXT    NOT NULL,
                    content     TEXT    NOT NULL,
                    mood        TEXT,
                    created_at  INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_memory_user ON chat_memory(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_memory_anchor ON chat_memory(anchor_id)");
        log.info("ChatMemoryDaoImpl schema ready (chat_memory)");
    }

    @Override
    public Mono<List<ChatMessage>> loadByAnchorIdAsync(String anchorId) {
        return Blocking.call(() -> loadByAnchorId(anchorId));
    }

    @Override
    public List<ChatMessage> loadByAnchorId(String anchorId) {
        return jdbc.query(
                "SELECT content FROM chat_memory WHERE anchor_id = ? ORDER BY id ASC",
                (rs, i) -> ChatMessageDeserializer.messageFromJson(rs.getString("content")),
                anchorId);
    }

    /** 匹配 AiMessage text 里的专用情绪标记 ⟦mood:KEYWORD⟧，持久化前剥离到 mood 列。 */
    private static final Pattern MOOD_TAG = Pattern.compile("⟦mood:([^⟧]+?)⟧");

    /**
     * 从 AiMessage 文本末尾提取 mood 关键字，返回剥离后的干净消息。
     * 非 AiMessage 或文本无 mood 标记时返回原消息 + mood=null。
     */
    private static ChatMessage stripMoodAndClean(ChatMessage msg, java.util.function.Consumer<String> moodSink) {
        if (msg instanceof AiMessage a && a.text() != null) {
            java.util.regex.Matcher m = MOOD_TAG.matcher(a.text());
            if (m.find()) {
                moodSink.accept(m.group(1).trim());
                String clean = m.replaceAll("");
                if (a.hasToolExecutionRequests()) {
                    return AiMessage.from(clean, a.toolExecutionRequests());
                }
                return AiMessage.from(clean);
            }
        }
        moodSink.accept(null);
        return msg;
    }

    @Override
    public void saveByAnchorId(String anchorId, List<ChatMessage> messages) {
        String userId = anchorMemoryDao.findUserId(anchorId);
        if (userId == null) {
            throw new IllegalStateException("Unknown anchorId: " + anchorId
                    + " — anchor row must exist before chat_memory writes");
        }
        // 持久化前剥离 AiMessage 文本中的 mood 标签到独立 mood 列
        List<String> moods = new ArrayList<>(messages.size());
        List<ChatMessage> cleaned = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            cleaned.add(stripMoodAndClean(msg, moods::add));
        }
        long base = System.currentTimeMillis();
        jdbc.update("DELETE FROM chat_memory WHERE anchor_id = ?", anchorId);
        if (cleaned.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO chat_memory (user_id, anchor_id, role, content, mood, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ChatMessage msg = cleaned.get(i);
                        ps.setString(1, userId);
                        ps.setString(2, anchorId);
                        ps.setString(3, msg.type().name());
                        ps.setString(4, ChatMessageSerializer.messageToJson(msg));
                        ps.setString(5, moods.get(i));
                        ps.setLong(6, base + i);
                    }

                    @Override
                    public int getBatchSize() {
                        return cleaned.size();
                    }
                });
    }

    @Override
    public void deleteByAnchorId(String anchorId) {
        jdbc.update("DELETE FROM chat_memory WHERE anchor_id = ?", anchorId);
    }
}
