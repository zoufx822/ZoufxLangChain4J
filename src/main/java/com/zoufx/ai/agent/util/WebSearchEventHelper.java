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
     */
    public static String toolResultPayload(String tool, int count, String rawResult) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", tool);
        node.put("count", count);
        node.put("resultPreview", truncate(rawResult, 200));
        return node.toString();
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
