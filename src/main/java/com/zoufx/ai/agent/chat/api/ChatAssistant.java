package com.zoufx.ai.agent.chat.api;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4J AiService 接口。
 * 由 AiServices.builder(...) 动态代理实现，自动接管会话记忆、流式、工具调用等。
 * {@code @MemoryId} 绑定到 anchorId（记忆按锚点窗口隔离），
 * 需要 userId 的下游通过 {@code AnchorMemoryDao.findUserId(anchorId)} 反查。
 * 系统提示由 systemMessageProvider 在运行时动态生成（注入当前日期 + 身份识别）。
 *
 * <p>{@code params} 为 per-call 请求参数：能逐请求切 thinking 的 profile（OpenAI 协议）
 * 靠它在单模型上切换思考/快档；不能的（Anthropic 协议）传 EMPTY、由 {@link StreamingChatPort}
 * 选不同模型 Bean。
 */
public interface ChatAssistant {
    TokenStream chat(@MemoryId String anchorId, @UserMessage String userMessage, ChatRequestParameters params);
}
