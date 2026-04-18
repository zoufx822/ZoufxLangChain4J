---
name: tech-stack
description: ZoufxAIAgent 项目前后端技术选型备忘。当用户提出任何新需求、新功能、新模块、改造、重构时，只要**可能用到新技术**或**用新技术能更方便地解决**，就必须使用本 skill 查阅优选技术栈。触发场景包括但不限于：前后端分离、引入 UI 库/状态库/AI 组件/样式方案/构建工具、选择数据库/缓存/消息队列/向量库/RAG/Agent 框架、讨论"用什么技术/框架/库"、遇到现有栈不好解决的问题、或任何"有没有更好的办法"的提问。宁可多触发，不要漏触发——只要嗅到"可能引入新技术"的味道就进来。
---

# 前后端技术选型备忘（ZoufxAIAgent）

这是与用户已经讨论并拍板的**默认技术栈**。未来开发相关功能时，**优先采用下列选型**；如需偏离，必须在动手前向用户说明理由并获得确认。

## 触发原则

**宁可多触发，不要漏触发。** 只要用户的需求满足以下任一条，就查阅本备忘：

- 可能需要引入新库/新框架/新中间件
- 用新技术能比现有栈更方便、更优雅
- 用户问"有没有更好的办法 / 用什么 / 推荐什么"
- 涉及前端架构、后端架构、数据层、AI/Agent、部署等技术选型

## 决策权重

**用户体验 > 未来扩展 > 生态完善**；**不考虑**学习成本和编码量（前后端代码均由 AI 生成）。

---

## 后端

### 已在用

| 技术 | 说明 |
|------|------|
| Spring Boot 4.0.3 | Web 框架，JDK 21 |
| Spring WebFlux | 返回 `Flux<ServerSentEvent>` 实现 SSE 流式输出 |
| LangChain4J 1.11.0 | AiServices 动态代理、ChatMemory、TokenStream |
| langchain4j-anthropic | 连接 MiniMax Anthropic 兼容接口的 StreamingChatModel |
| Lombok | 减少样板代码 |

### 扩展时的优选方案

> 每次与用户拍板后，在此新增一行。

| 场景 | 优选方案 | 说明 |
|------|----------|------|
| *（待补充）* | *（待补充）* | — |

---

## 前端

### 已在用

| 技术 | 说明 |
|------|------|
| Next.js 16 | App Router，SSR/路由，Turbopack HMR |
| React 19 | UI 框架 |
| TypeScript | 类型安全 |
| Tailwind CSS 4 | 工具类样式，CSS-first 零配置 |
| @tailwindcss/typography | Markdown prose 排版样式 |
| @base-ui/react | headless UI 原语（Button/Input/Tooltip/Sheet/ScrollArea 等），shadcn 现已基于 Base UI |
| shadcn | 组件代码生成器（构建时工具） |
| Zustand 5 | 客户端状态管理（会话列表、消息、加载状态） |
| streaming-markdown | 流式增量解析并直接写入 DOM，实现打字机效果 |
| Shiki | 流结束后对代码块应用语法高亮 |
| Motion | 动画库（消息展开/收起的 AnimatePresence） |
| lucide-react | 图标，与 shadcn 一致 |
| next-themes | 深色/浅色主题切换 |
| sonner | Toast 通知（用于流式错误提示） |
| pnpm | 包管理，快、省磁盘 |

### 扩展时的优选方案

| 维度 | 选型 | 说明 |
|------|------|------|
| 服务端状态 | @tanstack/react-query 5 | 已安装；适用于普通 REST 接口的缓存/重试/自动刷新，SSE 流式接口不适用 |
| 表单 | React Hook Form + Zod | 性能 + 类型安全校验 |
| Lint/Format | Biome | 替代 ESLint + Prettier |

### 不选项及原因

| 技术 | 原因 |
|------|------|
| Vercel AI SDK `useChat` | 后端 SSE 使用自定义事件（`thinking`/`content`/`error`），与 AI SDK 数据流协议不兼容，改造代价大于收益 |

---

## 架构形态

```
[ Next.js 16（../ZoufxAIAgent-Web） ]  ──SSE/HTTP──>  [ Spring Boot + LangChain4J ]
```

---

## 决策规则

1. **新功能/新模块**：直接按上表选型，无需再问。
2. **后端新增能力**：查后端扩展表；表中没有则与用户讨论后拍板并**回写本 skill 新增一行**。
3. **用户提出与上表冲突的选型**：尊重用户决定，但简短提醒本备忘的默认选择与理由。

## 偏离本备忘的触发条件

只有以下情形才应主动建议偏离：

- 用户**明确**改变了决策权重
- 出现**新技术**且在"用户体验 + 生态"上明显超越当前选型
- 项目场景发生结构性变化

## 维护规则

**每次就某项新技术与用户达成一致后，立即回写本文件对应章节。** 本备忘是活文档，越用越完整。
