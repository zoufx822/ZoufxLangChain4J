package com.zoufx.ai.agent.prompt.impl;

import com.zoufx.ai.agent.prompt.api.Prompt;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 「## 关于你自己」段（order=10）——注入 AI 自身人格 / 风格 / 原则 / 反模式 / 一致性原则 / 小习惯。
 *
 * <p>渲染顺序硬编码：role → name/tone → principles → forbidden_patterns → consistency_principles → quirks，
 * 直接从 {@code ctx.soulSnap()} 取值，缺值字段自然跳过。
 */
@Component
public class SoulPromptImpl implements Prompt {

    @Override
    public int order() {
        return 10;
    }

    @Override
    @Nullable
    public String render(@Nullable PromptContext ctx) {
        Map<String, String> snap = ctx != null ? ctx.soulSnap() : Map.of();
        if (snap.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("## 关于你自己\n\n");
        String role = snap.get("role");
        if (role != null && !role.isBlank()) {
            sb.append("你是").append(role).append("。\n\n");
        }
        String name = snap.get("name");
        String tone = snap.get("tone");
        if (name != null && !name.isBlank()) {
            sb.append("你叫").append(name).append("。");
            if (tone != null && !tone.isBlank()) sb.append("你的说话风格是：").append(tone).append("。");
            sb.append("\n\n");
        } else if (tone != null && !tone.isBlank()) {
            sb.append("你的说话风格是：").append(tone).append("。\n\n");
        }
        appendBlock(sb, snap, "principles",             "你坚持以下表达原则：");
        appendBlock(sb, snap, "forbidden_patterns",     "你**绝不**使用以下表达：");
        appendBlock(sb, snap, "consistency_principles", "你坚持以下**一致性原则**：");
        appendBlock(sb, snap, "quirks",                 "你有以下小习惯（自然流露即可，不要刻意）：");
        int headerLen = "## 关于你自己\n\n".length();
        return sb.length() > headerLen ? sb.toString() : null;
    }

    /** value 可能是 multi-line（yml 用 |），原样保留缩进/列表符号注入 prompt。 */
    private void appendBlock(StringBuilder sb, Map<String, String> ordered, String key, String title) {
        String value = ordered.get(key);
        if (value == null || value.isBlank()) return;
        sb.append(title).append("\n").append(value);
        if (!value.endsWith("\n")) sb.append("\n");
        sb.append("\n");
    }
}
