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
 * <p>召回原始结果由 {@code ChatService.prepare()} 存入 {@link PromptContext#recalled()}（未渲染）；
 * 文案格式化在本类内完成（compose 同步契约下的纯字符串拼接、无 IO）。
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
        List<RecallResult> hits = ctx.recalled();
        if (hits == null || hits.isEmpty()) return null;

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
