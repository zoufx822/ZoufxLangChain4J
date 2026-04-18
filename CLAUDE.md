# CLAUDE.md

## 项目

Spring Boot 4.0.3 + LangChain4J 1.11.0，通过 Anthropic 兼容接口连接 MiniMax AI（`MiniMax-M2.5`），支持带会话记忆的流式聊天和思考模式。

## 命令

```bash
mvn spring-boot:run        # 启动（JDK 21，访问 localhost:8080）
mvn clean package          # 打包
```

## 架构

### 后端

`ChatRequest { prompt, sessionId, thinking }` → `AIChatController` → 按 `thinking` 字段路由到对应 `ChatAssistant` Bean → TokenStream 回调翻译为 SSE 事件。

**关键类：**
- `ChatAssistant`：LangChain4J AiService 接口，`@MemoryId sessionId` + `@UserMessage`，由 `AiServices.builder()` 动态代理实现
- `AssistantConfig`：装配 `thinkingAssistant` / `nonThinkingAssistant` 两个 Bean，共享同一 `ChatMemoryProvider`（同 sessionId 跨模式历史连续）
- `LangChain4JConfig`：构建两个 `AnthropicStreamingChatModel`，连接 MiniMax Anthropic 兼容接口（`https://api.minimaxi.com/anthropic/v1`）
- `ChatMemoryConfig`：内存会话记忆，`MessageWindowChatMemory`

**SSE 事件类型：** `thinking`（思考过程）/ `content`（正文）/ `error`

**配置：** `application.yml`，模型 `MiniMax-M2.5`，max-tokens 16384，thinking budget-tokens 8192

**接口：**
- `POST /ai/chat` — 流式聊天（SSE）
- `DELETE /ai/session/{sessionId}` — 清除会话记忆

### 前端

独立仓库 `../ZoufxAIAgent-Web`（与后端同级），技术栈：Next.js 16 + React 19 + TypeScript + Tailwind CSS 4 + shadcn/ui + Zustand + TanStack Query。开发命令 `pnpm dev`（localhost:3000）。

## 工作原则

- 目标不清晰时停下来讨论，不做假设
- 测试后清理脚本、截图等临时文件
- 新功能开发完成后执行 `/test` 进行自测

**多 Agent**（同时满足：涉及多个独立模块 + 任务量大）

1. `superpowers:using-git-worktrees` 创建工作树
2. 多 agent 并行开发
3. `superpowers:finishing-a-development-branch` 合并

**跳过以下 skill**

- `superpowers:test-driven-development`
- `superpowers:brainstorming`
- `superpowers:requesting-code-review`
- `simplify`（用户主动要求时除外）
- `frontend-design:frontend-design`（整体重设计时除外）
