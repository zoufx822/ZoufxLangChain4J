package com.zoufx.ai.agent.mood.support;

import com.zoufx.ai.agent.base.support.JsonStrings;

import com.zoufx.ai.agent.chat.model.ChatEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 有状态的情绪事件处理器——从 LLM content 流里剥离专用情绪标记 {@code ⟦mood:KEYWORD⟧}。
 *
 * <p>专用 sentinel（U+27E6/U+27E7 数学白方括号）正文/代码/markdown/HTML 里都不出现，剥离规则
 * "见到 {@code ⟦...⟧} 就整段删"而零误伤；内部再校验是否 {@code mood:合法词}——是则发独立 SSE mood
 * 事件，否则（畸形）静默丢弃。无论 LLM 怎么填错，标记都不泄漏到用户屏幕。
 *
 * <p>跨 chunk：{@code ⟦} 是单字符，一旦出现就把它及之后的内容留在 buffer 等 {@code ⟧} 闭合，
 * 不 emit 越过未闭合的 {@code ⟦}。流末 {@link #flush()} 若仍有未闭合 {@code ⟦} 则丢弃其尾部。
 *
 * <p>一次性使用：每条 chat 请求 new 一个实例。线程模型：accept / flush 在 LC4J 单 token 回调线程串行调用。
 */
@Slf4j
public class MoodEventProcessor {

    /** 整轮零 mood 时的兜底词——与 prompt「拿不准→愉快」默认规则对齐（须是 {@link Moods} 合法词）。 */
    private static final String FALLBACK_MOOD = "愉快";

    private final FluxSink<ChatEvent> sink;
    private final String userId;
    private final StringBuilder buffer = new StringBuilder();
    /** 本轮正文里依次输出的所有合法 mood 关键词（可 0~N 个）；流末由 ChatService 取出落库。 */
    private final List<String> moods = new ArrayList<>();

    public MoodEventProcessor(FluxSink<ChatEvent> sink, String userId) {
        this.sink = sink;
        this.userId = userId;
    }

    /** 接收 LC4J 增量 token；可能 emit content + 0..N mood 事件。 */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        buffer.append(chunk);
        scanAndStrip();
        flushSafePrefix();
    }

    /**
     * 流末兜底：再扫一次 + flush 剩余；未闭合的 {@code ⟦} 尾部丢弃（畸形零泄漏）。
     * 收尾时若整轮一个合法 mood 都没打（「开头必打」是格式纪律型指令，实测约三成轮次被 LLM 忽略），
     * 补发一个默认「愉快」——保证每轮至少一次变脸，小Z 灵动的基线不塌。见 /test-prompt TP-31。
     */
    public void flush() {
        scanAndStrip();
        int open = buffer.indexOf(String.valueOf(MoodTags.OPEN));
        if (open >= 0) {
            if (open > 0) sink.next(new ChatEvent("content", buffer.substring(0, open)));
            log.info("Dropped unclosed mood sentinel tail [userId={}]", userId);
        } else if (buffer.length() > 0) {
            sink.next(new ChatEvent("content", buffer.toString()));
        }
        buffer.setLength(0);
        if (moods.isEmpty()) {
            sink.next(new ChatEvent("mood", moodPayload(FALLBACK_MOOD)));
            moods.add(FALLBACK_MOOD);
            log.info("No mood emitted this turn, injected fallback [userId={}] mood={}", userId, FALLBACK_MOOD);
        }
    }

    /** 剥离 buffer 内所有完整 {@code ⟦...⟧}：emit 命中前内容 + 删标记 + 校验内部发 mood 事件或静默丢弃。 */
    private void scanAndStrip() {
        while (true) {
            Matcher t = MoodTags.TAG.matcher(buffer);
            if (!t.find()) return;
            String before = buffer.substring(0, t.start());
            if (!before.isEmpty()) {
                sink.next(new ChatEvent("content", before));
            }
            String inner = t.group(1);
            buffer.delete(0, t.end());

            String mood = MoodTags.parseValid(inner);
            if (mood != null) {
                sink.next(new ChatEvent("mood", moodPayload(mood)));
                moods.add(mood);
                log.info("Mood stripped [userId={}] mood={}", userId, mood);
            } else {
                log.info("Dropped malformed mood tag [userId={}] inner='{}'", userId, inner.trim());
            }
        }
    }

    /** 流式途中：不 emit 越过未闭合的 {@code ⟦}（此时 buffer 里已无完整标记，任何 ⟦ 都是未闭合的）。 */
    private void flushSafePrefix() {
        int open = buffer.indexOf(String.valueOf(MoodTags.OPEN));
        int safeLen = (open >= 0) ? open : buffer.length();
        if (safeLen <= 0) return;
        sink.next(new ChatEvent("content", buffer.substring(0, safeLen)));
        buffer.delete(0, safeLen);
    }

    /** 本轮正文里依次出现的所有 mood（按出现顺序）；无则空 list。 */
    public List<String> getMoods() {
        return moods;
    }

    /** 构造 mood 事件 JSON payload；对 keyword 做 JSON 转义。 */
    public static String moodPayload(String keyword) {
        return "{\"keyword\":\"" + JsonStrings.escape(keyword) + "\"}";
    }
}
