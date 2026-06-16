package com.zoufx.ai.agent.prompt.api;

import com.zoufx.ai.agent.prompt.support.PromptContext;
import org.jspecify.annotations.Nullable;

/**
 * System prompt 的可组合段——SPI 契约。
 *
 * <p>各业务模块实现本接口并注册为 Spring Bean，由 {@code PromptComposer} 按
 * {@link #order()} 升序串行调用 {@link #render(PromptContext)} 拼接为完整 system prompt。
 * 实现一律放 {@code prompt/impl/}，业务模块只通过 api/property 暴露数据源。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>render 由 LC4J 在 WebFlux event loop 上同步内联调用——禁止 {@code .block()}，
 *       禁止任何 DB / 网络调用；所有数据已由 {@code ChatService.prepare()} 预加载至
 *       {@link PromptContext}</li>
 *   <li>返回 null 或空串表示本段在当前请求跳过</li>
 *   <li>实现必须无状态——状态仅通过 ctx 传入</li>
 * </ul>
 */
public interface Prompt {

    /** 注入顺序（升序）。建议保留间隔（如 10/20/30/...）方便未来插入新 Section。 */
    int order();

    /**
     * 渲染本段内容。
     *
     * @param ctx 当前请求预加载的 prompt 数据，{@code prepare()} 未能完成时为 null。
     * @return 段内容，已包含必要的换行；null / 空串表示本段在当前请求跳过。
     */
    @Nullable String render(@Nullable PromptContext ctx);
}
