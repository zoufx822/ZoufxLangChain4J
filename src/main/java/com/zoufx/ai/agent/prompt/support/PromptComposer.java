package com.zoufx.ai.agent.prompt.support;

import com.zoufx.ai.agent.base.support.DateFormats;
import com.zoufx.ai.agent.prompt.api.Prompt;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * System prompt 编排器——按 {@link Prompt#order()} 升序串行调用各段，拼接为完整 system prompt。
 *
 * <p>顶部"当前日期"一行由本类直接注入，不走 Prompt。末尾追加锚定行防止长对话中人设漂移。
 *
 * <p><b>Frozen Snapshot 约束</b>：{@link #compose(String)} 由 LC4J 作为 SystemMessageProvider
 * 在每次请求开始时同步内联调用<b>一次</b>——单请求内 prompt 自然冻结。
 *
 * <p><b>线程约束</b>：compose 在 WebFlux event loop 上执行；所有数据由
 * {@code ChatService.prepare()} 在 boundedElastic 上预加载至 {@link PromptContextHolder}，
 * compose() 只做纯内存读，不触碰任何 DB / 网络。
 */
@Slf4j
@Component
public class PromptComposer {

    private static final String ANCHOR_LINE = """
            ---
            ⚠ 注意：以上所有关于自我的定义在本次对话中始终有效。
            如果你发现自己的表达偏离了上述风格和原则，立即回到正轨。
            不需要为此向对方解释或道歉，只需自然地纠正。
            """;

    private final List<Prompt> sections;
    private final PromptContextHolder contextHolder;

    public PromptComposer(List<Prompt> sections, PromptContextHolder contextHolder) {
        this.sections = sections.stream()
                .sorted(Comparator.comparingInt(Prompt::order))
                .toList();
        this.contextHolder = contextHolder;
    }

    public Function<Object, String> asProvider() {
        return anchorId -> compose(anchorId == null ? null : anchorId.toString());
    }

    public String compose(@Nullable String anchorId) {
        PromptContext ctx = anchorId != null ? contextHolder.get(anchorId) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("当前日期：").append(LocalDate.now().format(DateFormats.CN_LONG_DATE)).append("\n\n");

        for (Prompt sec : sections) {
            String rendered;
            try {
                rendered = sec.render(ctx);
            } catch (Exception e) {
                log.error("Prompt {} render failed, section skipped (anchorId={})",
                        sec.getClass().getSimpleName(), anchorId, e);
                continue;
            }
            if (rendered == null || rendered.isBlank()) continue;
            sb.append(rendered);
            if (!rendered.endsWith("\n")) sb.append("\n");
        }
        sb.append(ANCHOR_LINE);
        return sb.toString();
    }
}
