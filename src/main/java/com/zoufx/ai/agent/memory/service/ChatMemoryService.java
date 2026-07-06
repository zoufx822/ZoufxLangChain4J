package com.zoufx.ai.agent.memory.service;

import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话窗口业务层——实现 LC4J {@link ChatMemoryStore} 契约，write-back 缓冲：
 * turn 内 {@code getMessages}/{@code updateMessages} 恒读写内存 {@code pending}，
 * 成功才 {@link #commit}落库，失败/取消 {@link #discard}。
 *
 * <p>{@code MessageWindowChatMemory} 是 read-through（只持 store 引用、不存消息 list），
 * 每次读写都过本类 ⟹ 本类的 pending 即唯一真相，失败只要不 commit 就无痕，无需 evict。
 *
 * <p>与 LC4J 自带的 {@code dev.langchain4j.service.memory.ChatMemoryService} 同名，靠包区分。
 */
@Service
@RequiredArgsConstructor
public class ChatMemoryService implements ChatMemoryStore {

    private final ChatMemoryDao dao;
    private final ConcurrentHashMap<String, List<ChatMessage>> pending = new ConcurrentHashMap<>();

    /** prepare 阶段调：把 committed 历史灌进 pending，turn 内 getMessages 恒读它。 */
    public void seed(String anchorId) {
        pending.put(anchorId, new ArrayList<>(dao.loadByAnchorId(anchorId)));
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String anchorId = memoryId.toString();
        List<ChatMessage> p = pending.get(anchorId);
        return p != null ? new ArrayList<>(p) : dao.loadByAnchorId(anchorId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        pending.put(memoryId.toString(), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String anchorId = memoryId.toString();
        pending.remove(anchorId);
        dao.deleteByAnchorId(anchorId);
    }

    /**
     * 成功：pending 去重后落库（参与外层事务，调用方需已在事务上下文）。
     * 只写库不清 pending——pending 由 ChatService 的 doFinally(releaseTurn) 统一 discard（成功轮必在 commit 之后）。
     */
    public void commit(String anchorId) {
        List<ChatMessage> buf = pending.get(anchorId);
        if (buf != null && !buf.isEmpty()) {
            dao.saveByAnchorId(anchorId, collapseRetryDuplicateUserMessages(buf));
        }
    }

    /** 失败/取消/终态清理：丢弃本轮未落库的 pending，纯内存操作、非阻塞 IO。 */
    public void discard(String anchorId) {
        pending.remove(anchorId);
    }

    /**
     * 折叠「内容完全相同」的相邻 UserMessage 为一条——LC4J 在首 token 前重试会把同一 prompt
     * 重复写入 pending（可能 >1 次），逐个折叠后只保留首条。
     *
     * <p>用 {@code equals}（UserMessage 是值对象，按 contents 比较）而非仅 {@code instanceof}：
     * 空回复轮留下的裸 UserMessage + 下一轮不同 prompt 的 UserMessage 也相邻，但内容不同，
     * ==必须保留==——不能误删合法的新轮用户消息。
     */
    private static List<ChatMessage> collapseRetryDuplicateUserMessages(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage
                    && !result.isEmpty()
                    && result.get(result.size() - 1) instanceof UserMessage prev
                    && prev.equals(m)) {
                continue;   // 与前一条内容相同的重试重复，跳过
            }
            result.add(m);
        }
        return result;
    }
}
