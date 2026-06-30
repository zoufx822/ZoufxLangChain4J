package com.zoufx.ai.agent.chat.api;

import com.zoufx.ai.agent.chat.model.Thinking;
import dev.langchain4j.service.TokenStream;

/**
 * 按思考配置把一次流式对话路由到合适的模型/参数，向 ChatService 屏蔽 profile 差异。
 *
 * <p>{@code thinking} 整体传入（含 enabled + effort），便于未来扩展思考相关配置而不动签名。
 * 各 LLM profile 装配自己的实现：
 * <ul>
 *   <li>能逐请求切深度的协议（OpenAI，如 DeepSeek）——单 {@code StreamingChatModel} +
 *       单 {@link ChatAssistant}，按 thinking 现场拼 per-call 参数（含模型名 + 思考开关 + effort）；</li>
 *   <li>不能逐请求切的协议（Anthropic，如 MiniMax，thinking 只能 builder 期固定）——
 *       两个模型 Bean + 两个 assistant，只按 enabled 选其一、effort 忽略，per-call 参数传 EMPTY。</li>
 * </ul>
 */
public interface StreamingChatPort {
    TokenStream stream(String anchorId, String userMessage, Thinking thinking);
}
