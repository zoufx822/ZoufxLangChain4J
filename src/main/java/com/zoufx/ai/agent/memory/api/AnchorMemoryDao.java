package com.zoufx.ai.agent.memory.api;

import com.zoufx.ai.agent.memory.model.AnchorMemory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 锚点记忆业务接口——管理 anchor_memory 表，承载 anchorId ↔ userId 关联 + title / summary 缓存。
 *
 * <p>同步方法是本体（阻塞 JDBC），只允许已脱离 event loop 的调用方使用
 * （Prompt.render、@Tool 方法、boundedElastic 上的阻塞流水线）；
 * {@code xxxAsync} 是同步本体的 boundedElastic 包装，供 WebFlux Controller /
 * ChatService 反应式编排串接，仅在确有反应式调用方时提供。
 */
public interface AnchorMemoryDao {

    /**
     * 同步反查 userId——给 Prompt 与 @Tool 方法使用。
     * anchorId 不存在返回 null，调用方自行处理（一般跳过本段 / 返回工具调用失败）。
     */
    @Nullable String findUserId(String anchorId);

    /**
     * 同步加载该用户的其他锚点（排除当前 anchorId），按 last_active_at desc。
     * 供 {@code AnchorPromptImpl.render} 用。
     */
    List<AnchorMemory> listOtherAnchors(String userId, String excludeAnchorId);

    /**
     * 同步读取锚点当前 last_active_at——供 {@link com.zoufx.ai.agent.chat.service.AnchorService#compress}
     * 在压缩前快照，写回时做 CAS 防止覆盖 touch 后的活跃状态。
     * anchorId 不存在返回 null。
     */
    @Nullable Long snapshotActiveAt(String anchorId);

    /**
     * 同步读取锚点现有摘要——供增量压缩把「旧摘要 + 新对话」一起喂给 LLM 产出更新后的摘要。
     * anchorId 不存在或从未压缩过返回 null。
     */
    @Nullable String loadSummary(String anchorId);

    /**
     * 找出需要压缩的锚点 id：{@code last_active_at < idleBefore}（空闲超阈值）且自上次摘要后有新内容
     * （{@code summarized_at IS NULL 或 < last_active_at}）。供定时压缩扫描器用。
     */
    List<String> findAnchorsNeedingCompaction(long idleBefore);

    /**
     * 同步创建锚点——内部生成 UUID 并返回。同步签名，调用方在 boundedElastic 上。
     */
    String create(String userId);

    /**
     * 列出该用户全部锚点，按 last_active_at desc。给前端 sidebar 用。
     */
    Mono<List<AnchorMemory>> listByUserAsync(String userId);

    /**
     * 标记锚点为活跃——更新 last_active_at = now，把本轮完整情绪轨迹（逗号连接）写入 mood 列
     * （COALESCE 语义：null 不覆盖旧值，保留"上一轮的轨迹"）。
     * summary 是滚动摘要、由定时压缩维护，touch **不动它**（last_active_at 推进后自然让 summarized_at 落后 → 下次扫描重压）。
     * 由 {@code ChatService.persistTurn} 在事务内同步调用。
     */
    void touch(String anchorId, @Nullable String moodTrail);

    /**
     * CAS 写入压缩摘要 + 推进水位 summarized_at——仅当 last_active_at 与快照一致时才写。
     * 若 touch 在压缩期间推进了 last_active_at，CAS 不匹配 → 静默丢弃这次结果，新内容留待下次扫描重压。
     * 由 {@link com.zoufx.ai.agent.chat.service.AnchorService#compress} 的同步流水线调用。
     */
    void updateSummaryIfUnchanged(String anchorId, String summary, long snapshotAt);

    /**
     * 仅推进水位 summarized_at（不动 summary）——供压缩时发现无可摘要内容（转录为空）时标记「已处理到此」，
     * 避免空锚点被扫描反复挑中。同样 CAS on last_active_at。
     */
    void bumpSummarizedAt(String anchorId, long snapshotAt);

    /**
     * 仅当 title 为 null / 空白时填入——避免覆盖用户手动改过的标题。
     * 由 {@code ChatService.persistTurn} 用首条用户消息截取后在事务内同步调用。
     */
    void updateTitleIfBlank(String anchorId, String title);

    /**
     * 无条件覆盖 title——由前端 PATCH /ai/anchors/{anchorId}/title 用户手动改名时调。
     * 与 {@link #updateTitleIfBlank} 区分：后者只填空，本方法强写。
     */
    Mono<Void> updateTitleAsync(String anchorId, String title);

}
