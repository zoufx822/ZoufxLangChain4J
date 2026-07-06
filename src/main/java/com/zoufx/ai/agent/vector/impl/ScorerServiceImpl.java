package com.zoufx.ai.agent.vector.impl;

import com.zoufx.ai.agent.vector.api.ScorerService;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则评分——不跑 LLM，按消息来源 / 长度 / 偏好信号 / 是否提及称呼加权。
 *
 * <p>基础分：significant-event / commitment = 0.7（本就是重要记忆）；user-impression = 0.6；
 * cold 按 user=0.5 / assistant=0.3。最终 clamp 到 [0,1]。
 */
@Component
public class ScorerServiceImpl implements ScorerService {

    /** 偏好信号关键词；命中其一 +0.15。 */
    private static final List<String> PREFERENCE_SIGNALS =
            List.of("喜欢", "讨厌", "经常", "通常", "习惯", "一直");

    private static final int LENGTH_THRESHOLD = 50;

    @Override
    public double score(String memType, @Nullable String role, String content, @Nullable String username) {
        double s = baseScore(memType, role);
        if (content != null && !content.isBlank()) {
            if (content.length() > LENGTH_THRESHOLD) s += 0.1;
            for (String k : PREFERENCE_SIGNALS) {
                if (content.contains(k)) { s += 0.15; break; }
            }
            if (username != null && !username.isBlank() && content.contains(username)) s += 0.15;
        }
        return Math.max(0.0, Math.min(1.0, s));
    }

    private double baseScore(String memType, @Nullable String role) {
        if (VectorPayload.SIGNIFICANT_EVENT.equals(memType)
                || VectorPayload.COMMITMENT.equals(memType)) {
            return 0.7;
        }
        if (VectorPayload.USER_IMPRESSION.equals(memType)) {
            return 0.6;
        }
        // cold
        return "user".equals(role) ? 0.5 : 0.3;
    }
}
