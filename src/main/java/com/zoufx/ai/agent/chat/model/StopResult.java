package com.zoufx.ai.agent.chat.model;

/**
 * 停止一轮的响应 DTO。{@code stopped=true} = 已掐断且不落库；
 * {@code false} = 该轮已完成落库 / 已停，前端据此保留不删（避免删掉已存的成功轮，刷新又冒出来）。
 */
public record StopResult(boolean stopped) {
}
