package com.zoufx.ai.agent.chat.support;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 在建轮注册表——consumeStream 下生成脱离连接后，这里是唯一能定位一轮的地方。
 *
 * <p>承担两职：① stop 端点按 {@code turnId} 定位并掐断；② {@code GET pending} 按 {@code anchorId}
 * 反查暴露在建轮的问题（write-back 下生成期间什么都没落库，只能问这里）。任何终态（完成/停止/出错）
 * 由管道 doFinally 移除，防泄漏。
 *
 * <p>只按 {@link TurnHandle} 存取——登记的实际是 {@link Turn} 本体，但收窄成接口，
 * 挡掉 assistant/inlineMoods/userEmbedding 等生成期内部状态对外部读者的暴露。
 */
@Component
public class TurnRegistry {

    private final ConcurrentHashMap<String, TurnHandle> turns = new ConcurrentHashMap<>();

    /** 开一轮：登记（供 ChatService 挂 backendSub）。 */
    public void register(TurnHandle turn) {
        turns.put(turn.turnId(), turn);
    }

    public void remove(String turnId) {
        turns.remove(turnId);
    }

    public @Nullable TurnHandle get(String turnId) {
        return turns.get(turnId);
    }

    /**
     * 按锚点反查在建轮——供 {@code GET pending}。同锚同刻至多一轮（前端串行化保证），命中即返回。
     */
    public @Nullable TurnHandle findByAnchor(String anchorId) {
        for (TurnHandle t : turns.values()) {
            if (t.anchorId().equals(anchorId)) return t;
        }
        return null;
    }
}
