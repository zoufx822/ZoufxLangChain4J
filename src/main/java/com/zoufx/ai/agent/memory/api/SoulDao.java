package com.zoufx.ai.agent.memory.api;

import java.util.Map;

/**
 * SOUL 存储——AI 自身人格的全局单例存储（无 userId 维度）。
 *
 * <p>与 HotMemoryDao（按 userId 存储用户认知）对偶。每次请求开始时由
 * {@code ChatService.prepare()} 预加载 snapshot 存入 {@code PromptContext}，由 {@code PromptComposer} 注入 system prompt。
 * AI 不能通过工具自改人格；写入仅限 seed 初始化。
 */
public interface SoulDao {

    /** 同步一次性读取全部已写入的 key。compose 注入用。 */
    Map<String, String> snapshot();
}
