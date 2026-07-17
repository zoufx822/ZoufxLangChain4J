package com.zoufx.ai.agent.mood.support;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ⟦mood:KEYWORD⟧} 标记格式的单一事实源——正则定义与校验只在此处出现，
 * {@link MoodEventProcessor}（流式增量剥离）与 {@code ChatMemoryDaoImpl}（落库前剥离）共用，
 * 避免两套解析各自维护、语义漂移。
 */
public final class MoodTags {

    /** 专用情绪标记定界符（U+27E6/U+27E7 数学白方括号，正文/代码/markdown/HTML 里都不出现）。 */
    public static final char OPEN = '⟦';
    public static final char CLOSE = '⟧';

    /** 匹配 ⟦任意非⟧字符⟧ 整个标记。 */
    public static final Pattern TAG = Pattern.compile(OPEN + "([^" + CLOSE + "]*)" + CLOSE);
    /** 标记内部须形如 mood:KEYWORD。 */
    private static final Pattern INNER = Pattern.compile("^\\s*mood:\\s*(.+?)\\s*$");

    private MoodTags() {}

    /** 校验并提取标记内部（{@link #TAG} group(1)）的合法 mood 关键词；非法/畸形返回 null。 */
    public static @Nullable String parseValid(String inner) {
        Matcher m = INNER.matcher(inner);
        return (m.matches() && Moods.isValid(m.group(1).trim())) ? m.group(1).trim() : null;
    }

    /** 提取文本内全部合法 mood 关键词，按出现顺序；非法/畸形标记跳过。 */
    public static List<String> extractValid(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        Matcher t = TAG.matcher(text);
        while (t.find()) {
            String mood = parseValid(t.group(1));
            if (mood != null) result.add(mood);
        }
        return result;
    }

    /** 剥离文本内全部 {@code ⟦...⟧} 标记（不论内部是否合法），返回干净文本。 */
    public static @Nullable String stripAll(@Nullable String text) {
        return text == null ? null : TAG.matcher(text).replaceAll("");
    }
}
