# CLAUDE.md

## 项目

Spring Boot 4.0.3 + LangChain4J 1.11.0，通过 Anthropic 兼容接口连接 MiniMax AI（`MiniMax-M2.7`），支持带会话记忆的流式聊天和思考模式。

## 命令

```bash
mvn spring-boot:run        # 启动（JDK 21，访问 localhost:8080）
mvn clean package          # 打包
```

## 架构

**后端：** `ChatRequest { prompt, sessionId, thinking }` → `AIChatController` 按 `thinking` 字段选择 model → SSE 流输出
`thinking` / `content` 两类事件。配置在 `application.yml`。

**前端（已弃用）：** `src/main/resources/static/` 下的原生 JS 前端已弃用，保留但不再维护。

**实际前端：** 独立项目 `E:\ZoufxDemo\ZoufxAIAgent-Web`（单独的 Git 仓库），技术栈：Next.js 16 + React 19 + TypeScript +
Tailwind CSS 4 + shadcn/ui + Zustand + TanStack Query。开发命令 `pnpm dev`。

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
