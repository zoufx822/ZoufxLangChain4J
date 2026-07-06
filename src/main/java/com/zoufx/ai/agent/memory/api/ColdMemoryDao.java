package com.zoufx.ai.agent.memory.api;

import org.jspecify.annotations.Nullable;
import com.zoufx.ai.agent.memory.model.ColdMemory;

import java.util.Collection;
import java.util.List;

/**
 * 冷内存（Cold Archive）业务接口——经历流存储。
 *
 * 与 {@link ChatMemoryDao}（工作记忆，继承 LC4J ChatMemoryStore）并行：
 * - {@link ChatMemoryDao}：滑窗 20 条，全量替换语义
 * - {@link ColdMemoryDao}：所有用户/AI 消息按时间序==只追加==，无上限
 *
 * 写入路径：{@code ChatService.persistTurn()}——LLM 无错误跑完才在单事务内 append user + assistant
 * 两行（经 {@link com.zoufx.ai.agent.memory.service.ColdMemoryService} 委托）；
 * 失败/取消一律不写，不在 LC4J Hook 里做，避免与 LC4J 全量替换语义冲突。
 *
 * 当前不写入 tool_result（噪音大）。全部同步签名，调用方均在事务/boundedElastic 上。
 */
public interface ColdMemoryDao {

    /**
     * 追加一条经历流记录，返回新行自增 id。
     *
     * @param role         'user' / 'assistant'
     * @param metadataJson JSON 字符串，当前留空
     * @param mood         情绪关键词，仅 assistant 消息有值；user 消息传 null
     */
    long append(String userId, String role, String content, @Nullable String metadataJson, @Nullable String mood);

    /**
     * 按 id 批量取原文——召回 hydration 用（Qdrant 只存指针，正文回这里取）。
     * 同步签名：调用方 {@code RecallServiceImpl} 在 boundedElastic 上。带 userId 过滤防跨用户。
     */
    List<ColdMemory> fetchByIds(String userId, Collection<Long> ids);

    /**
     * 该用户第 {@code windowSize} 近一条经历的 created_at——作为召回排除"工作窗口内已可见"cold 条目的下界。
     * 不足 windowSize 条返回 null（不排除，全部可召回）。同步签名（调用方在 boundedElastic 上）。
     */
    @Nullable Long windowLowerBound(String userId, int windowSize);
}
