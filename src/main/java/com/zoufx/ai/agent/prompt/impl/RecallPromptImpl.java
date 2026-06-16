package com.zoufx.ai.agent.prompt.impl;

import com.zoufx.ai.agent.prompt.api.Prompt;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.vector.model.RecallResult;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「## 此刻想起的相关记忆」段（order=45，system prompt 末段——置末保 prefix cache）。
 *
 * <p>内容由 {@code ChatService.prepare()} 召回后预算好存入 {@link PromptContext#recallBlock()}；
 * 本段只做一次同步字段读取（符合 compose 同步契约，不阻塞）。
 * 召回内容每请求重算、绝不落 chat_memory 窗口。
 */
@Component
public class RecallPromptImpl implements Prompt {

    /** 单条内容截断上限（受限即驱动，控常驻注意力）。 */
    private static final int MAX_ITEM_LEN = 120;

    @Override
    public int order() {
        return 45;
    }

    @Override
    @Nullable
    public String render(@Nullable PromptContext ctx) {
        if (ctx == null) return null;
        String block = ctx.recallBlock();
        return block.isBlank() ? null : block;
    }

    /**
     * 把召回结果渲染成段；空则返回 ""。由 ChatService 在 boundedElastic 上预算后存入 PromptContext。
     * 条数已由召回 limit 控制，这里只做单条截断。
     */
    public static String format(List<RecallResult> hits) {
        if (hits == null || hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 此刻想起的相关记忆\n\n");
        sb.append("（以下是你自然想起的、与当前对话相关的过往记忆——直接当作你记得的事实使用，自然融入回应，不要生硬罗列；其中已有的内容==不要==再调用记忆检索去查一遍）\n");
        for (RecallResult r : hits) {
            sb.append("- ").append(label(r.memType())).append(truncate(r.content())).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String label(String memType) {
        return VectorPayload.COLD.equals(memType) ? "" : "[" + VectorPayload.labelOf(memType) + "] ";
    }

    private static String truncate(String s) {
        String t = s.replace("\n", " ").trim();
        return t.length() <= MAX_ITEM_LEN ? t : t.substring(0, MAX_ITEM_LEN) + "…";
    }
}
