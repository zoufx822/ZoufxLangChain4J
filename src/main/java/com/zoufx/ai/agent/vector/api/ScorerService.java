package com.zoufx.ai.agent.vector.api;

import org.jspecify.annotations.Nullable;

/**
 * 记忆重要性评分——写入向量时算一次，随 payload 落库，参与召回三维加权。
 *
 * <p>接口式：当前用规则评分（{@code ScorerServiceImpl}），便于日后切 LLM 评分版而不动调用方。
 */
public interface ScorerService {

    /**
     * @param memType  cold / significant-event / commitment / user-impression（决定基础分）
     * @param role     cold 消息的 'user' / 'assistant'；hot 类传 null
     * @param content  记忆文本（用于长度 / 偏好信号 / 称呼加分）
     * @param username 已知称呼，用于"提及称呼"加分；不便获取时传 null
     * @return [0, 1]
     */
    double score(String memType, @Nullable String role, String content, @Nullable String username);
}
