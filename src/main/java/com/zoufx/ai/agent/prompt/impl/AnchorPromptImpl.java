package com.zoufx.ai.agent.prompt.impl;

import com.zoufx.ai.agent.prompt.api.Prompt;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.memory.model.AnchorMemory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「## 你与对方此前的其他交谈」段（order=28）——把同一用户的其他锚点摘要注入当前会话，
 * 让 AI 跨对话保有连续记忆。LLM 可见文案统一用"交谈"心智（记忆跨对话），
 * 不暴露"窗口"等工程概念——否则 LLM 会把记忆边界误解为单次对话。
 *
 * <p>只渲染已有 summary 的锚点（即客户端切走时已被 {@code AnchorService} 压缩过的）。
 * 当前活跃锚点 summary=null 会被 touch 清空——避免把"正在进行"的对话当背景塞回自己。
 * 全部锚点 summary 都为空时跳过本段。
 */
@Component
public class AnchorPromptImpl implements Prompt {

    /** 最多注入的其他锚点条数——按 last_active_at desc 取头部。 */
    private static final int INJECT_LIMIT = 5;

    @Override
    public int order() {
        return 28;
    }

    @Override
    @Nullable
    public String render(@Nullable PromptContext ctx) {
        if (ctx == null || ctx.userId() == null) return null;

        List<AnchorMemory> others = ctx.otherAnchors();
        if (others.isEmpty()) {
            return "## 你与对方此前的其他交谈\n\n暂无可参考的交谈摘要。对方提及过往时，以「此刻想起的相关记忆」和记忆检索的结果为准——**不要**凭空编造「我们之前聊过 X」或「你之前提到过 Y」。\n\n";
        }

        StringBuilder sb = new StringBuilder();
        int rendered = 0;
        for (AnchorMemory a : others) {
            if (rendered >= INJECT_LIMIT) break;
            String summary = a.summary();
            if (summary == null || summary.isBlank()) continue;
            if (rendered == 0) {
                sb.append("## 你与对方此前的其他交谈（背景参考，已按最近活跃排序）\n\n");
            }
            String title = a.title();
            if (title == null || title.isBlank()) title = "未命名对话";
            sb.append("- 「").append(title).append("」：").append(summary).append("\n");
            rendered++;
        }
        if (rendered == 0) {
            return "## 你与对方此前的其他交谈\n\n暂无可参考的交谈摘要。对方提及过往时，以「此刻想起的相关记忆」和记忆检索的结果为准——**不要**凭空编造「我们之前聊过 X」或「你之前提到过 Y」。\n\n";
        }
        sb.append("\n");
        return sb.toString();
    }
}
