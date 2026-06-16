package com.zoufx.ai.agent.tool.impl;

import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.vector.api.RecallService;
import com.zoufx.ai.agent.vector.model.RecallResult;
import com.zoufx.ai.agent.vector.support.VectorPayload;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.zoufx.ai.agent.tool.api.ToolPrompt;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆检索工具——当对方提到过往的事而 LLM 此刻想不起来（自动召回段 / 交谈摘要 / 当前对话里都没有）时，
 * ==语义召回==过往记忆（与自动召回共用 RecallService）。手动深挖路径：自动召回打底，想不起来才调本工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ColdMemorySearchTool implements ToolPrompt {

    private static final int DEFAULT_LIMIT = 5;
    private static final int HARD_MAX_LIMIT = 20;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault());

    private final RecallService recallService;
    private final AnchorMemoryDao anchorMemoryDao;
    private final EmbeddingModel embeddingModel;

    @Override
    public String section() {
        return "记忆检索";
    }

    @Override
    public Map<String, String> methodSections() {
        return Map.of("search_cold_memory", section());
    }

    @Override
    public String promptInstructions() {
        return """
                你的记忆跨越你们的所有交谈，不限于这一次对话。当对方提到过往的事（「我之前说过…吗」「你还记得…吗」「上次聊到的…」「我跟你提过…」），按以下顺序处理：

                ==第一步，先回想==：检查你此刻已经想起的内容——「此刻想起的相关记忆」、「你与对方此前的其他交谈」摘要、以及当前对话里已有的信息（==例：对方在这次对话里刚说过、前几轮提到过的内容，都算你已经知道，不必检索==）。
                - ==已有答案 → 直接自然融入回答，不要调用本工具==（内容已经在你脑中，再检索一遍是多余动作）
                ==第二步，想不起来才检索==：以上都没有时，==立刻调用 search_cold_memory== 主动回想——不要因为眼前看不到就断定没聊过。

                调用规则：
                - keyword：用关键词或短语，不要用整句话（例：传 "美式咖啡" 而不是 "我之前说过我喜欢喝什么饮料吗"）
                - 单次请求内最多调用 3 次，避免盲搜
                - 拿到结果后自然融入回答（"你之前提过 X..."），不要让对方感到你在翻聊天记录
                - 反模式：不回想、不检索就说"我没有记录""我刚开始和你聊天"——这是错的，必须==先回想、再检索、最后才能下结论==
                """;
    }

    @Tool("记忆检索：从你们所有交谈的完整记忆流里按语义检索过往记忆。当对方提到过往的事，而你此刻已想起的内容（相关记忆段、交谈摘要、当前对话）里都没有时使用。返回带时间戳、类型、内容的结果列表。")
    public String search_cold_memory(
            @ToolMemoryId String memoryId,
            @P("搜索关键词（短词或短语，不要用整句话）") String keyword,
            @P("返回条数，默认 5，最大 20") int limit) {
        String userId = anchorMemoryDao.findUserId(memoryId);
        if (userId == null) {
            return "search_cold_memory 调用失败：未识别的对话上下文";
        }
        if (keyword == null || keyword.isBlank()) {
            return "search_cold_memory 调用失败：keyword 不能为空";
        }
        int effectiveLimit = limit > 0 ? Math.min(limit, HARD_MAX_LIMIT) : DEFAULT_LIMIT;

        log.info("🔎 search_cold_memory [userId={}] keyword='{}' limit={}", userId, keyword, effectiveLimit);
        // windowSince 传 null——LLM 显式深挖时不排除近期内容；
        // @Tool 在工具线程上跑（允许阻塞），直接调同步 recall，不绕 recallAsync().block()
        var emb = embeddingModel.embed(keyword).content();
        List<RecallResult> hits = recallService.recall(userId, emb, effectiveLimit, null);
        if (hits.isEmpty()) {
            return "经历流里没找到与「" + keyword + "」相关的内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 条相关记忆（按相关性排序）：\n");
        int idx = 1;
        for (RecallResult r : hits) {
            String time = TIME_FMT.format(Instant.ofEpochMilli(r.createdAt()));
            sb.append(idx++).append(". [").append(VectorPayload.labelOf(r.memType())).append(" · ").append(time).append("] ")
                    .append(r.content().replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }
}
