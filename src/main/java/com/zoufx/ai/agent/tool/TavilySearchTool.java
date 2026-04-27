package com.zoufx.ai.agent.tool;

import com.zoufx.ai.agent.util.RetryPolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 自写的网络检索工具——相较官方 WebSearchTool 多了 maxResults 控制和日志钩子。
 * 方法名 search_web（snake_case）与 Anthropic 官方约定一致，提升 MiniMax-M2.5 触发率。
 */
@Slf4j
public class TavilySearchTool {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA);

    private final WebSearchEngine engine;
    private final int maxResults;
    private final int maxAttempts;
    private final long backoffMs;

    public TavilySearchTool(WebSearchEngine engine, int maxResults, int maxAttempts, long backoffMs) {
        this.engine = engine;
        this.maxResults = maxResults;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

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

        WebSearchResults results = searchWithRetry(req, query);

        if (results == null) {
            return "网络检索暂时不可用，基于已有知识回答。";
        }
        String today = LocalDate.now().format(DATE_FMT);
        String header = "今日日期：" + today
                + "\n（请核对下方结果中的日期，若与今日不符必须明确告知用户数据为何日，不得当作今日数据汇报）\n\n";
        if (results.results() == null || results.results().isEmpty()) {
            return header + "未检索到相关结果";
        }
        String body = results.results().stream()
                .limit(maxResults)
                .map(r -> "- [" + safe(r.title()) + "](" + (r.url() == null ? "" : r.url().toString()) + ")\n  " + safe(r.snippet()))
                .collect(Collectors.joining("\n"));
        return header + body;
    }

    private WebSearchResults searchWithRetry(WebSearchRequest req, String query) {
        Throwable last = null;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                return engine.search(req);
            } catch (RuntimeException ex) {
                last = ex;
                if (!RetryPolicy.isRetryable(ex) || attempt == maxAttempts) break;
                log.warn("Tavily retry {}/{} [query={}]: {}", attempt + 1, maxAttempts, query, ex.toString());
                try {
                    Thread.sleep(backoffMs * (attempt + 1L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Tavily exhausted [query={}]", query, last);
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").trim();
    }
}
