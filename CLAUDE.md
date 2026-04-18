# CLAUDE.md

## 项目

Spring Boot 4.0.3 + LangChain4J 1.11.0，通过 Anthropic 兼容接口连接 MiniMax AI，支持带会话记忆的流式聊天和思考模式。

## 架构

**后端：** `POST /ai/chat`（SSE）按 `thinking` 字段路由到两个 ChatAssistant Bean，SSE 事件类型为 `thinking` / `content` / `error`。配置见 `application.yml`。

**前端：** 独立仓库 `../ZoufxAIAgent-Web`（与后端同级），开发命令 `pnpm dev`（localhost:3000）。

## 工作原则

- 目标不清晰时停下来讨论，不做假设
- 临时文件按需清理，用户主动要求时执行
- 新功能完成后执行 `/test` 自测

**跳过以下 skill**

- `superpowers:test-driven-development`
- `superpowers:brainstorming`
- `superpowers:requesting-code-review`
- `simplify`（用户主动要求时除外）
- `frontend-design:frontend-design`（整体重设计时除外）
