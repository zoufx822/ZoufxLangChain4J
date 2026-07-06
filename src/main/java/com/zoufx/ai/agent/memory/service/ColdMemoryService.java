package com.zoufx.ai.agent.memory.service;

import com.zoufx.ai.agent.memory.api.ColdMemoryDao;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 长期归档业务层——委托 {@link ColdMemoryDao}。append 本版起只由
 * {@code ChatService.persistTurn} 在事务内调用（成功才落库），为日后换 R2DBC 只动 Dao 铺路。
 */
@Service
@RequiredArgsConstructor
public class ColdMemoryService {

    private final ColdMemoryDao dao;

    /** 追加一条经历流记录，返回新行自增 id——调用方需已在事务上下文。 */
    public long append(String userId, String role, String content, @Nullable String mood) {
        return dao.append(userId, role, content, null, mood);
    }
}
