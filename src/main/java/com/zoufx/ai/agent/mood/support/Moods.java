package com.zoufx.ai.agent.mood.support;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 情绪词谱的单一事实源——情绪标记（{@code ⟦mood:X⟧}）校验与提示词共用，与前端 eyes.tsx 的表情预设一一对应。
 * 扩词必须同步前端预设，故集合在此收口。
 */
public final class Moods {

    public static final List<String> ALL = List.of("平静", "愉快", "兴奋", "难过", "愤怒", "好奇", "困惑");

    private static final Set<String> SET = Set.copyOf(ALL);

    private Moods() {}

    public static boolean isValid(@Nullable String s) {
        return s != null && SET.contains(s.trim());
    }
}
