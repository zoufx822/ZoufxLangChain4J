package com.zoufx.ai.agent.tool.api;

import java.util.Map;

/**
 * 工具的 prompt 契约——每个工具自带使用说明（何时调用 / 调用规则），
 * 由 {@code ToolsPromptImpl} 聚合拼入 system prompt。
 */
public interface ToolPrompt {

    /** 工具在 system prompt 里的小节标题 */
    String section();

    /** 工具使用规则正文（无占位符替换——日期信息由 prompt 顶部「当前日期」提供）。 */
    String promptInstructions();

    /**
     * 本工具所有 @Tool 方法名 → 显示名映射（LC4J 回调只能拿到 Java 方法名）。
     * 默认空映射——未声明的方法由 {@code ToolNameMap} 回退显示原 Java 方法名。
     */
    default Map<String, String> methodSections() { return Map.of(); }
}
