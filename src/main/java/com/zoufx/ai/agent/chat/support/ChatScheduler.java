package com.zoufx.ai.agent.chat.support;

import com.zoufx.ai.agent.chat.service.AnchorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chat 通用定时器——集中管理所有 chat 域的定时任务。
 *
 * <p>每个定时方法做单一调度转发，业务逻辑在对应 Service 里。
 */
@Component
@RequiredArgsConstructor
public class ChatScheduler {

    private final AnchorService anchorService;

    /** 锚点压缩扫描——每 10min 触发一次。顺序阻塞 IO，耗时远小于间隔。 */
    @Scheduled(fixedDelayString = "PT10M")
    public void scanForAnchorCompaction() {
        anchorService.compactIdleAnchors();
    }
}
