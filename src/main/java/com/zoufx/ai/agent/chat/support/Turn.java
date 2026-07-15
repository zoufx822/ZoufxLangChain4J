package com.zoufx.ai.agent.chat.support;

import com.zoufx.ai.agent.chat.model.ChatEvent;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.response.StreamingHandle;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一轮对话的可变流式状态——把原先散落在 {@code ChatService.buildStream} 的局部变量收进一处，
 * 让各生命周期钩子退化成一行委托（{@code turn::xxx} / {@code xxx(turn)}）。
 * 由 prepare 阶段通过 Lombok {@code @Builder} 构建。
 * 累积字段跨线程访问（LC4J 回调线程写入 + boundedElastic/取消线程读取），故用线程安全类型。
 *
 * <p>实现 {@link TurnHandle}——{@link TurnRegistry} 只按该接口存取，编译期挡掉
 * assistant/inlineMoods/userEmbedding 等生成期内部状态对 stop/pending 等外部读者的暴露。
 */
@Builder(toBuilder = true)
public final class Turn implements TurnHandle {

    /** 本轮唯一标识——出生即生成，作首个 SSE 事件（turn_started）早发，供停止/在建轮定位。 */
    @Builder.Default
    public final String turnId = UUID.randomUUID().toString();
    public final String anchorId;
    public final String userId;
    public final String prompt;
    /** prepare 阶段 embed 完成后直接赋值（非 builder 字段——注册表在 embed 之前就已登记本 turn，
     *  toBuilder 重建会产生游离新实例，脱离已登记的引用）。 */
    public volatile @Nullable Embedding userEmbedding;
    public final boolean newAnchor;

    /** 重试守门：收到首个 token 后置位，禁止再重试（已发出的内容无法回滚）。 */
    @Builder.Default
    public final AtomicBoolean hasEmitted = new AtomicBoolean(false);
    /** 上游 LLM 流句柄：首个 token 回调时抓取，停止时掐断。 */
    @Builder.Default
    public final AtomicReference<StreamingHandle> handle = new AtomicReference<>();
    /** 落库/停止的终局 CAS 门闩：谁先抢到谁定局。 */
    @Builder.Default
    private final AtomicBoolean claimed = new AtomicBoolean(false);
    /** 后端自持订阅句柄，stop 时 dispose 触发管道 doFinally 清理。 */
    private volatile @Nullable Disposable backendSub;
    /** assistant 全文（LLM 主流写，无并发）。 */
    @Builder.Default
    private final StringBuilder assistant = new StringBuilder();
    /** 正文内嵌情绪标签，按出现顺序（含开头必打的第一反应）。 */
    @Builder.Default
    public final List<String> inlineMoods = new CopyOnWriteArrayList<>();

    @Override
    public String turnId() {
        return turnId;
    }

    @Override
    public String anchorId() {
        return anchorId;
    }

    @Override
    public String prompt() {
        return prompt;
    }

    public void setBackendSub(Disposable sub) {
        this.backendSub = sub;
    }

    @Override
    public boolean claim() {
        return claimed.compareAndSet(false, true);
    }

    @Override
    public void abort() {
        StreamingHandle h = handle.get();
        if (h != null && !h.isCancelled()) h.cancel();
        Disposable d = backendSub;
        if (d != null && !d.isDisposed()) d.dispose();
    }

    /** doOnNext：累积 assistant 全文（只收 content 事件）。 */
    public void collectContent(ChatEvent event) {
        if ("content".equals(event.type())) assistant.append(event.data());
    }

    public boolean hasContent() {
        return assistant.length() > 0;
    }

    public String assistantText() {
        return assistant.toString();
    }

    /** 末尾情绪 → 写 anchor.last_mood。 */
    public @Nullable String lastMood() {
        return inlineMoods.isEmpty() ? null : inlineMoods.get(inlineMoods.size() - 1);
    }

    /** 逗号连接的完整轨迹 → 写 cold_memory.mood。 */
    public @Nullable String moodTrail() {
        return inlineMoods.isEmpty() ? null : String.join(",", inlineMoods);
    }
}
