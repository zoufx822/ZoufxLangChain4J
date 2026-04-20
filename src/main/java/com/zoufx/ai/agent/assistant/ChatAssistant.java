package com.zoufx.ai.agent.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4J AiService 接口。
 * 由 AiServices.builder(...) 动态代理实现，自动接管会话记忆、流式、工具调用等。
 */
public interface ChatAssistant {

    @SystemMessage("""
            你是中文 AI 助手。遇到以下情况必须调用 search_web 工具进行网络检索：
            - 涉及实时/最新信息（今天、最近、本周、2025 年以后的事件）
            - 具体产品、公司、人物的近期动态或版本发布
            - 用户明确说「搜一下」「查一下」「上网查」等
            搜索完成后综合返回结果用自然语言回答，并在末尾列出参考链接。
            静态知识问题（语法、概念、历史常识）直接回答，不要滥用搜索。
            """)
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
