package com.zoufx.ai.agent.memory.api;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * Hot Memory 存储契约——见到此人就立刻浮现、不需检索的常驻记忆。
 *
 * <p>与 Memory Stream（Cold Archive）并行写入，不互相派生：Hot 由 LLM 通过 {@code @Tool} 主动晶化，
 * Cold 自动 append 每轮对话原文。
 *
 * <p>同步方法供已允许阻塞的调用方（Prompt / {@code @Tool} 线程 / boundedElastic）；
 * {@code snapshotAsync} 供 WebFlux Controller 反应式串接。
 */
public interface HotMemoryDao {

    /**
     * 同步一次性读取该 (userId, type) 下全部已写入的 key/value，按 {@code updated_at DESC} 有序
     * （最新在前）。返回有序 Map——append-only type（significant-event / commitment）的 key 是
     * 无时间语义的 UUID，调用方靠此顺序直接取「最近 N 条」而无需自行排序。
     */
    Map<String, String> snapshot(String userId, String type);

    Mono<Map<String, String>> snapshotAsync(String userId, String type);

    /**
     * 同步写入：UPSERT 语义，后写覆盖前写。调用方在 {@code @Tool} 线程上（允许阻塞）。
     */
    void set(String userId, String type, String key, String value);

    /**
     * 按 key 批量取 value——召回 hydration 用（Qdrant 只存指针，hot 正文回这里取）。
     * 同步签名：调用方 {@code RecallServiceImpl} 在 boundedElastic 上。
     */
    Map<String, String> fetchValues(String userId, String type, Collection<String> keys);
}
