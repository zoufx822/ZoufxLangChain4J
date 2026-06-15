package com.zoufx.ai.agent.chat.service;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.chat.api.ChatAssistant;
import com.zoufx.ai.agent.chat.property.ChatProps;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import com.zoufx.ai.agent.memory.api.ColdMemoryDao;
import com.zoufx.ai.agent.chat.model.ChatEvent;
import com.zoufx.ai.agent.chat.model.ChatPrepared;
import com.zoufx.ai.agent.prompt.impl.RecallPieceImpl;
import com.zoufx.ai.agent.mood.support.MoodEventProcessor;
import com.zoufx.ai.agent.vector.support.RecallContextHolder;
import com.zoufx.ai.agent.chat.support.RetryableExceptions;
import com.zoufx.ai.agent.tool.support.WebSearchEvents;
import com.zoufx.ai.agent.vector.api.IndexerService;
import com.zoufx.ai.agent.vector.api.RecallService;
import com.zoufx.ai.agent.vector.model.RecallResult;
import com.zoufx.ai.agent.vector.property.RecallProps;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import com.zoufx.ai.agent.tool.support.ToolNameMap;
import com.zoufx.ai.agent.mood.service.MoodService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天编排服务——一个类里可读到完整对话生命周期：
 *
 * <pre>
 *   chat()
 *     ├── prepare()              boundedElastic 上同步准备：
 *     │     锚点懒创建 → 写 cold_memory → embed → 召回 → 索引
 *     └── buildStream()          Flux 主体
 *           ├── instant 支        情绪快速分类（并发，辅助能力）
 *           ├── main 支           LC4J TokenStream → FluxSink，带重试
 *           ├── doOnNext          收集 assistant 全文
 *           ├── onErrorResume     错误兜底成 error 事件（保持 SSE 流不断）
 *           ├── doOnComplete      触发 onStreamComplete（touch + title + 持久化 + 索引）
 *           ├── doOnCancel        客户端断开时清理孤儿消息
 *           └── doFinally         终态（complete/error/cancel）统一清理召回上下文
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 自动 backfill title 时取首条用户消息的最大字符数。 */
    private static final int AUTO_TITLE_MAX_LEN = 20;

    /** 思考档（如 DeepSeek pro+max / MiniMax adaptive）：前端开启思考时的对话主路。 */
    @Qualifier("thinkingAssistant")
    private final ChatAssistant thinkingAssistant;
    /** 快档（thinking 关闭）：前端关闭思考时的对话主路。 */
    @Qualifier("fastAssistant")
    private final ChatAssistant fastAssistant;
    /** LC4J AiServices 管理的会话消息历史（按 anchorId 分桶）。 */
    private final ChatMemoryDao chatMemoryDao;
    /** 锚点（对话会话）的 CRUD——创建、touch、title backfill。 */
    private final AnchorMemoryDao anchorMemoryDao;
    /** 长期对话原文归档，按 userId 顺序追加；向量索引的数据源。 */
    private final ColdMemoryDao coldMemoryDao;
    private final MoodService moodService;
    private final ChatProps chatProps;
    /** 所有 @Tool 实现类，启动时反射扫描方法名 → section（中文名）映射。 */
    private final RecallService recallService;
    /**
     * 跨方法传递召回结果的 anchorId 级别 holder。
     * prepare() 写入，SystemPromptProvider.compose() 读取，流结束/取消时清理。
     */
    private final RecallContextHolder recallContextHolder;
    private final IndexerService indexer;
    private final EmbeddingModel embeddingModel;
    private final RecallProps recallProps;
    private final ToolNameMap toolNameMap;

    /**
     * 完整对话流程：prepare（锚点创建 + 召回准备）→ LLM 流式对话 → 流结束后处理。
     *
     * <p>新对话（{@code anchorId == null}）时自动创建锚点，{@code anchor_created} 作为首条事件发出，
     * 前端收到后更新 URL 中的 anchorId。
     */
    public Flux<ChatEvent> chat(@Nullable String anchorId, String prompt, boolean thinking, String userId) {
        // 前端思考开关直接映射模型档位：开 → 思考档、关 → 快档（档位内 thinking 策略 builder 期固定）
        ChatAssistant assistant = thinking ? thinkingAssistant : fastAssistant;
        // prepare 含同步 DB + embedding 操作，必须离开 event loop
        return Blocking.call(() -> prepare(userId, anchorId, prompt))
                .flatMapMany(p -> buildStream(assistant, p, userId, prompt))
                // 兜底 prepare 阶段的硬错误（如锚点创建失败）——buildStream 内部的 onErrorResume 罩不到这里；
                // 不转 error 事件的话 SSE 会以原始异常断连，前端只能看到连接错误
                .onErrorResume(err -> {
                    log.error("Chat prepare failed [userId={}]", userId, err);
                    return Flux.just(new ChatEvent("error", "会话初始化失败，请稍后重试"));
                });
    }

    /**
     * 在 {@code assistant.chat()} 启动前同步完成，返回解析后的锚点信息。
     *
     * <p>锚点创建在 try 外——失败是硬错误，直接传播；embed / 召回 / 索引失败吞掉（辅助能力，不阻断对话）。
     */
    private ChatPrepared prepare(String userId, @Nullable String anchorId, String prompt) {
        boolean newAnchor = anchorId == null;
        if (newAnchor) {
            anchorId = anchorMemoryDao.create(userId);
        }
        try {
            // 1. 用户消息原文持久化，拿行 id 作向量指针
            long coldUserId = coldMemoryDao.append(userId, "user", prompt, null, null);

            // 2. prompt 向量化：召回 query + 索引复用同一份，避免重复嵌入
            Embedding emb = embeddingModel.embed(prompt).content();

            // 3. 语义召回注入 holder，SystemPromptProvider.compose() 会在 LC4J 构建 prompt 时同步读取
            Long windowSince = coldMemoryDao.windowLowerBound(userId, chatProps.getLoadMessage());
            List<RecallResult> recalled = recallService.recall(userId, emb, recallProps.getLimit(), windowSince);
            recallContextHolder.set(anchorId, RecallPieceImpl.format(recalled));

            // 4. fire-and-forget 索引（先召回后索引，避免本次消息把自己召回）
            indexer.indexAsync(userId, VectorPayload.COLD, String.valueOf(coldUserId),
                    prompt, "user", System.currentTimeMillis(), emb).subscribe();
        } catch (Exception e) {
            log.warn("Prepare failed, skip auto-association [anchorId={}]: {}", anchorId, e.toString());
        }
        return new ChatPrepared(anchorId, newAnchor);
    }

    /**
     * 组装 Flux 管道：instant（情绪分类）和 main（LLM 主流）并发 merge，
     * 再套上全文收集 / 错误兜底 / 完成钩子 / 取消清理；新锚点时把 anchor_created 置为首条事件。
     *
     * <p>instant 支失败静默不发射（情绪是辅助能力）；main 支重试仅限首次 emit 前。
     */
    private Flux<ChatEvent> buildStream(ChatAssistant assistant, ChatPrepared prepared, String userId, String prompt) {
        String anchorId = prepared.anchorId();
        // instant / main 两支并发写同一批状态变量，必须用线程安全类型
        AtomicBoolean hasEmitted = new AtomicBoolean(false);   // 重试守门：收到首条 token 后禁止再重试
        StringBuilder assistantBuffer = new StringBuilder();   // 收集 content 事件全文，流结束后持久化
        AtomicReference<String> instantMood = new AtomicReference<>(); // instant 支写入，onStreamComplete 合并
        List<String> inlineMoods = new CopyOnWriteArrayList<>();       // main 支写入，onStreamComplete 合并

        // 情绪快速分类支路：拉对话历史 → LLM 分类关键词 → mood 事件；与 main 支并发，通常先于首条 content 到达
        Flux<ChatEvent> instant = chatMemoryDao.loadByAnchorIdAsync(anchorId)
                .flatMap(history -> moodService.classifyAsync(prompt, history))
                .doOnNext(instantMood::set)
                .map(kw -> new ChatEvent("mood", MoodEventProcessor.moodPayload(kw)))
                .onErrorResume(err -> {
                    log.warn("Instant mood branch failed, skip [anchorId={}]: {}", anchorId, err.toString());
                    return Mono.empty();
                })
                .flux();

        // LLM 主流支路：subscribeOn 固定 boundedElastic（retryWhen 默认走 parallel，需显式指定才能继承）
        Flux<ChatEvent> main = Flux.<ChatEvent>create(sink ->
                        startTokenStream(sink, assistant, anchorId, userId, prompt, hasEmitted, inlineMoods))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(buildRetrySpec(hasEmitted));

        Flux<ChatEvent> stream = Flux.merge(instant, main)
                // 实时收集 content token，流结束后一次性持久化（避免中途断开导致内容丢失）
                .doOnNext(event -> {
                    if ("content".equals(event.type())) {
                        assistantBuffer.append(event.data());
                    }
                })
                // 错误转 error 事件：保持 SSE 连接不断，前端能收到提示而非看到连接异常
                .onErrorResume(err -> {
                    log.error("Stream error [anchorId={}, userId={}]", anchorId, userId, err);
                    String msg = err.getMessage() != null ? err.getMessage() : "AI 服务异常，请稍后重试";
                    return Flux.just(new ChatEvent("error", msg));
                })
                // 流正常完成：触发 touch / title / cold_memory / 向量索引等持久化副作用
                .doOnComplete(() -> onStreamComplete(anchorId, userId, prompt, assistantBuffer, instantMood.get(), inlineMoods))
                // 客户端断开（关页面 / 取消请求）：清理孤儿消息
                .doOnCancel(() -> {
                    log.info("Stream cancelled [anchorId={}, userId={}]", anchorId, userId);
                    chatMemoryDao.cleanupOrphansAsync(anchorId)
                            .onErrorResume(err -> {
                                log.warn("Post-cancel sanitize failed [anchorId={}]: {}", anchorId, err.toString());
                                return Mono.empty();
                            })
                            .subscribe();
                })
                // 召回 holder 统一在终态清理（complete / error / cancel 全覆盖）；
                // 不能在 onError 回调里提前 remove——重试会重新 compose system prompt，召回段必须还在
                .doFinally(signal -> recallContextHolder.remove(anchorId));

        // anchor_created 必须是首条事件（前端靠它更新 URL 中的 anchorId）；concatWith 保证 stream 在其后发射
        return prepared.newAnchor()
                ? Flux.just(new ChatEvent("anchor_created", anchorId)).concatWith(stream)
                : stream;
    }

    /**
     * 把 LC4J TokenStream 的回调桥接为 {@link FluxSink} 事件流。
     *
     * <p>LC4J 回调跑在框架线程，与 event loop 隔离；{@code hasEmitted} 首次回调时置位，供重试策略判断。
     */
    private void startTokenStream(FluxSink<ChatEvent> sink, ChatAssistant assistant,
                                  String anchorId, String userId, String prompt,
                                  AtomicBoolean hasEmitted, List<String> inlineMoods) {
        // MoodEventProcessor：从 content token 流中剥离 LLM 内嵌的情绪标签，剩余文本作为正常 content 事件推给 sink
        final MoodEventProcessor moodStripper = new MoodEventProcessor(sink, userId);

        assistant.chat(anchorId, prompt)
                // 思考过程 token（仅支持 thinking 的 profile 会触发，pt 可能为 null）
                .onPartialThinking(pt -> {
                    hasEmitted.set(true);
                    if (pt != null && pt.text() != null) {
                        sink.next(new ChatEvent("thinking", pt.text()));
                    }
                })
                // 正文 token：交给 moodStripper 剥离情绪标签后转 content 事件，识别到的情绪词缓存在 stripper 内部
                .onPartialResponse(ct -> {
                    hasEmitted.set(true);
                    moodStripper.accept(ct);
                })
                // 工具调用开始：提取查询词 + 中文名，发 tool_call 事件让前端展示工具卡片
                .beforeToolExecution(evt -> {
                    hasEmitted.set(true);
                    String name = evt.request().name();
                    String query = WebSearchEvents.extractQuery(evt.request().arguments());
                    String chineseName = toolNameMap.toolName(name);
                    log.info("Tool call start [anchorId={}] {} ({}) query={}", anchorId, name, chineseName, query);
                    sink.next(new ChatEvent("tool_call", WebSearchEvents.toolCallPayload(name, chineseName, query)));
                })
                // 工具调用结束：统计结果条数，发 tool_result 事件（结果原文由前端折叠展示）
                .onToolExecuted(exec -> {
                    hasEmitted.set(true);
                    String name = exec.request().name();
                    String result = exec.result();
                    String chineseName = toolNameMap.toolName(name);
                    int count = WebSearchEvents.countResults(result);
                    log.info("Tool call done [anchorId={}] {} ({}) count={}", anchorId, name, chineseName, count);
                    sink.next(new ChatEvent("tool_result", WebSearchEvents.toolResultPayload(name, chineseName, count, result)));
                })
                // LLM 出错：向上传播触发外层 retryWhen 判断（召回 holder 留给外层 doFinally 统一清理）
                .onError(sink::error)
                // 流正常结束：flush 处理末尾残留的未完整情绪标签，导出情绪列表供 onStreamComplete 持久化
                .onCompleteResponse(r -> {
                    log.info("Stream completed [anchorId={}]", anchorId);
                    moodStripper.flush();
                    inlineMoods.addAll(moodStripper.getMoods());
                    sink.complete();
                })
                .start();
    }

    /**
     * 流完成后异步钩子：touch + title backfill + assistant 持久化 + 索引。
     *
     * <p>情绪轨迹：instant（对话开始时的快速分类）置首，inline（LLM 正文内嵌标签）依次追加；
     * {@code settledMood} 取最后一个写 anchor.last_mood，{@code moodTrail} 存完整轨迹写 cold_memory.mood。
     *
     * <p>全部 fire-and-forget，失败仅记日志——副作用不应影响已返回给用户的对话内容。
     */
    private void onStreamComplete(String anchorId, String userId, String prompt, StringBuilder buffer,
                                  @Nullable String instantMood, List<String> inlineMoods) {
        // 合并情绪轨迹：instant 置首（对话开始前的快速分类），inline 依次追加（LLM 正文中识别到的变化）
        List<String> moods = new ArrayList<>();
        if (instantMood != null && !instantMood.isBlank()) moods.add(instantMood);
        moods.addAll(inlineMoods);
        String settledMood = moods.isEmpty() ? null : moods.get(moods.size() - 1);
        String moodTrail = moods.isEmpty() ? null : String.join(",", moods);

        // 1. touch 锚点：更新 last_active 时间戳（供会话列表排序）+ 写入最终情绪
        anchorMemoryDao.touchAsync(anchorId, settledMood)
                .onErrorResume(err -> {
                    log.warn("Failed to touch anchor [anchorId={}]: {}", anchorId, err.toString());
                    return Mono.empty();
                })
                .subscribe();

        // 2. 自动补标题：取首条用户消息截断值，仅当标题为空时写入（不覆盖用户手动改名）
        String autoTitle = truncate(prompt, AUTO_TITLE_MAX_LEN);
        if (!autoTitle.isBlank()) {
            anchorMemoryDao.updateTitleIfBlankAsync(anchorId, autoTitle)
                    .onErrorResume(err -> {
                        log.warn("Failed to backfill anchor title [anchorId={}]: {}", anchorId, err.toString());
                        return Mono.empty();
                    })
                    .subscribe();
        }

        if (buffer.length() == 0) {
            // 3a. LLM 无产出（网络中断等）→ 清理 LC4J 已提前写入的孤儿 UserMessage
            // LC4J AiServices 在调用 LLM 前就把 UserMessage 写入 ChatMemoryDao，
            // 若 LLM 未返回任何内容则会话历史中留下"有问无答"的孤儿，需主动清理
            chatMemoryDao.removeLastOrphanUserMessageAsync(anchorId)
                    .onErrorResume(err -> {
                        log.warn("Failed to remove orphan user message [anchorId={}]: {}", anchorId, err.toString());
                        return Mono.empty();
                    })
                    .subscribe();
        }

        if (buffer.length() > 0) {
            // 3b. 持久化 assistant 原文：先 append 拿行 id 作向量 docId，再异步嵌入+索引
            String assistantText = buffer.toString();
            Blocking.call(() -> coldMemoryDao.append(userId, "assistant", assistantText, null, moodTrail))
                    .flatMap(id -> indexer.indexTextAsync(userId, VectorPayload.COLD, String.valueOf(id),
                            assistantText, "assistant", System.currentTimeMillis()))
                    .onErrorResume(err -> {
                        log.warn("Failed to append/index assistant message to cold_memory [userId={}]: {}",
                                userId, err.toString());
                        return Mono.empty();
                    })
                    .subscribe();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 指数退避重试策略——仅首次 emit 前对可重试错误生效。
     * {@code hasEmitted} 由 startTokenStream 任一回调置位，避免流开始后重试（已部分发送的内容无法回滚）。
     */
    private Retry buildRetrySpec(AtomicBoolean hasEmitted) {
        ChatProps.Retry r = chatProps.getRetry();
        return Retry.backoff(r.getMaxAttempts(), r.getMinBackoff())
                .maxBackoff(r.getMaxBackoff())
                .filter(err -> !hasEmitted.get() && RetryableExceptions.isRetryable(err))
                .doBeforeRetry(rs -> log.warn("LLM retry #{} cause={}",
                        rs.totalRetries() + 1, rs.failure().toString()));
    }

}
