package com.zoufx.ai.agent.tool.support;

import com.zoufx.ai.agent.base.support.JsonStrings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web 搜索类工具的 SSE 事件 payload 工厂——构造 {@code tool_call} / {@code tool_result} JSON、解析工具入参、统计结果条数。
 *
 * <p>纯静态工具集，无状态。被 chat service 流式处理链路与工具内部回调共用。
 */
@Slf4j
public final class WebSearchEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebSearchEvents() {
    }

    /** 从工具入参 JSON 字符串中提取 query 字段。 */
    public static String extractQuery(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) return "";
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            JsonNode q = node.get("query");
            return q != null ? q.asText("") : "";
        } catch (JsonProcessingException e) {
            log.debug("extractQuery parse failed: {}", e.getMessage());
            return "";
        }
    }

    /** 构造 tool_call 事件 payload，含工具方法名（英文）与展示名（中文）。 */
    public static String toolCallPayload(String tool, String chineseName, String query) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", tool);
        node.put("toolDisplay", chineseName);
        node.put("query", query);
        return node.toString();
    }

    /**
     * 构造 tool_result 事件 payload。
     *
     * <p>resultPreview 字段==手动转义==而非走 Jackson——SSE 流里不应出现真实换行符，
     * 用字符串拼接 + escapeJsonString 兜底确保 payload 单行安全。
     */
    public static String toolResultPayload(String tool, String chineseName, int count, String rawResult) {
        String escaped = JsonStrings.escape(truncate(rawResult, 200));
        return String.format("{\"tool\":\"%s\",\"toolDisplay\":\"%s\",\"count\":%d,\"resultPreview\":\"%s\"}",
                tool, chineseName, count, escaped);
    }

    /**
     * 数工具返回内容里的条目数，兼容两种列表格式：
     * <ul>
     *   <li>Bullet 列表（search_web 用）：行首或换行后是 {@code "- "}</li>
     *   <li>编号列表（search_cold_memory 用）：行首或换行后是 {@code "1. " / "2. " ...}</li>
     * </ul>
     * 用一个正则在 multi-line 模式下统一计数，避免针对每个工具的输出分支判断。
     */
    public static int countResults(String result) {
        if (!StringUtils.hasText(result)) return 0;
        int count = 0;
        Matcher m = LIST_ITEM_PATTERN.matcher(result);
        while (m.find()) count++;
        return count;
    }

    /** 匹配列表条目首字符：行首（^）或换行后跟 "- " 或 "数字. "。用 (?m) 让 ^ 匹配每行起点。 */
    private static final Pattern LIST_ITEM_PATTERN =
            Pattern.compile("(?m)^(?:-\\s|\\d+\\.\\s)");

    /** 截断字符串到指定长度，超过部分用 "…" 代替。 */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
