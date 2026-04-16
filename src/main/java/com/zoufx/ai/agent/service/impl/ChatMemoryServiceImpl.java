package com.zoufx.ai.agent.service.impl;

import com.zoufx.ai.agent.service.ChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版本的聊天记忆服务实现
 * 使用 ConcurrentHashMap 存储会话，支持多会话
 * 后续如需切换到 Redis，只需新建 ChatMemoryServiceImpl 实现 ChatMemoryService 接口
 */
@Slf4j
@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private static final int MAX_MESSAGES = 20;

    /**
     * 使用 ConcurrentHashMap 存储会话消息
     * Key: sessionId
     * Value: CopyOnWriteArrayList，保证并发写入安全
     */
    private final ConcurrentHashMap<String, List<String>> sessionMessages = new ConcurrentHashMap<>();

    @Override
    public void addUserMessage(String sessionId, String message) {
        log.debug("Adding user message to session {}: {}", sessionId, message);
        addMessage(sessionId, "User: " + message);
    }

    @Override
    public void addAssistantMessage(String sessionId, String message) {
        log.debug("Adding assistant message to session {}: {}", sessionId, message);
        addMessage(sessionId, "Assistant: " + message);
    }

    @Override
    public List<String> getHistory(String sessionId) {
        List<String> messages = sessionMessages.getOrDefault(sessionId, new CopyOnWriteArrayList<>());
        log.debug("Retrieved {} messages from session {}", messages.size(), sessionId);
        return new CopyOnWriteArrayList<>(messages);
    }

    @Override
    public void clear(String sessionId) {
        log.info("Clearing session memory: {}", sessionId);
        sessionMessages.remove(sessionId);
    }

    /**
     * 内部方法：添加消息到指定会话，超出上限时从头部裁剪
     */
    private void addMessage(String sessionId, String message) {
        List<String> list = sessionMessages.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(message);
        if (list.size() > MAX_MESSAGES) {
            list.subList(0, list.size() - MAX_MESSAGES).clear();
        }
    }
}
