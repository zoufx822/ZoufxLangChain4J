package com.zoufx.ai.agent.prompt.impl;

import com.zoufx.ai.agent.prompt.api.Prompt;
import com.zoufx.ai.agent.prompt.support.PromptContext;
import com.zoufx.ai.agent.tool.api.ToolPrompt;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 「## 可用工具」段（order=30）——聚合所有 {@link ToolPrompt} Bean 的 section 标题
 * 和 promptInstructions 正文。新增工具只需实现 {@link ToolPrompt}，Spring 自动收集到本段。
 */
@Component
@RequiredArgsConstructor
public class ToolsPromptImpl implements Prompt {

    private final List<ToolPrompt> tools;

    @Override
    public int order() {
        return 30;
    }

    @Override
    @Nullable
    public String render(@Nullable PromptContext ctx) {
        if (tools.isEmpty()) return null;
        String body = tools.stream()
                .map(t -> "### " + t.section() + "\n" + t.promptInstructions())
                .collect(Collectors.joining("\n"));
        return "## 可用工具\n\n" + body + "\n";
    }
}
