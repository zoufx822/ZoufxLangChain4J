package com.zoufx.ai.agent.chat.controller;

import com.zoufx.ai.agent.llm.model.Features;
import com.zoufx.ai.agent.chat.model.AnchorContextView;
import com.zoufx.ai.agent.chat.model.AnchorTitleUpdateRequest;
import com.zoufx.ai.agent.chat.model.ChatRequest;
import com.zoufx.ai.agent.chat.model.StopRequest;
import com.zoufx.ai.agent.chat.model.StopResult;
import com.zoufx.ai.agent.chat.service.ChatService;
import com.zoufx.ai.agent.chat.service.AnchorService;
import com.zoufx.ai.agent.chat.support.ChatMessageMapper;
import com.zoufx.ai.agent.memory.api.AnchorMemoryDao;
import com.zoufx.ai.agent.memory.api.ChatMemoryDao;
import com.zoufx.ai.agent.memory.api.HotMemoryDao;
import com.zoufx.ai.agent.memory.model.AnchorMemory;
import com.zoufx.ai.agent.memory.support.HotMemoryType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * AI Agent HTTP 入口（全部归 {@code /ai/*} 命名空间）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST   /ai/chat}：SSE 流式聊天（首帧 turn_started 事件带 turnId；anchorId 为空时懒创建锚点）</li>
 *   <li>{@code POST   /ai/chat/stop}：主动停止一轮（consumeStream 下断连≠停止，停止必须独立端点）</li>
 *   <li>{@code GET    /ai/features}：LLM 能力声明</li>
 *   <li>{@code GET    /ai/anchors?userId=X}：列出该用户全部锚点（sidebar）</li>
 *   <li>{@code GET    /ai/anchors/{anchorId}/messages}：加载锚点消息历史（滑动窗口，默认 20 条）</li>
 *   <li>{@code GET    /ai/anchors/{anchorId}/pending}：该锚点的在建轮问题（consumeStream 轮询用）</li>
 *   <li>{@code GET    /ai/anchors/{anchorId}/context}：其他锚点三层衰减视图（near/mid/far）</li>
 *   <li>{@code PATCH  /ai/anchors/{anchorId}/title}：手动重命名锚点</li>
 *   <li>{@code GET    /ai/memory/hot?userId=X&type=Y}：Hot Memory snapshot（用户级）</li>
 * </ul>
 *
 * <p>URL 风格约定：资源集合一律复数；userId 作为过滤条件走 query param，
 * 资源主键（anchorId）走 path param。无鉴权（开发环境）；真上线前必须补。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai")
public class ChatController {

    private final ChatService chatService;
    private final AnchorService anchorService;
    private final Features features;
    private final HotMemoryDao hotMemoryDao;
    private final AnchorMemoryDao anchorMemoryDao;
    private final ChatMemoryDao chatMemoryDao;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequest request, ServerHttpResponse response) {
        response.getHeaders().set("X-Accel-Buffering", "no");
        response.getHeaders().set("Cache-Control", "no-cache");

        String prompt = request.prompt().trim();
        log.info("Received prompt [anchorId={}, thinking={}]: {}",
                request.anchorId(), request.thinking(), prompt);

        // anchorId 为空时懒建锚点；上一锚点压缩改由后端定时扫描，不再靠前端传 prevAnchorId
        return chatService.chat(request.anchorId(), prompt, request.thinking(), request.userId())
                .map(e -> ServerSentEvent.<String>builder().event(e.type()).data(e.data()).build());
    }

    /**
     * 主动停止一轮生成——consumeStream 下「断连≠停止」，停止必须独立端点（另一个 HTTP 请求，到不了正在
     * 生成的那个请求）。返回 {@code {stopped}}：true=已掐断且不落库；false=该轮已完成落库或已停，前端保留不删。
     * 纯内存非阻塞，直接同步返回。
     */
    @PostMapping("/chat/stop")
    public StopResult stopTurn(@Valid @RequestBody StopRequest request) {
        return chatService.stop(request.turnId());
    }

    @GetMapping("/features")
    public Features features() {
        return features;
    }

    // ====== Anchor lifecycle ======

    /** 列出该用户全部锚点，按 last_active_at desc。 */
    @GetMapping("/anchors")
    public Mono<List<AnchorMemory>> listAnchors(@RequestParam String userId) {
        return anchorMemoryDao.listByUserAsync(userId);
    }

    /** 加载锚点消息历史（滑动窗口，默认 20 条），供前端切锚点时显示。 */
    @GetMapping("/anchors/{anchorId}/messages")
    public Mono<List<Map<String, String>>> messages(@PathVariable String anchorId) {
        return chatMemoryDao.loadByAnchorIdAsync(anchorId)
                .map(list -> list.stream()
                        .map(ChatMessageMapper::toMessageView)
                        .toList());
    }

    /**
     * 该锚点当前是否有在建轮——{@code {turnId, prompt}} 或空 {@code {}}。前端打开对话时并发拉 messages +
     * pending：pending 非空 → 显示「问题 + 生成中」并轮询 messages 等落库回复（consumeStream 下生成脱离连接，
     * write-back 期间 loadMessages 看不到该轮，故必须问注册表）。纯内存非阻塞，直接同步返回。
     */
    @GetMapping("/anchors/{anchorId}/pending")
    public Map<String, String> pending(@PathVariable String anchorId) {
        return chatService.pending(anchorId);
    }

    /**
     * 当前锚点的"其他锚点"三层衰减视图（near/mid/far），供前端右栏「记忆锚点」section 渲染。
     * anchorId 不存在时返 200 空结构，让前端统一走"这是我们的第一次对话"空态。
     */
    @GetMapping("/anchors/{anchorId}/context")
    public Mono<AnchorContextView> anchorContext(@PathVariable String anchorId) {
        return anchorService.anchorContextAsync(anchorId);
    }

    /** 手动重命名锚点——无条件覆盖。 */
    @PatchMapping("/anchors/{anchorId}/title")
    public Mono<Void> renameAnchor(@PathVariable String anchorId,
            @Valid @RequestBody AnchorTitleUpdateRequest request) {
        return anchorMemoryDao.updateTitleAsync(anchorId, request.title().trim());
    }

    // ====== Memory snapshots ======

    /**
     * Hot Memory snapshot：返回该 userId 在指定 type 下写入过的全部 key/value。
     *
     * <p>{@code type} 为必填 query param——强制调用方显式选择类型，避免未来加新 type
     * 时默认值带来的语义漂移。当前合法 type 见 {@link HotMemoryType}。
     */
    @GetMapping("/memory/hot")
    public Mono<Map<String, String>> hotMemory(@RequestParam String userId,
                                               @RequestParam String type) {
        if (!HotMemoryType.ALL.contains(type)) {
            return Mono.error(new IllegalArgumentException("type 无效: " + type));
        }
        return hotMemoryDao.snapshotAsync(userId, type);
    }

}
