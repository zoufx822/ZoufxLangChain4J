package com.zoufx.ai.agent.mood.service;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.mood.support.Moods;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 情绪服务——用户消息一到就并行起一次轻量同步 LLM 调用快速分类，
 * 抢在主推理流之前判出小Z 的"第一反应情绪"，让前端头像立刻变脸（开口前先把情绪挂脸上）。
 *
 * <p>判断带上本锚点最近的对话窗口：新消息放进上下文里理解（上一句还在倾诉、这句只回「嗯」时
 * 不会被孤立判成平静）。复用 {@code syncChatModel}（轻量同步档），整条调用包在 boundedElastic
 * 上，绝不碰 event loop。窗口加载与主流并行，不进主流关键路径，首次变脸仍早于主流 TTFT。
 *
 * <p>失败语义有别：模型有响应但输出杂乱 → {@link Moods#normalize} 回落「平静」；
 * 真异常/超时 → 返回 {@code Mono.empty()} 不发情绪事件，避免给出一张错误的"平静脸"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoodService {

    /** 注入 prompt 的最近对话条数上界——第一反应只取近因，过长既稀释判断又涨延迟。 */
    private static final int RECENT_CONTEXT_MESSAGES = 8;

    private static final String PROMPT_TEMPLATE = """
            你是「小Z」，正在和对方聊天。下面是你们最近的对话，以及对方刚发来的新消息。
            结合整段对话上下文，判断你此刻读到这条新消息的第一反应情绪，
            从下面 7 个词里精确选 1 个，只输出这个词本身，不要解释、不要标点、不要任何多余字符：
            平静 / 愉快 / 兴奋 / 难过 / 愤怒 / 好奇 / 困惑

            判断要点：
            - 对方倾诉负面经历 / 低落挫败 → 难过（与对方共情）
            - 对方分享好消息 / 取得进展 → 兴奋
            - 对方遭遇明显不公 / 被欺负伤害 → 愤怒（为对方义愤）
            - 对方抛出新颖问题 / 陌生领域 → 好奇
            - 对方表达模糊 / 信息冲突 / 要求再解释 → 困惑
            - 日常闲聊 / 信息陈述 / 拿不准 → **优先选择愉快**——氛围轻松时用愉快，只有当愉快明显不贴合（如对方在陈述负面事实、表达不满、情绪低落）时才选择平静

            注意：新消息要放进上下文里理解。比如对方上一句还在倾诉难过、这一句只回了「嗯」「然后呢」，
            那你的第一反应应延续上文的共情，而不是孤立地把这句判成平静。

            最近的对话：
            ---
            %s
            ---

            对方刚发来的新消息：
            ---
            %s
            ---
            """;

    @Qualifier("syncChatModel")
    private final ChatModel chatModel;

    /** 异常/超时吞掉为 empty，不发情绪事件、不影响主流。 */
    public Mono<String> classifyAsync(String userMessage, List<ChatMessage> history) {
        return Blocking.call(() -> classify(userMessage, history))
                .onErrorResume(err -> {
                    log.warn("Mood classification failed, skip instant mood: {}", err.toString());
                    return Mono.empty();
                });
    }

    /**
     * 结合本锚点最近窗口判出情绪词之一（阻塞 LLM 调用，调用方需保证不在 event loop 上）。
     *
     * @param history 本锚点对话滑窗（与主 LLM 同源）；可空/可含末尾的当轮消息，内部自行处理
     */
    private String classify(String userMessage, List<ChatMessage> history) {
        String context = formatRecentContext(history, userMessage);
        String contextBlock = context.isBlank() ? "（这是你们对话的开始，暂无历史）" : context;
        String raw = chatModel.chat(UserMessage.from(
                        String.format(PROMPT_TEMPLATE, contextBlock, userMessage)))
                .aiMessage().text();
        String mood = Moods.normalize(raw);
        log.info("Mood classified instant={} (raw={})", mood, raw == null ? "" : raw.trim());
        return mood;
    }

    /**
     * 取窗口末尾 {@link #RECENT_CONTEXT_MESSAGES} 条渲染成「对方: … / 你: …」（小Z 第一人称）。
     * LC4J 在调 LLM 前即把当轮消息写进窗口，故剥掉末尾与当轮相同的 UserMessage，避免上下文里重复露出。
     * 跳过 system / tool 类消息——第一反应只看双方对话内容。
     */
    private String formatRecentContext(List<ChatMessage> history, String currentMessage) {
        if (history == null || history.isEmpty()) return "";
        List<ChatMessage> trimmed = new ArrayList<>(history);
        int last = trimmed.size() - 1;
        if (trimmed.get(last) instanceof UserMessage u && currentMessage.equals(u.singleText())) {
            trimmed.remove(last);
        }
        int from = Math.max(0, trimmed.size() - RECENT_CONTEXT_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : trimmed.subList(from, trimmed.size())) {
            if (m instanceof UserMessage u) {
                sb.append("对方: ").append(u.singleText()).append("\n");
            } else if (m instanceof AiMessage a) {
                String text = a.text();
                if (text == null || text.isBlank()) continue;
                sb.append("你: ").append(text).append("\n");
            }
        }
        return sb.toString();
    }
}
