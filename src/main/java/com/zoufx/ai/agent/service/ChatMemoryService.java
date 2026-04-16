package com.zoufx.ai.agent.service;

import java.util.List;

/**
 * 聊天记忆服务接口 - 定义会话记忆的基本操作
 * 后续可通过实现类切换到 Redis 等其他存储方案
 */
public interface ChatMemoryService {

    /**
     * 添加用户消息到会话
     * @param sessionId 会话 ID
     * @param message 用户消息
     */
    void addUserMessage(String sessionId, String message);

    /**
     * 添加 AI 助手消息到会话
     * @param sessionId 会话 ID
     * @param message AI 助手消息
     */
    void addAssistantMessage(String sessionId, String message);

    /**
     * 获取会话历史消息
     * @param sessionId 会话 ID
     * @return 消息列表，按时间顺序排列
     */
    List<String> getHistory(String sessionId);

    /**
     * 清理指定会话的记忆
     * @param sessionId 会话 ID
     */
    void clear(String sessionId);
}
