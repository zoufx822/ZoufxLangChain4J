package com.zoufx.ai.agent.chat.model;

import com.zoufx.ai.agent.memory.model.AnchorMemory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「记忆锚点」三层衰减视图——{@code GET /ai/anchors/{anchorId}/context} 的响应 DTO。
 *
 * <p>分层规则（输入按 last_active_at desc 排序、已排除当前 anchorId）：
 * <ul>
 *   <li>{@code near} = top 5，body 携带完整 summary（≤200 字，AnchorService 已压缩）</li>
 *   <li>{@code mid}  = next 15，body 截断到前 50 字 + "…"</li>
 *   <li>{@code far}  = 剩余全部，仅返计数；前端用 "N 个更早的对话" 模板渲染</li>
 * </ul>
 * 前端按 {@code lastActiveAt} 现算相对时间标签（用户本地时区）。
 */
public record AnchorContextView(
        List<AnchorSummary> near,
        List<AnchorSummary> mid,
        FarTier far
) {

    private static final int NEAR_LIMIT = 5;
    private static final int MID_LIMIT = 15;
    private static final int MID_BODY_TRUNCATE = 50;

    public static AnchorContextView from(List<AnchorMemory> others) {
        List<AnchorSummary> near = new ArrayList<>();
        List<AnchorSummary> mid = new ArrayList<>();
        int farCount = 0;
        List<String> farTitles = new ArrayList<>(3);
        for (int i = 0; i < others.size(); i++) {
            AnchorMemory a = others.get(i);
            if (i < NEAR_LIMIT) {
                near.add(AnchorSummary.fullBody(a));
            } else if (i < NEAR_LIMIT + MID_LIMIT) {
                mid.add(AnchorSummary.truncatedBody(a, MID_BODY_TRUNCATE));
            } else {
                farCount++;
                if (farTitles.size() < 3) farTitles.add(a.title() != null ? a.title() : "新对话");
            }
        }
        String farSummary = farCount > 0 ? String.join("、", farTitles) : null;
        return new AnchorContextView(near, mid, new FarTier(farCount, farSummary));
    }

    public static AnchorContextView empty() {
        return new AnchorContextView(List.of(), List.of(), new FarTier(0, null));
    }

    public record AnchorSummary(
            String id,
            @Nullable String title,
            @Nullable String body,
            @Nullable String mood,
            long lastActiveAt
    ) {
        static AnchorSummary fullBody(AnchorMemory a) {
            return new AnchorSummary(a.id(), a.title(), a.summary(), a.lastMood(), a.lastActiveAt());
        }

        static AnchorSummary truncatedBody(AnchorMemory a, int max) {
            String body = a.summary();
            if (body != null && body.length() > max) {
                body = body.substring(0, max) + "…";
            }
            return new AnchorSummary(a.id(), a.title(), body, a.lastMood(), a.lastActiveAt());
        }
    }

    public record FarTier(int count, @Nullable String summary) {}
}
