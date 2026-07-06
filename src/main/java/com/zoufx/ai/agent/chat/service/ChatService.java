package com.zoufx.ai.agent.chat.service;

import com.zoufx.ai.agent.base.support.Blocking;
import com.zoufx.ai.agent.chat.api.StreamingChatPort;
import com.zoufx.ai.agent.chat.property.ChatProps;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.api.ColdMemoryDao;
import com.zoufx.ai.agent.memory.api.HotMemoryDao;
import com.zoufx.ai.agent.memory.api.SoulDao;
import com.zoufx.ai.agent.memory.service.ChatMemoryService;
import com.zoufx.ai.agent.memory.service.ColdMemoryService;
import com.zoufx.ai.agent.memory.service.HotMemoryService;
import com.zoufx.ai.agent.memory.support.HotMemoryType;
import com.zoufx.ai.agent.chat.model.ChatEvent;
import com.zoufx.ai.agent.chat.model.ChatPrepared;
import com.zoufx.ai.agent.chat.model.Thinking;
import com.zoufx.ai.agent.prompt.impl.RecallPromptImpl;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.prompt.support.PromptContextHolder;
import com.zoufx.ai.agent.mood.support.MoodEventProcessor;
import com.zoufx.ai.agent.chat.support.RetryableExceptions;
import com.zoufx.ai.agent.tool.support.WebSearchEvents;
import com.zoufx.ai.agent.vector.api.IndexerService;
import com.zoufx.ai.agent.vector.api.RecallService;
import com.zoufx.ai.agent.vector.model.RecallResult;
import com.zoufx.ai.agent.vector.property.RecallProps;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import com.zoufx.ai.agent.tool.support.ToolNameMap;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天编排服务——一个类里可读到完整对话生命周期：
 *
 * <pre>
 *   chat()
 *     ├── prepare()              boundedElastic 上同步准备：开一轮 + seed + embed + 召回 + PromptContext
 *     └── buildStream()          Flux 主体——单条 LLM 主流，挂五个生命周期钩子：
 *           Flux.create(bridgeTokenStream).subscribeOn(bE).retryWhen(...)   订阅侧落 boundedElastic
 *           .publishOn(boundedElastic)   下行信号搬到可阻塞线程，使 doOnComplete 能同步跑事务
 *           .doOnNext      收全文       turn::collectContent
 *           .doOnComplete  成功→落库     persistTurn
 *           .doOnCancel    取消→掐上游    cancelTurn
 *           .doFinally     终态→清理      releaseTurn（PromptContext + pending）
 *           .onErrorResume 失败→记日志+收尾成 error 帧   failureEvent
 * </pre>
 *
 * <p>情绪单一来源：主模型在正文里打 {@code ⟦mood:X⟧} 标记（开头必打第一反应 + 转折按需追加），
 * 由 {@code MoodEventProcessor} 剥离成独立 mood 事件——不再另起一次 LLM 快速分类支。
 *
 * <p>四个 doOn* 钩子处理正常生命周期；错误处理集中在末尾一个 onErrorResume（记日志 + 把错误收尾成
 * 客户端 error 帧）。「产出一个兜底事件」只能用恢复族、偷窥族 doOn* 干不了，故错误就一个出口、不硬拆两支。
 * publishOn 使 doOnComplete 里的阻塞事务可同步直调而不碰 event loop，且天然排在 doFinally 之前——
 * 故 pending 的 discard 统一在 releaseTurn 一处（成功轮必在 commit 之后）；失败/取消 persistTurn 不运行、零残留。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 自动 backfill title 时取首条用户消息的最大字符数。 */
    private static final int AUTO_TITLE_MAX_LEN = 20;

    /** 流式对话端口：按 thinking 开关路由到合适的模型/参数，屏蔽 profile 间的 per-call 能力差异。 */
    private final StreamingChatPort streamingChatPort;
    /** 会话窗口业务层——LC4J ChatMemoryStore 契约 + write-back 缓冲。 */
    private final ChatMemoryService chatMemoryService;
    /** 锚点只读——prepare 组装 PromptContext 用。 */
    private final AnchorMemoryDao anchorMemoryDao;
    /** 锚点生命周期业务——开一轮 + touch/title（同步，事务内）。 */
    private final AnchorService anchorService;
    /** 长期对话原文归档业务层——persistTurn 事务内 append。 */
    private final ColdMemoryService coldMemoryService;
    /** 长期对话原文归档纯落库——prepare 阶段 windowLowerBound 只读。 */
    private final ColdMemoryDao coldMemoryDao;
    /** AI 人格快照——每次 prepare() 预加载进 PromptContext。 */
    private final SoulDao soulDao;
    /** 用户画像存储——每次 prepare() 预加载 user-impression 快照进 PromptContext（只读）。 */
    private final HotMemoryDao hotMemoryDao;
    /** 印象/经历/承诺业务层——persistTurn 事务内 commit，失败/取消 discard。 */
    private final HotMemoryService hotMemoryService;
    private final ChatProps chatProps;
    private final RecallService recallService;
    /** 请求级 prompt 数据暂存——compose() 在 event loop 上纯内存读，不再访问 DB。 */
    private final PromptContextHolder promptContextHolder;
    private final IndexerService indexer;
    private final EmbeddingModel embeddingModel;
    private final RecallProps recallProps;
    private final ToolNameMap toolNameMap;
    @Qualifier("memoryTxTemplate")
    private final TransactionTemplate tx;

    /**
     * 完整对话流程：prepare（锚点创建 + 召回准备）→ 组一个 Turn → LLM 流式对话。
     * prepare 期间的硬错误在此兜底成一条 error 帧（buildStream 内部错误已由其自身收尾，不会漏到这）。
     */
    public Flux<ChatEvent> chat(@Nullable String anchorId, @Nullable String prevAnchorId,
                                String prompt, Thinking thinking, String userId) {
        return Blocking.call(() -> prepare(userId, anchorId, prevAnchorId, prompt))
                .map(prepared -> new Turn(prepared, userId, prompt))
                .flatMapMany(turn -> buildStream(thinking, turn))
                .onErrorResume(err -> {
                    log.error("Chat prepare failed [userId={}]", userId, err);
                    return Flux.just(new ChatEvent("error", "会话初始化失败，请稍后重试"));
                });
    }

    /**
     * 在 {@code assistant.chat()} 启动前同步完成，返回解析后的锚点信息。
     *
     * <p>锚点开一轮（{@code anchorService.openTurn}：压上一锚点 + 解析/懒建）+
     * {@code chatMemoryService.seed}（committed 灌进 pending）在 try 外——硬错误，直接传播；
     * embed / 召回 / PromptContext 构建失败吞掉（辅助能力，不阻断对话）。
     */
    private ChatPrepared prepare(String userId, @Nullable String anchorId,
                                 @Nullable String prevAnchorId, String prompt) {
        boolean newAnchor = anchorId == null;
        anchorId = anchorService.openTurn(anchorId, prevAnchorId, userId);
        chatMemoryService.seed(anchorId);
        Embedding emb = null;
        try {
            // prompt 向量化：召回 query + persistTurn 索引 cold user 行复用同一份，避免重复嵌入
            emb = embeddingModel.embed(prompt).content();
            Long windowSince = coldMemoryDao.windowLowerBound(userId, chatProps.getLoadMessage());
            List<RecallResult> recalled = recallService.recall(userId, emb, recallProps.getLimit(), windowSince);
            // 一次性构造 PromptContext，compose() 在 event loop 上纯内存读，不再访问 DB
            promptContextHolder.set(anchorId, new PromptContext(
                    anchorId,
                    userId,
                    soulDao.snapshot(),
                    hotMemoryDao.snapshot(userId, HotMemoryType.USER_IMPRESSION),
                    anchorMemoryDao.listOtherAnchors(userId, anchorId),
                    RecallPromptImpl.format(recalled)
            ));
        } catch (Exception e) {
            log.warn("Prepare failed, skip auto-association [anchorId={}]: {}", anchorId, e.toString());
        }
        return new ChatPrepared(anchorId, newAnchor, emb);
    }

    /**
     * 组装 Flux 管道：单条 LLM 主流 publishOn 到 boundedElastic 后挂五个生命周期钩子，
     * 末尾把错误收尾成 error 帧。新锚点时把 anchor_created 置为首条事件。
     */
    private Flux<ChatEvent> buildStream(Thinking thinking, Turn turn) {
        // 两个 Schedulers 各管一侧、不重复：
        //  subscribeOn（在 retryWhen 前）——订阅侧：bridgeTokenStream 的 LC4J .start() 落在 boundedElastic，
        //    尤其让 retryWhen 的重订阅也在 boundedElastic（否则默认在 parallel 重订阅）。
        //  publishOn（在 retryWhen 后）——下行侧：doOnNext/doOnComplete（含阻塞的 persistTurn 事务）搬到
        //    boundedElastic，不占 LC4J 回调线程、不碰 event loop。
        Flux<ChatEvent> stream = Flux.<ChatEvent>create(sink -> bridgeTokenStream(sink, thinking, turn))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(buildRetrySpec(turn.hasEmitted))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(turn::collectContent)                    // 收全文
                .doOnComplete(() -> persistTurn(turn))             // 成功：单事务落库 + 索引
                .doOnCancel(() -> cancelTurn(turn))            // 取消：掐断上游 LLM 流
                .doFinally(signal -> releaseTurn(turn))            // 终态：清 PromptContext + pending
                .onErrorResume(err -> failureEvent(turn, err));    // 失败：记日志 + 收尾成 error 帧
        return turn.newAnchor
                ? Flux.just(new ChatEvent("anchor_created", turn.anchorId)).concatWith(stream)
                : stream;
    }

    /**
     * 把 LC4J TokenStream 的回调桥接为 {@link FluxSink} 事件流。
     * LC4J 回调跑在框架线程，与 event loop 隔离；{@code turn.hasEmitted} 首次回调时置位，供重试策略判断。
     */
    private void bridgeTokenStream(FluxSink<ChatEvent> sink, Thinking thinking, Turn turn) {
        MoodEventProcessor moodStripper = new MoodEventProcessor(sink, turn.userId);
        streamingChatPort.stream(turn.anchorId, turn.prompt, thinking)
                // WithContext 变体：首个 thinking/content 回调时抓住 StreamingHandle，供取消时掐断上游
                .onPartialThinkingWithContext((pt, ctx) -> {
                    turn.handle.compareAndSet(null, ctx.streamingHandle());
                    turn.hasEmitted.set(true);
                    if (pt != null && pt.text() != null) sink.next(new ChatEvent("thinking", pt.text()));
                })
                .onPartialResponseWithContext((pr, ctx) -> {
                    turn.handle.compareAndSet(null, ctx.streamingHandle());
                    turn.hasEmitted.set(true);
                    moodStripper.accept(pr.text());
                })
                .beforeToolExecution(evt -> {
                    turn.hasEmitted.set(true);
                    String name = evt.request().name();
                    String query = WebSearchEvents.extractQuery(evt.request().arguments());
                    String chineseName = toolNameMap.toolName(name);
                    log.info("Tool call start [anchorId={}] {} ({}) query={}", turn.anchorId, name, chineseName, query);
                    sink.next(new ChatEvent("tool_call", WebSearchEvents.toolCallPayload(name, chineseName, query)));
                })
                .onToolExecuted(exec -> {
                    turn.hasEmitted.set(true);
                    String name = exec.request().name();
                    int count = WebSearchEvents.countResults(exec.result());
                    String chineseName = toolNameMap.toolName(name);
                    log.info("Tool call done [anchorId={}] {} ({}) count={}", turn.anchorId, name, chineseName, count);
                    sink.next(new ChatEvent("tool_result", WebSearchEvents.toolResultPayload(name, chineseName, count, exec.result())));
                })
                .onError(sink::error)
                .onCompleteResponse(r -> {
                    log.info("Stream completed [anchorId={}]", turn.anchorId);
                    moodStripper.flush();
                    turn.inlineMoods.addAll(moodStripper.getMoods());
                    sink.complete();
                })
                .start();
    }

    // ====== 五个生命周期钩子 ======

    /**
     * doOnComplete——成功轮：一个事务落 chat + cold(user/assistant) + hot + anchor(touch/title)，
     * 要么全落要么全不落（防崩在提交中途留跨表半截）；事务提交成功后再 fire-and-forget 索引 Qdrant
     * （向量是派生索引，绝不能先于正本提交，否则回滚留孤儿向量）。自吞异常——用户已看到完整回复，
     * 落库失败只记日志、不外发 error 帧。借 publishOn 已在 boundedElastic 上，故阻塞事务可同步直调。
     */
    private void persistTurn(Turn turn) {
        String lastMood = turn.lastMood();
        String moodTrail = turn.moodTrail();
        boolean hasContent = turn.hasContent();
        String assistantText = turn.assistantText();
        String autoTitle = truncate(turn.prompt, AUTO_TITLE_MAX_LEN);
        try {
            CommitResult result = tx.execute(status -> {
                chatMemoryService.commit(turn.anchorId);
                long coldUserId = -1, coldAssistantId = -1;
                if (hasContent) {
                    coldUserId = coldMemoryService.append(turn.userId, "user", turn.prompt, null);
                    coldAssistantId = coldMemoryService.append(turn.userId, "assistant", assistantText, moodTrail);
                }
                List<HotMemoryService.Entry> hotEntries = hotMemoryService.commit(turn.anchorId);
                anchorService.touch(turn.anchorId, lastMood);
                if (!autoTitle.isBlank()) anchorService.updateTitleIfBlank(turn.anchorId, autoTitle);
                return new CommitResult(coldUserId, coldAssistantId, hotEntries);
            });
            indexAfterCommit(turn, result);
        } catch (Exception e) {
            log.error("Persist turn failed [anchorId={}, userId={}]", turn.anchorId, turn.userId, e);
        }
    }

    /** onErrorResume——失败轮：记日志 + 把错误收尾成一帧 error 事件（管道唯一的错误出口；pending 由 releaseTurn 清）。 */
    private Mono<ChatEvent> failureEvent(Turn turn, Throwable err) {
        log.error("Stream failed [anchorId={}, userId={}]", turn.anchorId, turn.userId, err);
        return Mono.just(new ChatEvent("error", "AI 服务异常，请稍后重试"));
    }

    /** doOnCancel——客户端断开：主动掐断上游 LLM 流，避免无人消费时继续烧 token。 */
    private void cancelTurn(Turn turn) {
        log.info("Stream cancelled [anchorId={}, userId={}]", turn.anchorId, turn.userId);
        StreamingHandle h = turn.handle.get();
        if (h != null && !h.isCancelled()) h.cancel();
    }

    /** doFinally——终态统一清理：PromptContext + pending 缓冲（成功轮 persistTurn 已在此前同步提交完）。 */
    private void releaseTurn(Turn turn) {
        promptContextHolder.remove(turn.anchorId);
        chatMemoryService.discard(turn.anchorId);
        hotMemoryService.discard(turn.anchorId);
    }

    // ====== 事务后索引 ======

    /** 事务提交成功后：fire-and-forget 索引 Qdrant（cold user/assistant + hot 各条）。 */
    private void indexAfterCommit(Turn turn, CommitResult result) {
        if (turn.hasContent()) {
            long now = System.currentTimeMillis();
            Mono<Void> coldUser = turn.userEmbedding != null
                    ? indexer.indexAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldUserId()), turn.prompt, "user", now, turn.userEmbedding)
                    : indexer.indexTextAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldUserId()), turn.prompt, "user", now);
            fireIndex(coldUser, "cold-user");
            fireIndex(indexer.indexTextAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldAssistantId()),
                    turn.assistantText(), "assistant", now), "cold-assistant");
        }
        for (HotMemoryService.Entry e : result.hotEntries()) {
            fireIndex(indexer.indexTextAsync(e.userId(), e.type(), e.key(), e.embedText(), null, System.currentTimeMillis()),
                    "hot-" + e.type());
        }
    }

    /** fire-and-forget 索引：失败只 warn 不传播（backfill 兜底），不阻塞、不影响主流程。 */
    private void fireIndex(Mono<Void> indexOp, String desc) {
        indexOp.doOnError(err -> log.warn("Index failed [{}]: {}", desc, err.toString()))
                .onErrorComplete()
                .subscribe();
    }

    /** persistTurn 事务的返回值：cold 行 id（未写入为 -1）+ 本轮已落库的 hot 条目，供事务提交后索引用。 */
    private record CommitResult(long coldUserId, long coldAssistantId, List<HotMemoryService.Entry> hotEntries) {
    }

    /**
     * 指数退避重试策略——仅首次 emit 前对可重试错误生效。
     * {@code hasEmitted} 由 bridgeTokenStream 任一回调置位，避免流开始后重试（已部分发送的内容无法回滚）。
     */
    private Retry buildRetrySpec(AtomicBoolean hasEmitted) {
        ChatProps.Retry r = chatProps.getRetry();
        return Retry.backoff(r.getMaxAttempts(), r.getMinBackoff())
                .maxBackoff(r.getMaxBackoff())
                .filter(err -> !hasEmitted.get() && RetryableExceptions.isRetryable(err))
                .doBeforeRetry(rs -> log.warn("LLM retry #{} cause={}",
                        rs.totalRetries() + 1, rs.failure().toString()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 一轮对话的可变流式状态——把原先散落在 buildStream 的局部变量收进一处，
     * 让各生命周期钩子退化成一行委托（{@code turn::xxx} / {@code xxx(turn)}）。
     * 累积字段跨线程访问（LC4J 回调线程写入 + boundedElastic/取消线程读取），故用线程安全类型。
     */
    private static final class Turn {
        final String anchorId;
        final String userId;
        final String prompt;
        @Nullable final Embedding userEmbedding;
        final boolean newAnchor;

        /** 重试守门：收到首个 token 后置位，禁止再重试（已发出的内容无法回滚）。 */
        final AtomicBoolean hasEmitted = new AtomicBoolean(false);
        /** 上游 LLM 流句柄：首个 token 回调时抓取，取消时掐断。 */
        final AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        /** assistant 全文（LLM 主流写，无并发）。 */
        private final StringBuilder assistant = new StringBuilder();
        /** 正文内嵌情绪标签，按出现顺序（含开头必打的第一反应）。 */
        final List<String> inlineMoods = new CopyOnWriteArrayList<>();

        Turn(ChatPrepared prepared, String userId, String prompt) {
            this.anchorId = prepared.anchorId();
            this.newAnchor = prepared.newAnchor();
            this.userEmbedding = prepared.userEmbedding();
            this.userId = userId;
            this.prompt = prompt;
        }

        /** doOnNext：累积 assistant 全文（只收 content 事件）。 */
        void collectContent(ChatEvent event) {
            if ("content".equals(event.type())) assistant.append(event.data());
        }

        boolean hasContent() {
            return assistant.length() > 0;
        }

        String assistantText() {
            return assistant.toString();
        }

        /** 末尾情绪 → 写 anchor.last_mood。 */
        @Nullable String lastMood() {
            return inlineMoods.isEmpty() ? null : inlineMoods.get(inlineMoods.size() - 1);
        }

        /** 逗号连接的完整轨迹 → 写 cold_memory.mood。 */
        @Nullable String moodTrail() {
            return inlineMoods.isEmpty() ? null : String.join(",", inlineMoods);
        }
    }
}
