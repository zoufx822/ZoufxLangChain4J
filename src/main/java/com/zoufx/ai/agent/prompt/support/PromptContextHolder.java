package com.zoufx.ai.agent.prompt.support;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 请求级 prompt 上下文暂存，按 anchorId 键控。
 *
 * <p>{@code ChatService.prepare()} 在 boundedElastic 上完成所有 DB 查询后一次性
 * {@link #set}；{@code PromptComposer.compose()} 在 event loop 上 {@link #get}（纯内存读）；
 * 流终态统一 {@link #remove}。同 anchorId double-send 接受"最后写赢"，不做请求级隔离。
 *
 * <p>召回结果与其余 prompt 数据（soul / 印象 / 锚点）合并为单一入口，compose 不再逐项访问 DB。
 */
@Component
public class PromptContextHolder {

    private final ConcurrentMap<String, PromptContext> byAnchor = new ConcurrentHashMap<>();

    public void set(String anchorId, PromptContext ctx) {
        byAnchor.put(anchorId, ctx);
    }

    @Nullable
    public PromptContext get(String anchorId) {
        return byAnchor.get(anchorId);
    }

    public void remove(String anchorId) {
        byAnchor.remove(anchorId);
    }
}
