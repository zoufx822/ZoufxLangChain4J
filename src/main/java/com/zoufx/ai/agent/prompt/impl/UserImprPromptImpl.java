package com.zoufx.ai.agent.prompt.impl;

import com.zoufx.ai.agent.prompt.api.Prompt;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.memory.support.UserImpressionFields;
import com.zoufx.ai.agent.memory.support.UserImpressionFields.FieldSpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用户画像段（order=20）——同一份 user-impression snapshot 渲染两段：
 * 「## 关于对方」（已识别字段套 {@link FieldSpec#renderDirective()} 模板）+
 * 「## 你对对方的了解程度」（按 fill_ratio 引导主动了解）。
 *
 * <p>了解程度按 fill_ratio 落入三档：stranger（ratio &lt; 0.3 → 追问一个字段 + 深度话题拒答）、
 * half-known（0.3~0.7 → 适度追问 + 保留意见）、fully-known（≥ 0.7 → 不追问，引用已知）。
 * stranger 模式下按 FIELDS 声明顺序找未填字段第 1 名，动态拼接追问指令替换
 * {@code {fieldQuestion}} 占位。
 */
@Slf4j
@Component
public class UserImprPromptImpl implements Prompt {

    private static final String CLOSENESS_HEADER = "## 你对对方的了解程度\n\n";
    private static final String STRANGER = "stranger";
    private static final String HALF_KNOWN = "half-known";
    private static final String FULLY_KNOWN = "fully-known";
    private static final String FIELD_QUESTION_PLACEHOLDER = "{fieldQuestion}";

    static final String STRANGER_PROMPT = """
            你和对方刚认识不久，对 ta 还不太了解。在本轮回复里：
            - ==优先==：若对方本轮主动提供了任何画像信息（自报名字、职业、爱好等），立刻调 update_user_impression 写入，再继续回复——不能只口头确认而不调工具
            - 先把对方这句话本身聊好（不打断、不绕题、有来有回）
            - 出于真实的好奇了解 ta：==只在对话出现自然话口时==顺势问一句（系统这轮想了解的是：{fieldQuestion}）；没有合适话口就这轮不问——别硬塞、别每轮都问、别连珠炮追问
            - ==首次交谈例外==：若这是你们第一次说话（chat memory 为空）且你还不知道怎么称呼对方，则无论对方说什么、有没有自然话口，都在回应之后自然地补问一次怎么称呼——这是破冰的起点，不算硬塞
            - 若 chat memory 显示你之前已问过该字段且对方没正面答，或对方明确表示不想说（"不用问""没必要"等），==就别再追问==
            - 面对"情感 / 人生 / 选择"等深度话题：==先给出有温度、有分量的回应==（共情 + 你的真实想法），再在自然处流露想多了解 ta 处境的好奇——==不要==用"我们刚认识，先了解你再给意见"把对方挡回去
            基调：你是因为==真的对这个人感兴趣==才想了解 ta，不是在完成一张信息收集表。
            """;
    static final String HALF_KNOWN_PROMPT = """
            你已经认识对方一段时间，但对其内在仍不够了解。在合适的时机：
            - ==优先==：若对方本轮主动提供了任何画像信息，立刻调 update_user_impression 写入，再继续回复——不能只口头确认而不调工具
            - 有自然话口时顺势穿插一句了解，但不要每轮都问，宽松些（每 2~3 轮一次就够）
            - 当对方分享细节时，主动调 update_user_impression 写入推断到的字段
            - 深度话题给意见时，自然地带出你对 ta 的了解（如「以我对你的了解…」），让建议更贴合——不必生硬套固定开头
            """;
    static final String FULLY_KNOWN_PROMPT = """
            你已经较充分地了解对方。在回答时：
            - 使用对方习惯的称呼与沟通风格
            - 个性化判断 / 建议时**必须**显式引用对方特质，格式：「（你之前提过 X，所以...）」，不得用隐含方式替代
            - 仍在对话中持续观察新信号，必要时 update_user_impression 补充或修正
            """;

    private static final double STRANGER_THRESHOLD = 0.3;
    private static final double FULLY_KNOWN_THRESHOLD = 0.7;

    @Override
    public int order() {
        return 20;
    }

    @Override
    @Nullable
    public String render(@Nullable PromptContext ctx) {
        if (ctx == null || ctx.userId() == null) return null;
        Map<String, String> snap = ctx.hotImpressionSnap();

        StringBuilder sb = new StringBuilder();
        renderImpression(sb, snap);
        renderCloseness(sb, snap);
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 「## 关于对方」——按 FIELDS 声明顺序渲染非空字段，全空则段不出现。 */
    private void renderImpression(StringBuilder sb, Map<String, String> snap) {
        int start = sb.length();
        sb.append("## 关于对方\n\n");
        boolean any = false;
        for (Map.Entry<String, FieldSpec> e : UserImpressionFields.FIELDS.entrySet()) {
            String value = snap.get(e.getKey());
            if (value == null || value.isBlank()) continue;
            any = true;
            String directive = e.getValue().renderDirective();
            sb.append(directive.replace("{}", value));
            if (!directive.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        if (!any) sb.setLength(start);
    }

    /** 「## 你对对方的了解程度」——fill_ratio 三档引导。 */
    private void renderCloseness(StringBuilder sb, Map<String, String> snap) {
        int total = UserImpressionFields.FIELDS.size();
        if (total == 0) return;
        int filled = 0;
        for (String key : UserImpressionFields.FIELDS.keySet()) {
            String v = snap.get(key);
            if (v != null && !v.isBlank()) filled++;
        }
        double fillRatio = (double) filled / total;

        String mode = resolveMode(fillRatio);
        String template = promptForMode(mode);
        if (template == null) {
            log.warn("UserImprPromptImpl closeness skipped: no prompt for mode={} (fillRatio={})",
                    mode, fillRatio);
            return;
        }

        String rendered = template;
        if (STRANGER.equals(mode) && rendered.contains(FIELD_QUESTION_PLACEHOLDER)) {
            rendered = rendered.replace(FIELD_QUESTION_PLACEHOLDER, buildFieldQuestion(snap));
        }
        sb.append(CLOSENESS_HEADER).append(rendered);
    }

    private String promptForMode(String mode) {
        return switch (mode) {
            case STRANGER -> STRANGER_PROMPT;
            case HALF_KNOWN -> HALF_KNOWN_PROMPT;
            case FULLY_KNOWN -> FULLY_KNOWN_PROMPT;
            default -> null;
        };
    }

    private String resolveMode(double fillRatio) {
        if (fillRatio < STRANGER_THRESHOLD) return STRANGER;
        if (fillRatio < FULLY_KNOWN_THRESHOLD) return HALF_KNOWN;
        return FULLY_KNOWN;
    }

    /**
     * 按 FIELDS 声明顺序找未填字段第 1 名，拼追问指令。
     *
     * <p>仅在 stranger mode 下调（fillRatio < 0.3 → 至少 70% 字段未填，循环必命中）。
     * 若配置错误导致找不到未填字段，抛 {@link IllegalStateException} fail-fast 而非塞空串。
     */
    private String buildFieldQuestion(Map<String, String> snap) {
        for (Map.Entry<String, FieldSpec> e : UserImpressionFields.FIELDS.entrySet()) {
            String v = snap.get(e.getKey());
            if (v == null || v.isBlank()) {
                return "对方的" + e.getValue().nameForUser();
            }
        }
        throw new IllegalStateException(
                "UserImprPromptImpl.buildFieldQuestion 在 stranger mode 下未找到未填字段，请检查 UserImpressionFields.FIELDS 定义");
    }
}
