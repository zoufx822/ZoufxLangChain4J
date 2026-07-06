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
 * 重要经历记录工具——专管 hot_memory significant-event type（叙事性 append-only，
 * key=UUID 防冲突）。
 *
 * <p>语义边界：情绪显著的人生事件 / 长期处境 / 带时间标记的具体经历 → 写本工具；
 * 临时性事实 / 偏好习惯 → 分别忽略或写 user-impression。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignificantEventRecordTool implements ToolPrompt {

    private final HotMemoryService hotMemoryService;
    private final AnchorMemoryDao anchorMemoryDao;

    @Override
    public String section() {
        return "重要经历记录";
    }

    @Override
    public Map<String, String> methodSections() {
        return Map.of("record_significant_event", section());
    }

    @Override
    public String promptInstructions() {
        return """
                ==必须触发==：识别到对方的重要经历 / 共享事件时，立刻调用 record_significant_event(description)。

                命中信号：
                - 情绪显著的人生事件：亲人去世 / 重大成就 / 失败 / 婚恋 / 健康变化
                - 长期处境：在备考 / 在转行 / 异地分居 / 长期照护家人
                - 第一次提到的、带时间标记的具体经历（如"去年我父亲去世了"、"上个月辞职了"）

                调用规则：
                - description 写完整的叙事性描述（"去年父亲去世"、"正在备考研究生"），不要只写关键词
                - 一轮可调用多次（多个独立事件分别写）
                - 调用是后台动作，回复对方时无需说"我已记下"
                - 反模式：
                  - 临时性事实（"刚下班"、"今天迟到了"、"等会儿吃饭"）——不写
                  - 偏好/习惯（"喜欢爬山"、"早起跑步"）——属于 user-impression 的 hobbies / habits，不写本工具
                  - 已经写过的事件（chat memory 显示之前提过 + 重要经历段已含）——不重复写
                """;
    }

    @Tool("重要经历记录：识别到对方的情绪显著人生事件 / 长期处境 / 第一次提到的带时间标记的具体经历时调用，"
            + "写入长期记忆。叙事性 append-only —— 每次调用产生一条独立记录。")
    public String record_significant_event(
            @ToolMemoryId String memoryId,
            @P("事件的完整叙事描述。例：\"去年父亲去世\"、\"正在备考研究生\"、\"刚结束一段七年的感情\"。"
                    + "不要只写关键词；不要包含时间戳（系统自动记录）。") String description) {
        String userId = anchorMemoryDao.findUserId(memoryId);
        if (userId == null) {
            log.error("record_significant_event: unknown memoryId={}", memoryId);
            return "record_significant_event 调用失败：未识别的对话上下文";
        }
        if (description == null || description.isBlank()) {
            return "record_significant_event 调用失败：description 不能为空";
        }
        String trimmed = description.trim();
        String key = UUID.randomUUID().toString();
        log.info("📝 record_significant_event [userId={}] uuid={} description={}", userId, key, trimmed);
        // 暂存不落库、不建索引——本轮 LLM 成功跑完才 commit（ChatService.persistTurn），失败/停止随 pending 丢弃
        hotMemoryService.stage(memoryId, userId, HotMemoryType.SIGNIFICANT_EVENT, key, trimmed, trimmed);
        return "已记下重要经历：" + trimmed;
    }
}
