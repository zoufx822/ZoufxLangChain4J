package com.zoufx.ai.agent.memory.impl;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import com.zoufx.ai.agent.memory.support.ChatRequestRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQLite 实现——只实现业务 {@link ChatMemoryDao} 接口，后者继承 LC4J
 * {@code dev.langchain4j.store.memory.chat.ChatMemoryStore}，因此同一 Bean
 * 同时满足框架同步契约和业务反应式契约。
 *
 * <p>隔离 key 是 anchor_id（来自 LC4J 框架的 Object memoryId，业务侧也传同一值）。
 * user_id 列保留作冗余兜底，save 时通过 {@link AnchorMemoryDao#findUserId} 反查写入。
 * anchorId 不存在视为异常状态，fail-fast 抛异常。
 *
 * <p>构造函数注入 {@link AnchorMemoryDao} 同时承担依赖排序：保证
 * {@code AnchorMemoryDaoImpl.init()} 在本类的 {@link #init()} 之前完成，
 * 因为后者的 backfill 需要 anchor 表已存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryDaoImpl implements ChatMemoryDao {

    @Qualifier("memoryJdbcTemplate")
    private final JdbcTemplate jdbc;
    @Qualifier("memoryTxTemplate")
    private final TransactionTemplate tx;
    private final AnchorMemoryDao anchorMemoryDao;
    private final ChatRequestRegistry chatRequestRegistry;

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

    // ====== LC4J ChatMemoryStore 同步契约（框架线程调用；memoryId == anchorId）======

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return loadByAnchorId(memoryId.toString());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        saveByAnchorId(memoryId.toString(), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        deleteByAnchorId(memoryId.toString());
    }

    // ====== 业务反应式契约（自动 boundedElastic 调度）======

    @Override
    public Mono<List<ChatMessage>> loadByAnchorIdAsync(String anchorId) {
        return Blocking.call(() -> loadByAnchorId(anchorId));
    }

    @Override
    public Mono<Void> cleanupOrphansAsync(String anchorId) {
        return Blocking.run(() -> cleanupOrphans(anchorId));
    }

    @Override
    public Mono<Void> removeLastOrphanUserMessageAsync(String anchorId) {
        return Blocking.run(() -> removeLastOrphanUserMessage(anchorId));
    }

    // ====== 同步实现（被两套契约共享）======

    @Override
    public List<ChatMessage> loadByAnchorId(String anchorId) {
        // LC4J add(AiMessage_tool_calls) 写完后会立即 add(ToolExecutionResultMessage)，
        // 若在 load 时 sanitize，会把刚写入的 AiMessage 误判为孤儿。
        // sanitize 仅在 stop 取消流后由 ChatService.doOnCancel fire-and-forget 调用。
        return jdbc.query(
                "SELECT content FROM chat_memory WHERE anchor_id = ? ORDER BY id ASC",
                (rs, i) -> ChatMessageDeserializer.messageFromJson(rs.getString("content")),
                anchorId);
    }

    private void removeLastOrphanUserMessage(String anchorId) {
        List<ChatMessage> msgs = loadByAnchorId(anchorId);
        if (msgs.isEmpty()) return;
        ChatMessage last = msgs.get(msgs.size() - 1);
        if (!(last instanceof dev.langchain4j.data.message.UserMessage)) return;
        List<ChatMessage> trimmed = new ArrayList<>(msgs.subList(0, msgs.size() - 1));
        saveByAnchorId(anchorId, trimmed);
        log.info("Removed orphan user message [anchorId={}]", anchorId);
    }

    private void cleanupOrphans(String anchorId) {
        List<ChatMessage> raw = loadByAnchorId(anchorId);
        List<ChatMessage> cleaned = sanitize(anchorId, raw);
        if (cleaned.size() == raw.size()) return;
        saveByAnchorId(anchorId, cleaned);
        log.info("Post-cancel sanitize fired [anchorId={}]", anchorId);
    }

    /**
     * 双向剔除孤儿 tool 消息——OpenAI/Anthropic 要求 tool_calls 与 tool_result 必须配对。
     * <p>触发场景：用户在 LC4J 调用工具期间按下前端 stop 按钮，TokenStream 被 abort，
     * 可能出现两种损坏：
     * <ul>
     *   <li>AiMessage(tool_calls) 已写入，对应的 ToolExecutionResultMessage 未写入 → 「tool_calls must be followed by tool messages」</li>
     *   <li>ToolExecutionResultMessage 写入了但前驱 AiMessage(tool_calls) 已被 sanitize 移除 → 「tool messages must follow a tool_calls」</li>
     * </ul>
     * <p>在 load 时清理两侧，让历史自愈。下次 LC4J 写回 ChatMemory 时干净版本会持久化。
     */
    private List<ChatMessage> sanitize(String anchorId, List<ChatMessage> raw) {
        // 第一遍：收集所有现存的 tool result id（用于判断 AiMessage 是否完整）
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage m : raw) {
            if (m instanceof ToolExecutionResultMessage r) {
                resultIds.add(r.id());
            }
        }
        // 第二遍：识别"被保留的 AiMessage"提供的 valid request id（用于判断 tool result 是否合法）
        Set<String> validRequestIds = new HashSet<>();
        for (ChatMessage m : raw) {
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                boolean complete = ai.toolExecutionRequests().stream()
                        .allMatch(r -> resultIds.contains(r.id()));
                if (complete) {
                    ai.toolExecutionRequests().forEach(r -> validRequestIds.add(r.id()));
                }
            }
        }
        // 第三遍：执行过滤
        List<ChatMessage> cleaned = new ArrayList<>(raw.size());
        int droppedAi = 0, droppedTool = 0;
        for (ChatMessage m : raw) {
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                boolean complete = ai.toolExecutionRequests().stream()
                        .allMatch(r -> resultIds.contains(r.id()));
                if (!complete) { droppedAi++; continue; }
            }
            if (m instanceof ToolExecutionResultMessage r) {
                if (!validRequestIds.contains(r.id())) { droppedTool++; continue; }
            }
            cleaned.add(m);
        }
        if (droppedAi > 0 || droppedTool > 0) {
            log.warn("Sanitized chat_memory [anchorId={}] dropped {} orphan AiMessage(s) + {} orphan ToolExecutionResultMessage(s)",
                    anchorId, droppedAi, droppedTool);
        }
        return cleaned;
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

    /**
     * 扫描消息列表，找到最后一对相邻 UserMessage 并移除后者（重试产生的重复条目）。
     * 若不存在相邻 UserMessage 则原样返回。
     */
    private static List<ChatMessage> deduplicateConsecutiveUserMessages(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i > 0; i--) {
            if (messages.get(i) instanceof dev.langchain4j.data.message.UserMessage
                    && messages.get(i - 1) instanceof dev.langchain4j.data.message.UserMessage) {
                List<ChatMessage> result = new ArrayList<>(messages);
                result.remove(i);
                return result;
            }
        }
        return messages;
    }

    private void saveByAnchorId(String anchorId, List<ChatMessage> messages) {
        // 重试去重：同一请求首次写 UserMessage 后，LC4J 重试会再写一次形成相邻重复——
        // tryClaimFirstWrite 首次返 true（放行），重试返 false（去重）
        List<ChatMessage> effective = chatRequestRegistry.tryClaimFirstWrite(anchorId)
                ? messages
                : deduplicateConsecutiveUserMessages(messages);
        if (effective.size() < messages.size()) {
            log.info("Retry dedup: removed duplicate UserMessage [anchorId={}]", anchorId);
        }

        String userId = anchorMemoryDao.findUserId(anchorId);
        if (userId == null) {
            throw new IllegalStateException("Unknown anchorId: " + anchorId
                    + " — anchor row must exist before chat_memory writes");
        }
        // 持久化前剥离 AiMessage 文本中的 mood 标签到独立 mood 列
        List<String> moods = new ArrayList<>(effective.size());
        List<ChatMessage> cleaned = new ArrayList<>(effective.size());
        for (ChatMessage msg : effective) {
            cleaned.add(stripMoodAndClean(msg, moods::add));
        }
        long base = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
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
        });
    }

    private void deleteByAnchorId(String anchorId) {
        jdbc.update("DELETE FROM chat_memory WHERE anchor_id = ?", anchorId);
    }
}
