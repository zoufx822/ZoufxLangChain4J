# CLAUDE.md

## 项目

Spring Boot 4.0.6 + LangChain4J 1.13.1，支持 `deepseek-v4` / `MiniMax-M3` 双 LLM profile 切换：DeepSeek 走 OpenAI 兼容协议，MiniMax 走 Anthropic 兼容协议。带会话记忆的流式聊天与思考模式。

Hot Memory 含三种 type（v0.14）：`user-impression`（用户画像，UPSERT）/ `significant-event`（重要经历，append-only）/ `commitment`（双方承诺，append-only）。情绪谱 7 词：平静 / 愉快 / 兴奋 / 难过 / 愤怒 / 好奇 / 困惑。

记忆检索走语义召回（v0.2，FTS5 已下线）：SQLite 为正本，Qdrant 只存向量 + 指针元数据（本地 `docker-compose up` 起 Qdrant，gRPC 6334；嵌入用 BGE-M3 走 OpenAI 兼容端点，与 LLM profile 解耦）。每轮对话 prepare 阶段按 userId 自动召回（三维加权 recency/importance/relevance + MMR），注入 system prompt「此刻想起的相关记忆」段，前端无感知；LLM 需要深挖时另有 `search_cold_memory` 工具。配置见 `ai.embedding` / `ai.vector` / `ai.recall`；存量数据回填开 `ai.recall.backfill-on-start`。

## 架构

**后端：** Spring WebFlux + Reactor Netty（已移除 `spring-boot-starter-web`，对齐 LangChain4J 官方姿势）。HTTP 入口集中在 `ChatController`：
- `POST /ai/chat`（SSE）—— 流式聊天，事件类型 `anchor_created` / `thinking` / `content` / `tool_call` / `tool_result` / `mood` / `error`；请求携带 `thinking` 布尔开关，true 走思考档、false 走快档；`anchorId` 为空时懒创建锚点（`anchor_created` 作首条事件）
- `GET /ai/features` —— 当前 profile 标识（预留扩展点，前端暂不消费）
- `GET /ai/anchors?userId=X` / `GET /ai/anchors/{id}/messages` / `GET /ai/anchors/{id}/context` / `PATCH /ai/anchors/{id}/title` —— 锚点列表 / 窗口消息 / 三层衰减视图 / 重命名
- `GET /ai/memory/hot?userId=X&type=Y` —— Hot Memory snapshot

配置见 `application.yml`。

**前端：** 独立仓库 `../ZoufxAIAgent-Web`（与后端同级），开发命令 `pnpm dev`（localhost:3000）。启动时拉 `/ai/features` 缓存到 zustand store，UI 行为按 capability 自适应。

## LLM Profile 切换

当前激活 profile 由 `ai.llm.profile.active` 决定，profile 名与模型官方命名一致（`deepseek-v4` / `MiniMax-M3`）。切换 profile = 改这一行 + 重启。每个模型版本独立 profile（`deepseek-v3` 与 `deepseek-v4` 视同不同厂商），独立 `@ConfigurationProperties` 命名空间（`ai.llm.deepseek-v4.*` / `ai.llm.minimax-m3.*`），由 `@ConditionalOnProperty` 路由对应 `XxxConfig`。

### 模型 Bean 角色（按项目需求定义，跨 profile 统一命名）

模型角色按项目业务需求定义（而非映射各厂商参数空间），每个 profile Config 负责把角色映射到自家实现。每 profile 装两个角色 Bean：

| 角色 Bean | 用途 | deepseek-v4 | MiniMax-M3 |
|---|---|---|---|
| `streamingChatModel`（流式） | 对话主路 | 1 个，思考/快档由 per-call 参数切（pro+max+enabled / flash+disabled） | 端口内建 2 个，adaptive / disabled |
| `syncChatModel`（同步） | 情绪分类 + 摘要压缩 | flash + thinking disabled | disabled |

每档模型 ID 由 `ai.llm.<profile>.chat.thinking-model / fast-model` 配置（MiniMax M3 两档同模型同值，靠 thinking 参数区分）。

**前端思考开关 → 后端路由：** ChatService 只依赖 `StreamingChatPort.stream(anchorId, prompt, thinking)`，profile 间的 per-call 能力差异被端口屏蔽。`AssistantFactory`（`chat/support`）从一个 `StreamingChatModel` 装一个 `ChatAssistant`（共享 ChatMemoryProvider + 动态 system prompt + 工具集），profile-agnostic 部分集中于此。两 profile 的端口实现各自调它：

