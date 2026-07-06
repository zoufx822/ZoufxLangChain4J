package com.zoufx.ai.agent.tool.impl;

import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.service.HotMemoryService;
import com.zoufx.ai.agent.memory.support.HotMemoryType;
import com.zoufx.ai.agent.tool.api.ToolPrompt;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 承诺记录工具——专管 hot_memory commitment type（叙事性 append-only）。
 *
 * <p>承诺方靠 value 文本前缀区分（schema 不加 side 列）：
 * 「我（AI）答应{对方}：」/「{对方}答应我：」/「我们约定：」。
 * 当前不追踪履行状态，仅记录原始承诺文本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommitmentRecordTool implements ToolPrompt {

    private final HotMemoryService hotMemoryService;
    private final AnchorMemoryDao anchorMemoryDao;

    @Override
    public String section() {
        return "承诺记录";
    }

    @Override
    public Map<String, String> methodSections() {
        return Map.of("record_commitment", section());
    }

    @Override
    public String promptInstructions() {
        return """
                ==必须触发==：识别到对话双方做出的承诺时，立刻调用 record_commitment(description)。

                承诺方靠 description 文本前缀显式区分——==必须从以下 3 个前缀中选一==：
                - 「我（AI）答应{对方称呼}：...」—— 你答应了对方
                - 「{对方称呼}答应我：...」—— 对方答应了你
                - 「我们约定：...」—— 双方共同约定

                正例：
                - 对方说"你这周帮我梳理 React 学习路径吧"，你应允 →
                  record_commitment("我（AI）答应{对方称呼}：本周帮其梳理 React 学习路径")
                - 对方说"我这周内把代码 review 完发给你" →
                  record_commitment("{对方称呼}答应我：本周内 review 完代码")
                - 对方说"我们一起读 SICP 吧"，你应允 →
                  record_commitment("我们约定：一起读 SICP")

                调用规则：
                - {对方称呼} 占位用已知的 username 填入；未识别 username 时用「对方」
                - description ==必须带前缀==，否则后续读不出承诺方
                - 一轮可写多条独立承诺
                - 调用是后台动作，回复对方时无需说"我已记下"
                - 反模式：
                  - 即时性事实（"我帮你点杯咖啡"、"等会儿吃饭"）——不是承诺，不写
                  - 重复承诺（chat memory 显示之前已写过同样内容）——不重复写
                  - 缺前缀的描述（"梳理 React 路径"）——前缀必填，否则承诺方不清
                """;
    }

    @Tool("承诺记录：识别到对话双方做出的承诺时调用，写入长期记忆。"
            + "AI 答应对方、对方答应 AI、双方共同约定，三类承诺都进本工具。"
            + "description 必须以「我（AI）答应{对方称呼}：」/「{对方称呼}答应我：」/「我们约定：」三种前缀之一开头。")
    public String record_commitment(
            @ToolMemoryId String memoryId,
            @P("承诺的完整描述，必须带前缀。"
                    + "例：\"我（AI）答应{对方称呼}：本周帮其梳理 React 学习路径\" / "
                    + "\"{对方称呼}答应我：本周内 review 完代码\" / "
                    + "\"我们约定：一起读 SICP\"。"
                    + "{对方称呼} 占位用已知 username；未知时用「对方」。") String description) {
        String userId = anchorMemoryDao.findUserId(memoryId);
        if (userId == null) {
            log.error("record_commitment: unknown memoryId={}", memoryId);
            return "record_commitment 调用失败：未识别的对话上下文";
        }
        if (description == null || description.isBlank()) {
            return "record_commitment 调用失败：description 不能为空";
        }
        String trimmed = description.trim();
        String key = UUID.randomUUID().toString();
        log.info("📝 record_commitment [userId={}] uuid={} description={}", userId, key, trimmed);
        // 暂存不落库、不建索引——本轮 LLM 成功跑完才 commit（ChatService.persistTurn），失败/停止随 pending 丢弃
        hotMemoryService.stage(memoryId, userId, HotMemoryType.COMMITMENT, key, trimmed, trimmed);
        return "已记下承诺：" + trimmed;
    }
}
