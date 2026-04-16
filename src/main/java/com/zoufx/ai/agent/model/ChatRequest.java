package com.zoufx.ai.agent.model;

import lombok.Data;

/**
 * 聊天请求 DTO
 */
@Data
public class ChatRequest {
    private String prompt;
    private String sessionId;
    /**
     * 是否启用思考模式，默认 false
     */
    private boolean thinking = false;
}
