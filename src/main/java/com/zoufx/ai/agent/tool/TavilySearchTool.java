package com.zoufx.ai.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * 自写的网络检索工具——相较官方 WebSearchTool 多了 maxResults 控制和日志钩子。
 * 方法名 search_web（snake_case）与 Anthropic 官方约定一致，提升 MiniMax-M2.5 触发率。
 */
@Slf4j
@RequiredArgsConstructor
public class TavilySearchTool {

    private final WebSearchEngine engine;
    private final int maxResults;

    @Tool("在需要最新/实时信息时调用：新闻、近期事件、股价、产品发布、时效性问答等。返回带标题、URL、摘要的结果列表。")
    public String search_web(@P("搜索关键词，简洁中文或英文查询语句") String query) {
        if (engine == null) {
            log.warn("TavilySearchTool 被调用但 engine 未配置，降级返回");
            return "网络检索暂未配置，请基于已有知识回答。";
        }
        log.info("🔍 search_web called: {}", query);
        WebSearchRequest req = WebSearchRequest.builder()
                .searchTerms(query)
                .maxResults(maxResults)
                .build();
        WebSearchResults results = engine.search(req);
        if (results == null || results.results() == null || results.results().isEmpty()) {
            return "未检索到相关结果";
        }
        return results.results().stream()
                .limit(maxResults)
                .map(r -> "- [" + safe(r.title()) + "](" + (r.url() == null ? "" : r.url().toString()) + ")\n  " + safe(r.snippet()))
                .collect(Collectors.joining("\n"));
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").trim();
    }
}
