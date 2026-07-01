package com.zoufx.ai.agent.chat.service;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.chat.model.AnchorContextView;
import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 锚点服务——承载对话锚点的生命周期业务：
 * <ul>
 *   <li>{@link #openTurn}：开启新一轮（压上一锚点 + 解析/懒建本次锚点），供 ChatService 调用；</li>
 *   <li>{@link #compressAsync}：用同步 ChatModel 把消息流压成短摘要写回 anchor.summary 缓存；</li>
 *   <li>{@link #anchorContextAsync}：其他锚点三层衰减视图（near/mid/far）的组装。</li>
 * </ul>
 *
 * <p>压缩以 fire-and-forget 形式 subscribe()，失败仅记日志，不阻断主聊天流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnchorService {

    /** 摘要 prompt 模板——%s 处填入格式化好的对话文本。 */
    private static final String SUMMARY_PROMPT_TEMPLATE = """
            请对下面这段对话做一个简洁的中文摘要（200 字以内），用客观第三人称叙述。
            摘要要捕捉：双方主要谈论的话题、达成的关键结论或承诺、对方在此次对话中表现出的状态或情绪。
            不要使用"你/我"，统一用"对方"指代用户、"AI"指代助手。
            只输出摘要正文本身，不要前缀（如"摘要："）也不要包裹引号。

            对话内容：
            ---
            %s
            ---
            """;

    /** 摘要超过此字符数被截断——防止个别极端 LLM 输出撑爆 prompt 注入预算。 */
    private static final int SUMMARY_MAX_CHARS = 400;

    @Qualifier("syncChatModel")
    private final ChatModel chatModel;
    private final ChatMemoryDao chatMemoryDao;
    private final AnchorMemoryDao anchorMemoryDao;

    /**
     * 开启新一轮对话的锚点处理，返回本次要用的 anchorId：
     * 压缩上一锚点（旁路 fire-and-forget，不阻塞主流程）+ 解析/懒建本次锚点。
     * 供 ChatService 在准备阶段调用；须在非 event loop 线程上跑（内含阻塞建锚）。
     */
    public String openTurn(@Nullable String anchorId, @Nullable String prevAnchorId, String userId) {
        if (prevAnchorId != null && !prevAnchorId.isBlank()) {
            log.info("Anchor switch detected, compressing prevAnchorId={}", prevAnchorId);
            compressAsync(prevAnchorId).subscribe();
        }
        return resolveOrCreate(anchorId, userId);
    }

    public Mono<Void> compressAsync(String anchorId) {
        return Blocking.run(() -> compress(anchorId))
                .onErrorResume(err -> {
                    log.warn("Anchor compression failed [anchorId={}]: {}", anchorId, err.toString());
                    return Mono.empty();
                });
    }

    /**
     * 压缩指定锚点的消息流为短摘要，写入 anchor.summary。
     * 流水线是顺序阻塞 IO（DB 读 → LLM → DB CAS 写），调用方需保证不在 event loop 上。
     */
    private void compress(String anchorId) {
        Long snapshotAt = anchorMemoryDao.snapshotActiveAt(anchorId);
        if (snapshotAt == null) {
            log.info("Skip compression [anchorId={}]: anchor not found", anchorId);
            return;
        }
        String transcript = formatTranscript(chatMemoryDao.loadByAnchorId(anchorId));
        if (transcript.isBlank()) {
            log.info("Skip compression [anchorId={}]: empty transcript", anchorId);
            return;
        }
        String summary = callLLM(transcript);
        anchorMemoryDao.updateSummaryIfUnchanged(anchorId, summary, snapshotAt);
        log.info("Anchor summary saved [anchorId={}] len={}", anchorId, summary.length());
    }

    public Mono<AnchorContextView> anchorContextAsync(String anchorId) {
        return Blocking.call(() -> anchorContext(anchorId));
    }

    /**
     * 当前锚点的"其他锚点"三层衰减视图（near/mid/far）。
     * anchorId 不存在返回空结构，让前端统一走"这是我们的第一次对话"空态。
     */
    private AnchorContextView anchorContext(String anchorId) {
        String userId = anchorMemoryDao.findUserId(anchorId);
        if (userId == null) return AnchorContextView.empty();
        return AnchorContextView.from(anchorMemoryDao.listOtherAnchors(userId, anchorId));
    }

    /** 解析本次锚点：为空则懒建新锚返回新 id，非空原样返回。建锚失败为硬错误，直接抛出。 */
    private String resolveOrCreate(@Nullable String anchorId, String userId) {
        return anchorId != null ? anchorId : anchorMemoryDao.create(userId);
    }

    private String callLLM(String transcript) {
        String prompt = String.format(SUMMARY_PROMPT_TEMPLATE, transcript);
        String raw = chatModel.chat(UserMessage.from(prompt)).aiMessage().text();
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.length() <= SUMMARY_MAX_CHARS ? trimmed : trimmed.substring(0, SUMMARY_MAX_CHARS);
    }

    /**
     * 把消息流格式化为「对方: ... / AI: ...」纯文本。
     * 跳过 system / tool 类消息——摘要场景只关心双方对话内容。
     */
    private String formatTranscript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage u) {
                sb.append("对方: ").append(u.singleText()).append("\n");
            } else if (m instanceof AiMessage a) {
                String text = a.text();
                if (text == null || text.isBlank()) continue;
                sb.append("AI: ").append(text).append("\n");
            }
        }
        return sb.toString();
    }
}
