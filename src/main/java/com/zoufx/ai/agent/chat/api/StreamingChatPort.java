package com.zoufx.ai.agent.chat.api;

import dev.langchain4j.service.TokenStream;

/**
 * 按思考开关把一次流式对话路由到合适的模型/参数，向 ChatService 屏蔽 profile 差异。
 *
 * <p>各 LLM profile 装配自己的实现：
 * <ul>
 *   <li>能逐请求切 thinking 的协议（OpenAI，如 DeepSeek）——单 {@code StreamingChatModel} +
 *       单 {@link ChatAssistant}，按 thinking 现场拼 per-call 参数（含模型名 + 思考开关）；</li>
 *   <li>不能逐请求切 thinking 的协议（Anthropic，如 MiniMax，thinking 只能 builder 期固定）——
 *       两个模型 Bean + 两个 assistant，按 thinking 选其一，per-call 参数传 EMPTY。</li>
 * </ul>
 */
public interface StreamingChatPort {
    TokenStream stream(String anchorId, String userMessage, boolean thinking);
}
