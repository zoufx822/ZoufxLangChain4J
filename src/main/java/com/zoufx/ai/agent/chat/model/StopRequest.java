package com.zoufx.ai.agent.chat.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 主动停止一轮的请求 DTO——{@code turnId} 由 SSE 首个 {@code turn} 事件下发，前端存下后回传定位。
 */
public record StopRequest(@NotBlank(message = "不能为空") String turnId) {
}
