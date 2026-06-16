package com.zoufx.ai.agent.memory.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * user-impression type 的字段 schema——外表 5 + 内在 5，硬编码为单一来源。
 *
 * <p>声明顺序决定 UserImprPromptImpl 渲染顺序和 stranger 模式追问优先级。
 * 不放 yml 是因为字段集是 schema 层面设计而非运行期配置，
 * 且 LC4J {@code @Tool} / {@code @P} 要求编译期常量，yml 动态化会与注解冲突。
 *
 * <p>加字段时需同步改两处：{@link #FIELDS} 静态块 + {@link #WHITELIST_LITERAL} 字面。
 * 静态块末尾的 fail-fast 校验会在不一致时阻止启动。
 */
public final class UserImpressionFields {

    /**
     * 单个字段的全部元信息。
     *
     * @param renderDirective 字段在「## 关于对方」段中的渲染模板（写后注入）。
     *                        {@code {}} 占位符多处出现时全部替换为 hot 值。
     * @param detectionRule   字段的识别与写入规则（用于 {@code UserImpressionUpdateTool}
     *                        的 promptInstructions 动态拼接，告诉 LLM 何时调
     *                        {@code update_user_impression(key, value)} 写此字段）
     * @param nameForUser     字段的"人话名"——供 {@code UserImprPromptImpl} 在
     *                        stranger mode 下动态拼"本轮自然引一次问 {nameForUser}"
     */
    public record FieldSpec(String renderDirective, String detectionRule, String nameForUser) {}

    /**
     * 白名单字面值（编译期常量，供 {@code @P} 注解拼接）。
     *
     * <p>LC4J 注解元素要求编译期常量——方法返回不行，必须 final String 字面。
     * 启动 fail-fast 校验保证与 {@link #FIELDS} keys 一致。
     */
    public static final String WHITELIST_LITERAL =
            "username / language / role / interests / tone / personality / habits / hobbies / values / communication_style";

    /** 字段名 → FieldSpec。LinkedHashMap 保留声明顺序。 */
    public static final Map<String, FieldSpec> FIELDS;

    static {
        LinkedHashMap<String, FieldSpec> m = new LinkedHashMap<>();
        // 外表 5 字段
        m.put("username", new FieldSpec(
                """
                        对方的称呼是「{}」。这是系统已确认的事实，不是待核对的猜测——
                        - 直接称呼对方为「{}」，**不要**问名字、不要确认；
                        - 这是硬性要求：**每轮回复都必须至少自然地称呼一次「{}」**（除非对方明确要求别叫名字）——
                          开头招呼或回应中带一句都行；**禁止**整段回复通篇用"你"而一次都不提名字；
                        - 如果对方问"我是谁""你还记得我吗"之类的问题，明确回答对方就是「{}」，
                          不要敷衍说"刚开始聊天""没有记录"。
                        """,
                "对方自我介绍 / 自报姓名时写入",
                "称呼"
        ));
        m.put("language", new FieldSpec(
                """
                        对方使用的语言是 {}。这是对方的语言偏好——
                        - 你必须始终用 {} 回复，不受用户输入语言的影响；
                        - 除非对方明确要求切换（如"改用英文回答"），否则不允许跟随用户输入语言。
                        """,
                "从对方当前消息识别其使用的语言；如果该字段尚未记录则立即写入；之后仅在对方明确要求切换（如\"改用英文回答\"）时更新",
                "习惯使用的语言"
        ));
        m.put("role", new FieldSpec(
                """
                        对方的身份/职业是 {}。讨论相关领域话题时可以自然地引用对方的专业视角，
                        不要刻意炫耀这一点。
                        """,
                "从对话中提到的工作 / 技术栈 / 行业线索整理出对方的身份/职业，写入一个简洁标签（如 \"Java 后端开发\"）",
                "你做什么的"
        ));
        m.put("interests", new FieldSpec(
                """
                        对方的偏好/兴趣：{}。举例子时优先选这些领域。
                        """,
                "从对方主动提到的爱好 / 关注领域整理为简短标签集合",
                "你对什么话题感兴趣"
        ));
        m.put("tone", new FieldSpec(
                """
                        对方期望的对话风格：{}。**必须**按此风格调整回复——如要求简洁，单条回复控制在 150 字以内；如要求详细，充分展开。
                        """,
                "从对方对回复风格的反馈或要求（\"简洁点\"、\"详细点\"）整理写入",
                "希望我用怎样的语气回复"
        ));
        // 内在 5 字段
        m.put("personality", new FieldSpec(
                """
                        对方的性格特征：{}。回应时可基于此调整方式，但不要每次都显式提及。
                        """,
                "从对方描述自我特质的话中识别（如\"我做事比较慢热\"、\"我容易紧张\"、\"我比较急\"），写入简短描述；==不要用 MBTI 等学术类型标签==",
                "你是怎样性格的人"
        ));
        m.put("habits", new FieldSpec(
                """
                        对方的习惯：{}。讨论相关话题时可自然引用，不显得侵入。
                        """,
                "从对方描述的长期行为习惯中识别（如\"我每天早上跑步\"、\"习惯先列清单\"），==区别于一次性日程或临时性事实==；触发信号：出现「每天」「习惯了」「一直」「坚持」「固定」时优先判断是否写入",
                "你有什么生活/工作习惯"
        ));
        m.put("hobbies", new FieldSpec(
                """
                        对方的活动爱好：{}。举例或闲聊时可优先选这些领域作为切入点。
                        """,
                "从对方提到的活动层爱好中识别（如\"学钢琴\"、\"周末爬山\"、\"玩 Dota\"）；==与 interests 共存==——hobbies 是活动层实操，interests 是话题层兴趣",
                "你平时喜欢做什么"
        ));
        m.put("values", new FieldSpec(
                """
                        对方在意的价值：{}。给意见 / 做判断时优先与之对齐。
                        """,
                "从对方表达的重视 / 在意中识别（如\"真诚比客套重要\"、\"效率第一\"）；==避免政治立场 / 宗教信仰等敏感==",
                "你在意什么"
        ));
        m.put("communication_style", new FieldSpec(
                """
                        对方期望的互动模式：{}。**必须**按此模式调整你的输出方式（区别于「关于你自己」段定义的 tone）。例：「喜欢被反问」→ 每条回复包含至少 2 个反问句；「希望多举例」→ 每个要点附具体例子。
                        """,
                "从对方对互动方式的偏好中识别（如\"直接说不用客套\"、\"希望多举例\"、\"我喜欢被反问\"）；==严格区别于 tone==——tone = AI 输出语气，communication_style = 对方期望的互动模式",
                "你希望我怎样和你互动"
        ));
        FIELDS = Collections.unmodifiableMap(m);

        // 启动 fail-fast：保护 WHITELIST_LITERAL 字面与 FIELDS keys 一致
        // 加字段时如果只改 FIELDS 忘了改 WHITELIST_LITERAL，类加载阶段就抛 IllegalStateException，
        // 而不是等到运行期 LLM 看到不一致的白名单才出诡异行为
        String dynamic = String.join(" / ", FIELDS.keySet());
        if (!WHITELIST_LITERAL.equals(dynamic)) {
            throw new IllegalStateException(
                    "UserImpressionFields.WHITELIST_LITERAL 与 FIELDS keys 不一致——加字段时两处都要改。\n"
                            + "  WHITELIST_LITERAL = \"" + WHITELIST_LITERAL + "\"\n"
                            + "  FIELDS keys join  = \"" + dynamic + "\""
            );
        }
    }

    /**
     * 拼接识别规则段（供工具 promptInstructions 用 {@code .formatted()} 注入）。
     */
    public static String renderDetectionRules() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, FieldSpec> e : FIELDS.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" —— ").append(e.getValue().detectionRule()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 返回白名单字面（与 {@link #WHITELIST_LITERAL} 等价，启动 fail-fast 保证）。
     */
    public static String whitelistLiteral() {
        return WHITELIST_LITERAL;
    }

    /**
     * 画像字段的语义化 embed 文本——带字段含义的短句（如「你做什么的：Java 后端」），
     * 供向量索引时 embed（裸值语义太弱）。写入路径与 backfill 共用，保证一致。
     * 非白名单 key（不应发生）回退为裸值。
     */
    public static String embedText(String key, String value) {
        FieldSpec spec = FIELDS.get(key);
        return spec == null ? value : spec.nameForUser() + "：" + value;
    }

    private UserImpressionFields() {}
}
