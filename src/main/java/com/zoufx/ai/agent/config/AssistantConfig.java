package com.zoufx.ai.agent.config;

import com.zoufx.ai.agent.assistant.ChatAssistant;
import com.zoufx.ai.agent.tool.TavilySearchTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

/**
 * 装配两个 ChatAssistant Bean：分别绑定 thinking / nonThinking 的 StreamingChatModel。
 * 共享同一个 ChatMemoryProvider——同 sessionId 跨模式切换历史连续。
 * 两者均挂载 TavilySearchTool 提供网络检索能力。
 * 系统提示由 systemMessageProvider 在每次调用时动态生成，注入当前日期供模型生成正确的搜索 query。
 */
@Configuration
public class AssistantConfig {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA);

    private static final String SYSTEM_TEMPLATE = """
            你是中文 AI 助手。当前日期：%s。
            
            遇到以下情况必须调用 search_web 工具进行网络检索：
            - 涉及实时/最新信息（今天、最近、本周、当前年度及以后的事件）
            - 具体产品、公司、人物的近期动态或版本发布
            - 用户明确说「搜一下」「查一下」「上网查」等
            
            生成搜索关键词时必须结合上述当前日期，不要使用过时的年份。
            搜索完成后综合返回结果用自然语言回答，并在末尾列出参考链接。
            静态知识问题（语法、概念、历史常识）直接回答，不要滥用搜索。
            """;

    private static Function<Object, String> dynamicSystemMessageProvider() {
        return memoryId -> SYSTEM_TEMPLATE.formatted(LocalDate.now().format(DATE_FMT));
    }

    @Bean
    public ChatAssistant thinkingAssistant(
            @Qualifier("thinkingChatModel") StreamingChatModel model,
            ChatMemoryProvider chatMemoryProvider,
            TavilySearchTool tavilySearchTool) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(dynamicSystemMessageProvider())
                .tools(tavilySearchTool)
                .build();
    }

    @Bean
    public ChatAssistant nonThinkingAssistant(
            @Qualifier("nonThinkingChatModel") StreamingChatModel model,
            ChatMemoryProvider chatMemoryProvider,
            TavilySearchTool tavilySearchTool) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(dynamicSystemMessageProvider())
                .tools(tavilySearchTool)
                .build();
    }
}
