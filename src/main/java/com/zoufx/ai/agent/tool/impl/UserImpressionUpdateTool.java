package com.zoufx.ai.agent.tool.impl;

import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.service.HotMemoryService;
import com.zoufx.ai.agent.memory.support.HotMemoryType;
import com.zoufx.ai.agent.memory.support.UserImpressionFields;
import com.zoufx.ai.agent.tool.api.ToolPrompt;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用户印象更新工具——专管 hot_memory user-impression type（结构化 KV，UPSERT）。
 *
 * <p>字段 schema 和白名单的单一来源为 {@link UserImpressionFields}。
 * {@code @P} 注解白名单通过 {@code UserImpressionFields.WHITELIST_LITERAL} 编译期拼接
 * ——LC4J {@code @P} 要求编译期常量，方法返回值不行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserImpressionUpdateTool implements ToolPrompt {

    private final HotMemoryService hotMemoryService;
    private final AnchorMemoryDao anchorMemoryDao;

    @Override
    public String section() {
        return "用户印象更新";
    }

    @Override
    public Map<String, String> methodSections() {
        return Map.of("update_user_impression", section());
    }

    @Override
    public String promptInstructions() {
        // 识别规则段 + 白名单字面 由 UserImpressionFields 动态拼接——
        // 加字段时只改 UserImpressionFields.FIELDS，本方法的字面文本无需同步
        return """
                ==必须触发==：识别到对方的任何画像属性时，立刻调用 update_user_impression(key, value)。

                各字段的识别与写入规则：

                %s
                调用规则：
                - key 必须从以下白名单中选：%s
                - value 只传==事实本身==（如 "Java 后端" 而不是 "我是 Java 后端"）
                - 一轮内可调用多次（同时识别多个字段）
                - 调用是后台动作，回复对方时无需说"我已记下"
                - 反模式：
                  - 写白名单外的 key——会被拒绝
                  - 单次零散信号就武断推断（如对方说"今天写了几行代码"不能直接断定职业；
                    需要明确或多次信号才整理写入）
                  - 在回复里说"好的我会记住"但实际==没调工具==
                """.formatted(
                        UserImpressionFields.renderDetectionRules(),
                        UserImpressionFields.whitelistLiteral()
                );
    }

    @Tool("用户印象更新：识别到对方的属性（外表与内在 —— 称呼/语言/职业/兴趣/对话风格/性格/习惯/爱好/价值观/互动期望）"
            + "时调用，写入长期记忆。包括对方明确告知，也包括对话内容中明确或多次出现的相关信号。")
    public String update_user_impression(
            @ToolMemoryId String memoryId,
            // 白名单字面通过 UserImpressionFields.WHITELIST_LITERAL 编译期拼接——
            // 加字段时只改 UserImpressionFields 一处，本注解自动跟随
            @P("属性字段名。必须从白名单选：" + UserImpressionFields.WHITELIST_LITERAL) String key,
            @P("属性值。只传事实本身，不带「我是」「我叫」「我在」等前缀") String value) {
        String userId = anchorMemoryDao.findUserId(memoryId);
        if (userId == null) {
            log.error("update_user_impression: unknown memoryId={}", memoryId);
            return "update_user_impression 调用失败：未识别的对话上下文";
        }
        if (key == null || key.isBlank()) {
            return "update_user_impression 调用失败：key 不能为空";
        }
        if (value == null || value.isBlank()) {
            return "update_user_impression 调用失败：value 不能为空";
        }
        String trimmedKey = key.trim();
        String trimmedValue = value.trim();
        if (!UserImpressionFields.FIELDS.containsKey(trimmedKey)) {
            log.warn("⛔ update_user_impression rejected [userId={}] key='{}' not in whitelist", userId, trimmedKey);
            return "update_user_impression 调用失败：key '" + trimmedKey + "' 不在允许字段列表内";
        }
        log.info("📝 update_user_impression [userId={}] {}={}", userId, trimmedKey, trimmedValue);
        // 暂存不落库、不建索引——本轮 LLM 成功跑完才 commit（ChatService.persistTurn），失败/停止随 pending 丢弃
        // embedText 带字段语义的短句（如「你做什么的：Java 后端」），UPSERT 由确定性 id 保证
        String embedText = UserImpressionFields.embedText(trimmedKey, trimmedValue);
        hotMemoryService.stage(memoryId, userId, HotMemoryType.USER_IMPRESSION, trimmedKey, trimmedValue, embedText);
        return "已记下：" + trimmedKey + "=" + trimmedValue;
    }
}
