package com.zoufx.ai.agent.prompt.support;

import com.zoufx.ai.agent.memory.model.AnchorMemory;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 每次请求在 boundedElastic 上预加载的 prompt 全量数据快照。
 *
 * <p>{@code ChatService.prepare()} 构造并写入 {@link PromptContextHolder}；
 * {@code PromptComposer.compose()} 在 event loop 上读取本对象，所有 {@link Prompt} 实现
 * 从此取数——compose() 整体不做任何 DB 调用。
 */
public record PromptContext(
        String anchorId,
        @Nullable String userId,
        Map<String, String> soulSnap,
        Map<String, String> hotImpressionSnap,
        List<AnchorMemory> otherAnchors,
        String recallBlock
) {}
