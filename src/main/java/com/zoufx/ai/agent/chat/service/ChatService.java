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
import com.zoufx.ai.agent.chat.model.CommitResult;
import com.zoufx.ai.agent.chat.model.StopResult;
import com.zoufx.ai.agent.chat.model.Thinking;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.prompt.support.PromptContextHolder;
import com.zoufx.ai.agent.mood.support.MoodEventProcessor;
import com.zoufx.ai.agent.chat.support.RetryableExceptions;
import com.zoufx.ai.agent.chat.support.Turn;
import com.zoufx.ai.agent.chat.support.TurnHandle;
import com.zoufx.ai.agent.chat.support.TurnRegistry;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天编排服务——一个类里可读到完整对话生命周期：
 *
 * <pre>
 *   chat()
 *     ├── prepare()              boundedElastic 上同步准备：开一轮 + seed + embed + 召回 + PromptContext，产出 Turn（自带 turnId）
 *     └── buildStream()          生成脱离客户端连接（consumeStream）：
 *           后端自持订阅  buildGenerationFlux(turn).subscribe(...)   驱动生成、落库，与客户端在不在无关
 *             Flux.create(bridgeTokenStream).subscribeOn(bE).retryWhen(...).publishOn(bE)
 *             .doOnNext      收全文     turn::collectContent
 *             .doOnComplete  成功→落库   persistTurn（先 claim 抢终局，未被停止才落）
 *             .doFinally     终态→清理   releaseTurn + 注册表移除
 *           客户端 SSE = sink.asFlux()（Sinks.replay().all()，视图）
 *             断连 = 取消视图，不动后端订阅（生成继续跑完落库）；错误经 sink 收尾成 error 帧
 * </pre>
 *
 * <p>情绪单一来源：主模型在正文里打 {@code ⟦mood:X⟧} 标记（开头必打第一反应 + 转折按需追加），
 * 由 {@code MoodEventProcessor} 剥离成独立 mood 事件——不再另起一次 LLM 快速分类支。
 *
 * <p>关键：生成挂在后端自己的订阅上，客户端 SSE 只是 replay sink 的一个视图。关页/断网/刷新只取消视图，
 * 后端订阅照跑到底并落库（用户回来轮询到完整回复）；主动停止走独立端点 {@link #stop}（掐 handle + dispose
 * 订阅），而非把「断连」当停止。落库/清理都挂后端订阅（doOnComplete/doFinally），与客户端在不在解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 自动 backfill title 时取首条用户消息的最大字符数。 */
    private static final int AUTO_TITLE_MAX_LEN = 20;

    /** 流式对话端口：按 thinking 开关路由到合适的模型/参数，屏蔽 profile 间的 per-call 能力差异。 */
    private final StreamingChatPort streamingChatPort;
    /** 在建轮注册表——stop 端点与 GET pending 靠它定位一轮；生成脱离连接后这是唯一的定位处。 */
    private final TurnRegistry turnRegistry;
    /** 会话窗口业务层——LC4J ChatMemoryStore 契约 + write-back 缓冲。 */
    private final ChatMemoryService chatMemoryService;
    /** 锚点只读——prepare 组装 PromptContext 用。 */
    private final AnchorMemoryDao anchorMemoryDao;
    /** 锚点生命周期业务——touch/title（同步，事务内）+ 摘要压缩。 */
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
     * 完整对话流程：prepare（锚点创建 + 召回准备，直接产出 Turn）→ LLM 流式对话。
     * prepare 期间的硬错误在此兜底成一条 error 帧（buildStream 内部错误已由其自身收尾，不会漏到这）。
     */
    public Flux<ChatEvent> chat(@Nullable String anchorId, String prompt, Thinking thinking, String userId) {
        // 注册表幽灵 pending 兜底②：prepare 跑在 boundedElastic 上，客户端若在 prepare 期间断连，
        // flatMapMany 不会订阅、doFinally 也不会触发。cancelled + registered 构成双向探测：
        // doOnCancel 先置位再读引用，prepare 先登记引用再读标志——两侧至少一侧必看到对方，
        // 消除「取消落在 register 与引用可见之间」的窗口。（实测过 doOnDiscard：
        // fromCallable+subscribeOn 组合下取消后产出的值只是静默丢弃、不经 discard 钩子，故不可依赖。）
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Turn> registered = new AtomicReference<>();
        return Blocking.call(() -> prepare(userId, anchorId, prompt, registered, cancelled))
                .doOnCancel(() -> {
                    cancelled.set(true);
                    Turn turn = registered.get();
                    if (turn != null) {
                        log.info("Client disconnected during prepare, releasing turn [turnId={}, anchorId={}]",
                                turn.turnId, turn.anchorId);
                        turnRegistry.remove(turn.turnId);
                        releaseTurn(turn);   // 幂等；此时生成管道必未启动（Mono 已终止则本钩子不触发），无竞争
                    }
                })
                .flatMapMany(turn -> buildStream(thinking, turn))
                .onErrorResume(err -> {
                    log.error("Chat prepare failed [userId={}]", userId, err);
                    return Flux.just(new ChatEvent("error", "会话初始化失败，请稍后重试"));
                });
    }

    /**
     * 在 {@code assistant.chat()} 启动前同步完成，直接构建本轮 {@link Turn}（turnId + 解析后的锚点 + 预计算向量）。
     *
     * <p>懒建锚点（anchorId 为空则新建）+ {@code chatMemoryService.seed}（committed 灌进 pending）在 try 外——
     * 硬错误，直接传播；PromptContext 本体（soul/画像/其他锚点摘要）与 embed/召回解耦，只有 embed/召回
     * 失败吞掉（辅助能力，不阻断对话，userEmbedding/recalled 留空）——embedding 服务抖动不该连累人格快照、
     * 用户画像、跨锚点摘要一并从 system prompt 里消失。
     * 上一锚点的压缩不在此触发——改由 {@link com.zoufx.ai.agent.chat.support.ChatScheduler} 定时按空闲检测。
     *
     * <p>{@code turnRegistry.register} 紧跟锚点解析之后、embed/召回（慢网络调用）之前——锚点创建
     * 落库即刻可被 {@code GET /ai/anchors} 查到，若注册表登记晚于这些慢调用，会出现"锚点已存在但
     * pending 查不到在建轮"的窗口（实测约 100~200ms，足够被一次页面刷新撞上）。
     *
     * <p>register 之后的全部剩余代码包一层 try/catch：soul/画像/其他锚点摘要读取属硬错误（DB 故障等），
     * 抛出即需从注册表摘除再上抛，否则 {@link #chat} 的 {@code flatMapMany} 不会订阅、doFinally 也不会
     * 触发，该锚点 pending 永远卡住（幽灵 turn，{@code stop} 也救不了）。{@code registered}/{@code cancelled}
     * 与调用方 doOnCancel 构成断连双向探测（见 {@link #chat} 注释）。
     */
    private Turn prepare(String userId, @Nullable String anchorId, String prompt,
                         AtomicReference<Turn> registered, AtomicBoolean cancelled) {
        Turn turn = Turn.builder()
                .userId(userId)
                .prompt(prompt)
                .newAnchor(anchorId == null)
                .anchorId(Optional.ofNullable(anchorId).orElseGet(() -> anchorMemoryDao.create(userId)))
                .build();

        chatMemoryService.seed(turn.anchorId);
        turnRegistry.register(turn);
        registered.set(turn);
        // 双向探测补刀：取消若发生在 registered 可见之前，doOnCancel 读到 null 无从清理，由这里接手；
        // 此后任何取消都能从 registered 读到本 turn，归 doOnCancel 管
        if (cancelled.get()) {
            turnRegistry.remove(turn.turnId);
            chatMemoryService.discard(turn.anchorId);
            log.info("Client disconnected before registration visible, released turn [turnId={}]", turn.turnId);
            return turn;   // 下游已取消，返回值被直接丢弃——跳过 embed/召回等昂贵步骤
        }

        try {
            List<RecallResult> recalled = List.of();
            try {
                // prompt 向量化：召回 query + persistTurn 索引 cold user 行复用同一份，避免重复嵌入
                Embedding emb = embeddingModel.embed(prompt).content();
                turn.userEmbedding = emb;
                // 借 chat window 大小在 cold_memory（跨锚点全量）近似出一个时间下界：
                // 同一轮消息会同时落 chat_memory（发给 LLM 的原文）和 cold_memory（可召回索引），不排除的话，
                // 刚发生的对话会被召回换个格式重复塞进 system prompt，让 LLM 把"刚说的话"误当成"想起的旧记忆"。
                Long windowSince = coldMemoryDao.windowLowerBound(userId, chatProps.getLoadMessage());
                recalled = recallService.recall(userId, emb, recallProps.getLimit(), windowSince);
            } catch (Exception e) {
                log.warn("Embed/recall failed, skip auto-association [anchorId={}]: {}", turn.anchorId, e.toString());
            }
            Map<String, String> hotImpressionSnap = hotMemoryDao.snapshot(userId, HotMemoryType.USER_IMPRESSION);
            turn.username = hotImpressionSnap.get("username");
            promptContextHolder.set(turn.anchorId, new PromptContext(
                    turn.anchorId,
                    userId,
                    soulDao.snapshot(),
                    hotImpressionSnap,
                    anchorMemoryDao.listOtherAnchors(userId, turn.anchorId),
                    recalled
            ));
        } catch (RuntimeException e) {
            turnRegistry.remove(turn.turnId);
            throw e;
        }
        return turn;
    }

    /**
     * 生成脱离客户端连接（consumeStream）：生成挂在后端自持订阅上，客户端 SSE 只是 replay sink 的一个视图。
     * 关页/断网只取消视图，后端订阅照跑到底并落库；主动停止走独立端点 {@link #stop}，而非把断连当停止。
     */
    private Flux<ChatEvent> buildStream(Thinking thinking, Turn turn) {
        // replay().all()：客户端视图是 controller 返回后才订阅（晚一步），靠回放拿到下面先发的开头帧
        Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();
        sink.tryEmitNext(new ChatEvent("turn_started", turn.turnId));
        if (turn.newAnchor) sink.tryEmitNext(new ChatEvent("anchor_created", turn.anchorId));

        // 后端自持订阅：驱动生成、把事件转进 sink——与客户端在不在无关（turn 已在 prepare 阶段登记注册表）
        turn.setBackendSub(buildGenerationFlux(thinking, turn).subscribe(
                sink::tryEmitNext,
                err -> {   // 出错：记日志 + 传给 sink（doOnComplete 不触发→不落库）
                    log.error("Stream failed [anchorId={}, userId={}]", turn.anchorId, turn.userId, err);
                    sink.tryEmitError(err);
                },
                sink::tryEmitComplete));

        // 客户端 SSE = sink 视图：断连只取消它（生成继续跑完落库），错误收尾成一帧 error（仅给还连着的客户端）
        return sink.asFlux()
                .doOnCancel(() -> log.info("Client disconnected, generation continues [turnId={}, anchorId={}]",
                        turn.turnId, turn.anchorId))
                .onErrorResume(err -> Flux.just(new ChatEvent("error", "AI 服务异常，请稍后重试")));
    }

    /**
     * 后端订阅的生成主流：单条 LLM 主流 publishOn 到 boundedElastic 后挂三个生命周期钩子。
     * subscribeOn（retryWhen 前）让 LC4J {@code .start()} + 重订阅落 boundedElastic；publishOn（retryWhen 后）
     * 让 doOnComplete 的阻塞落库事务落可阻塞线程。落库/清理都挂这条链，与客户端在不在无关（成功才落）。
     */
    private Flux<ChatEvent> buildGenerationFlux(Thinking thinking, Turn turn) {
        return Flux.<ChatEvent>create(s -> bridgeTokenStream(s, thinking, turn))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(buildRetrySpec(turn.hasEmitted))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(turn::collectContent)                    // 收全文
                .doOnComplete(() -> persistTurn(turn))             // 成功：先 claim 抢终局，未被停止才单事务落库 + 索引
                .doFinally(signal -> {                             // 终态：清 PromptContext + pending，移除注册表（防泄漏）
                    releaseTurn(turn);
                    turnRegistry.remove(turn.turnId);
                });
    }

    /**
     * 把 LC4J TokenStream 的回调桥接为 {@link FluxSink} 事件流。
     * LC4J 回调跑在框架线程，与 event loop 隔离；{@code turn.hasEmitted} 首次回调时置位，供重试策略判断。
     */
    private void bridgeTokenStream(FluxSink<ChatEvent> sink, Thinking thinking, Turn turn) {
        MoodEventProcessor moodStripper = new MoodEventProcessor(sink, turn.userId);
        streamingChatPort.stream(turn.anchorId, turn.prompt, thinking)
                // WithContext 变体：首个 thinking/content 回调时抓住 StreamingHandle，供取消时掐断上游。
                // stop 若在首 token 前抢到 claim，此时 handle 还不存在、abort() 的 cancel 落空——
                // 故每次抓到 handle 都反查一次 claim，若已被抢占则就地掐断，防止上游白烧到自然结束。
                .onPartialThinkingWithContext((pt, ctx) -> {
                    turn.handle.compareAndSet(null, ctx.streamingHandle());
                    turn.hasEmitted.set(true);
                    if (cancelIfClaimed(turn, ctx.streamingHandle())) return;
                    if (pt != null && pt.text() != null) sink.next(new ChatEvent("thinking", pt.text()));
                })
                .onPartialResponseWithContext((pr, ctx) -> {
                    turn.handle.compareAndSet(null, ctx.streamingHandle());
                    turn.hasEmitted.set(true);
                    if (cancelIfClaimed(turn, ctx.streamingHandle())) return;
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

    /** 抓到 handle 时反查 claim：已被 stop 抢占则就地掐断上游，返回 true 供调用处跳过本次事件。 */
    private boolean cancelIfClaimed(Turn turn, StreamingHandle handle) {
        if (!turn.isClaimed()) return false;
        if (!handle.isCancelled()) handle.cancel();
        return true;
    }

    // ====== 生命周期钩子 ======

    /**
     * doOnComplete——成功轮：先 CAS 抢本轮终局（与主动停止竞争，抢不到=已被停止接管→不落库），抢到则
     * 一个事务落 chat + cold(user/assistant) + hot + anchor(touch/title)，要么全落要么全不落（防崩在提交中途
     * 留跨表半截）；事务提交成功后再 fire-and-forget 索引 Qdrant（向量是派生索引，绝不能先于正本提交，否则
     * 回滚留孤儿向量）。自吞异常——用户已看到完整回复，落库失败只记日志。借 publishOn 已在 boundedElastic 上，
     * 故阻塞事务可同步直调。
     */
    private void persistTurn(Turn turn) {
        if (!turn.claim()) {
            log.info("Skip persist, turn claimed by stop [turnId={}, anchorId={}]", turn.turnId, turn.anchorId);
            return;
        }
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
                anchorService.touch(turn.anchorId, moodTrail);
                if (!autoTitle.isBlank()) anchorService.updateTitleIfBlank(turn.anchorId, autoTitle);
                return new CommitResult(coldUserId, coldAssistantId, hotEntries);
            });
            indexAfterCommit(turn, result);
        } catch (Exception e) {
            log.error("Persist turn failed [anchorId={}, userId={}]", turn.anchorId, turn.userId, e);
        }
    }

    /** doFinally——终态统一清理：PromptContext + pending 缓冲（成功轮 persistTurn 已在此前同步提交完）。 */
    private void releaseTurn(Turn turn) {
        promptContextHolder.remove(turn.anchorId);
        chatMemoryService.discard(turn.anchorId);
        hotMemoryService.discard(turn.anchorId);
    }

    // ====== 停止 / 在建轮暴露（consumeStream 端点支撑） ======

    /**
     * 主动停止一轮：按 turnId 定位并 CAS 抢终局。抢到 → 掐 LC4J + dispose 后端订阅（触发 doFinally
     * discard + 注册表移除，不落库）；抢不到 / 不存在（该轮已完成落库或已停）→ {@code stopped=false}，
     * 前端据此保留该轮不删（否则删掉一条已存的成功轮，刷新又冒出来，自相矛盾）。
     */
    public StopResult stop(String turnId) {
        TurnHandle t = turnRegistry.get(turnId);
        if (t == null || !t.claim()) return new StopResult(false);
        log.info("Turn stopped [turnId={}, anchorId={}]", turnId, t.anchorId());
        t.abort();
        return new StopResult(true);
    }

    /**
     * 该锚点当前的在建轮问题——供 {@code GET pending}。write-back 下生成期间什么都没落库，loadMessages
     * 看不到该轮，只能问注册表拿到问题原文，让另一端/刷新后先看到「问题 + 生成中」，再轮询补回复。
     * 无在建轮返回空 Map（{}）。
     */
    public Map<String, String> pending(String anchorId) {
        TurnHandle t = turnRegistry.findByAnchor(anchorId);
        return t == null ? Map.of() : Map.of("turnId", t.turnId(), "prompt", t.prompt());
    }

    // ====== 事务后索引 ======

    /** 事务提交成功后：fire-and-forget 索引 Qdrant（cold user/assistant + hot 各条）。 */
    private void indexAfterCommit(Turn turn, CommitResult result) {
        if (turn.hasContent()) {
            long now = System.currentTimeMillis();
            Mono<Void> coldUser = turn.userEmbedding != null
                    ? indexer.indexAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldUserId()), turn.prompt, "user", now, turn.userEmbedding, turn.username)
                    : indexer.indexTextAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldUserId()), turn.prompt, "user", now, turn.username);
            fireIndex(coldUser, "cold-user");
            fireIndex(indexer.indexTextAsync(turn.userId, VectorPayload.COLD, String.valueOf(result.coldAssistantId()),
                    turn.assistantText(), "assistant", now, turn.username), "cold-assistant");
        }
        for (HotMemoryService.Entry e : result.hotEntries()) {
            fireIndex(indexer.indexTextAsync(e.userId(), e.type(), e.key(), e.embedText(), null, System.currentTimeMillis(), turn.username),
                    "hot-" + e.type());
        }
    }

    /** fire-and-forget 索引：失败只 warn 不传播（backfill 兜底），不阻塞、不影响主流程。 */
    private void fireIndex(Mono<Void> indexOp, String desc) {
        indexOp.doOnError(err -> log.warn("Index failed [{}]: {}", desc, err.toString()))
                .onErrorComplete()
                .subscribe();
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
}