- **DeepSeek（OpenAI 协议）**：OpenAI 支持 per-call `OpenAiChatRequestParameters`，故只装 1 个 `streamingChatModel` + 1 个 assistant；思考/快档差异（模型名 + reasoning_effort + `thinking` 私有字段经 `customParameters`）由端口按请求现场拼参数覆盖。
- **MiniMax（Anthropic 协议）**：LC4J 1.13.1 的 Anthropic 无 per-call 参数载体（无 `AnthropicChatRequestParameters`，thinking 只能 builder 期定），故端口内建思考/快档两个模型 + 两个 assistant，按 thinking 选其一、per-call 参数传 EMPTY。

> 注意：所有模型 Bean 统一开 `returnThinking + sendThinking`——思考/快档共享会话记忆，上一轮思考档产出的 reasoning/thinking 内容必须在后续轮次（即使走快档）原样回传，否则 DeepSeek API 拒绝、MiniMax 工具调用行为异常。

## 后端开发约束（WebFlux）

- 接口签名随意写：返回 POJO/Map/`Mono`/`Flux` 都行，Spring 自动适配
- **红线**：Controller/Service 里禁止直接调 JDBC、`RestTemplate`、`Thread.sleep`、同步文件 IO 等阻塞 API —— 会卡死 Netty event loop
- 优先选反应式 SDK：HTTP 用 `WebClient`，DB 用 R2DBC
- 例外：LC4J 的 `@Tool` 方法在框架自己的工具线程跑，不在 event loop，无需特殊处理

### 同步/异步命名与 Mono 收口规范

- **命名**：同步函数按作用命名（如 `compress` / `snapshot`），异步函数 = 同步函数名 + `Async`（如 `compressAsync` / `snapshotAsync`），异步函数内直接调用对应同步函数，消除重复代码
- **Mono 包装收口**：`Mono.fromCallable / fromRunnable + subscribeOn` 不允许手写，统一走 `base/support/Blocking` 工具类（`Blocking.call` / `Blocking.run`），且只允许出现在两类位置：
  1. store / 基础设施层的 `xxxAsync` 包装方法（仅在确有反应式调用方时才提供）
  2. service 自包含阻塞流水线（顺序阻塞 IO + 提前退出，无并发/流式需求）的最外层包一次——先写纯同步私有方法，再在公开边界整体包装，不要在编排链路中段嵌套包装（回调地狱的根源）
- **Controller 禁止构造 Mono 包装**：要么调 store/service 的 `xxxAsync`，要么逻辑下沉到 service

## 已知瓶颈

唯一阻塞点：`@Tool` 方法（`TavilySearchTool.search_web`）+ Tavily 同步 HTTP client。LC4J 1.x 的 `@Tool` 不支持 `Mono`/`Future` 返回值，是框架边界。最坏 Tavily 全失败时单次工具调用阻塞约 60s（20s × 3 次重试 + backoff）。低并发场景可接受，不修。

## 注释原则

- **写设计意图，不写版本变迁**：说明当前为什么这样设计（约束、取舍），删掉"v0.12 是 X，v0.13 改为 Y"等历史叙述
- **两行说清类/方法的职责**，删掉"归属 xx 包"、"与 xx 对偶"等可从代码结构直接看出的说明
- **保留**：非显而易见的约束（线程契约、编译期常量限制、fail-fast 不变量）、跨版本遗留问题（如 LC4J 未提供的接口）、seed 语义（已有不覆盖）
- **删除**：版本号标注（`v0.xx`）、设计文档引用（`详见 xxx.md`）、选型论证（"为何不用枚举"但代码已自解释）、迁移路径（"从 X 迁到 Y"）
- yml 配置同理——大段 prompt 文案默认值进 Java `@ConfigurationProperties` 字段初始化，yml 只留阈值/开关/环境变量
- yml 与 `@ConfigurationProperties` 必须保持一一对应：yml 删掉的字段，Props 类里同步删掉，常量内联到调用处；不在 yml 里出现的值不应出现在 Props 类里

## 工作原则

- 目标不清晰时停下来讨论，不做假设
- 临时文件按需清理，用户主动要求时执行
- 新功能完成后执行 `/test` 自测
- 版本号变更时，`pom.xml` 的 `<version>` 必须同步更新（格式 `major.minor.patch-SNAPSHOT`，如 v0.12 → `0.12.0-SNAPSHOT`）
- `git commit` / `git push` / 创建 PR 等写操作绝不主动执行，等用户明确发命令再做（只读命令如 `git status` / `git diff` 不受限）