package com.zoufx.ai.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 网络检索 SSE 事件的构建和解析工具。
 */
@Slf4j
public class WebSearchEventHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从工具入参 JSON 字符串中提取 query 字段。
     */
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

    /**
     * 构建 tool_call 事件的 JSON payload。
     */
    public static String toolCallPayload(String tool, String query) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", tool);
        node.put("query", query);
        return node.toString();
    }

    /**
     * 构建 tool_result 事件的 JSON payload。
     * 手动转义特殊字符以确保 SSE 兼容性。
     * 不能依赖 Jackson，因为 SSE 流中不应该有真实换行符。
     */
    public static String toolResultPayload(String tool, int count, String rawResult) {
        String preview = truncate(rawResult, 200);
        // 对 resultPreview 进行 JSON 转义
        String escaped = escapeJsonString(preview);
        // 直接使用字符串拼接，确保没有真实换行符进入 JSON
        return String.format("{\"tool\":\"%s\",\"count\":%d,\"resultPreview\":\"%s\"}", tool, count, escaped);
    }

    /**
     * 转义字符串以符合 JSON 标准和 SSE 要求。
     */
    private static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 数搜索结果的条数（以 "\n- " 分隔符计数）。
     */
    public static int countResults(String result) {
        if (!StringUtils.hasText(result)) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = result.indexOf("\n- ", idx)) != -1) {
            count++;
            idx += 3;
        }
        return result.startsWith("- ") ? count + 1 : count;
    }

    /**
     * 截断字符串到指定长度，超过部分用 "…" 代替。
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
