package com.zoufx.ai.agent.memory.service;

import com.zoufx.ai.agent.memory.api.HotMemoryDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 印象/经历/承诺业务层——write-back 缓冲：LLM 工具在轮内识别到内容时 {@link #stage} 暂存
 * （不落库，仍向 LLM 返回"已记下"），成功才 {@link #commit}（参与外层事务），失败/取消 {@link #discard}。
 *
 * <p>与 {@link com.zoufx.ai.agent.memory.service.ChatMemoryService} 并行的 write-back 缓冲，
 * 但不实现任何 LC4J 契约——纯业务层，供三个 Hot {@code @Tool} 与 {@code ChatService.persistTurn} 调用。
 */
@Service
@RequiredArgsConstructor
public class HotMemoryService {

    private final HotMemoryDao dao;
    private final ConcurrentHashMap<String, List<Entry>> pending = new ConcurrentHashMap<>();

    /** @Tool 方法调：本轮识别到一条印象/经历/承诺，先暂存不落库、不建索引。 */
    public void stage(String anchorId, String userId, String type, String key, String value, String embedText) {
        pending.computeIfAbsent(anchorId, k -> new ArrayList<>())
                .add(new Entry(userId, type, key, value, embedText));
    }

    /**
     * 成功：本轮暂存条目 upsert 落库（参与外层事务，调用方需已在事务上下文），
     * 返回条目列表供事务提交后索引 Qdrant。
     */
    public List<Entry> commit(String anchorId) {
        List<Entry> entries = pending.get(anchorId);
        if (entries == null || entries.isEmpty()) return List.of();
        for (Entry e : entries) {
            dao.set(e.userId(), e.type(), e.key(), e.value());
        }
        return entries;
    }

    /** 失败/取消/终态清理：丢弃本轮未落库的暂存，纯内存操作、非阻塞 IO。 */
    public void discard(String anchorId) {
        pending.remove(anchorId);
    }

    /** 一条暂存的 Hot Memory 条目——{@code embedText} 供索引用，可与 {@code value}（存储值）不同（如 user-impression）。 */
    public record Entry(String userId, String type, String key, String value, String embedText) {
    }
}
