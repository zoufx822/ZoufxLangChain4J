package com.zoufx.ai.agent.memory.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级写守卫：防止 LLM 重试时 LC4J 重复写入 UserMessage。
 *
 * <p>LC4J 在 {@code .start()} 内部将 UserMessage 写入 ChatMemory 后才调 LLM；
 * 首 token 前重试 = 重新调 {@code .start()} = 再写一次 UserMessage，导致历史出现连续重复条目。
 * 本类用 requestId（每次 {@code ChatService.prepare()} 生成）标记"本次请求的 UserMessage
 * 是否已写"，{@code ChatMemoryDaoImpl.saveByAnchorId} 通过 {@link #tryClaimFirstWrite}
 * 区分首次写（放行）与重试写（触发去重）。
 *
 * <p>{@code ChatService.prepare()} 调 {@link #begin}；流终态 {@link #end}。
 */
@Component
public class ChatRequestRegistry {

    /** anchorId → 当前请求的 requestId。 */
    private final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();
    /** "anchorId:requestId" → UserMessage 已写标记。 */
    private final ConcurrentHashMap<String, Boolean> written = new ConcurrentHashMap<>();

    public void begin(String anchorId, String requestId) {
        active.put(anchorId, requestId);
    }

    public void end(String anchorId) {
        String rid = active.remove(anchorId);
        if (rid != null) written.remove(anchorId + ":" + rid);
    }

    /**
     * 尝试声明本请求的 UserMessage 首次写入权。
     *
     * <p>利用 {@code putIfAbsent} CAS 保证线程安全：
     *
     * @return {@code true} = 首次写，放行；{@code false} = 已写过，触发去重
     */
    public boolean tryClaimFirstWrite(String anchorId) {
        String rid = active.get(anchorId);
        if (rid == null) return true;
        return written.putIfAbsent(anchorId + ":" + rid, Boolean.TRUE) == null;
    }
}
