# /test-prompt — System Prompt 编排的 Prompt-Behavior 系统测试

验证 `PromptComposer` + 所有 `Prompt` 实现注入的每条 directive 是否==真的驱动了 LLM 行为==。专治"prompt 文本看着对、LLM 行为却不对"的盲区——比如 v0.13 SOUL principles 那条"陌生人问称呼"软语气被 LLM 默默忽略，结构验证全过，跑了多轮才发现。

==与 `/test` 的区别==：`/test` 测功能/接口/UI，关注"代码是否跑通"；`/test-prompt` 测提示词，关注"==LLM 行为是否符合 prompt 预期=="。两者互补，不重叠。

## 调用方式

- `/test-prompt` — 测试==当前所有== Prompt 的全部 directive
- `/test-prompt <Section名>` — 只测指定 Section，如 `/test-prompt Soul` / `/test-prompt AnchorContext`
- `/test-prompt diff` — 只测最近 git diff 涉及到的 Prompt（增量验证）

---

## Section 全景（按 order 排序）

| order | 实现类 | 段标题 | 关键 directive 来源 |
|-------|--------|--------|-------------------|
| 10  | `SoulPromptImpl` | `## 关于你自己` | `SoulDaoImpl.DEFAULT_SEED`（role/name/tone/principles/forbidden_patterns/quirks） |
| 20  | `UserImprPromptImpl` | `## 关于对方` | `UserImpressionFields.FIELDS` 10 字段的 `renderDirective()`（非空字段才出现） |
| 20  | `UserImprPromptImpl` | `## 你对对方的了解程度` | stranger/half-known/fully-known 三模式（fill_ratio 阈值：0.3/0.7；10 字段共 100%) |
| 28  | `AnchorPromptImpl` | `## 你与对方此前的其他交谈` | `anchor_memory` 其他锚点已有 summary 的条目，按 last_active_at desc，最多 5 条 |
| 30  | `ToolsPromptImpl` | `## 可用工具` | 5 个 `ToolPrompt` Bean 的 promptInstructions 拼接（见下表） |
| 35  | `ExpressionPromptImpl` | `## 回复框架` | 静态文案——回复结构与详略框架 |
| 35  | `ExpressionPromptImpl` | `## 输出格式` | 静态文案——Markdown 输出形态约束（禁止整段裹进代码围栏等） |
| 35  | `ExpressionPromptImpl` | `## 情绪表达` | 7 词词表 + 倾向规则 + 格式反模式（**无条件注入**，无 enable 开关） |
| 45  | `RecallPromptImpl` | `## 此刻想起的相关记忆` | 语义召回 Top-N：cold + significant-event + commitment + user-impression 经向量库回查（无命中则整段不渲染） |

> 注：`significant-event` / `commitment` 已**无专用常驻段**——record 工具写入 hot_memory 时同步索引进向量库，此后仅在语义相关时经 `RecallPromptImpl` 浮现。测它们的「注入后引用」行为，前置状态须让内容进入**向量库**（走 record 工具或 backfill），单纯 SQL 写 hot_memory 不会出现在 prompt 里。

### 工具子段（ToolsPromptImpl 聚合）

| 工具实现类 | 工具名 | 触发条件 |
|-----------|--------|---------|
| `UserImpressionUpdateTool` | `update_user_impression` | 识别到任何画像属性（10 字段白名单） |
| `SignificantEventRecordTool` | `record_significant_event` | 情绪显著人生事件 / 长期处境 / 带时间标记经历 |
| `CommitmentRecordTool` | `record_commitment` | 双方做出承诺（3 种前缀之一） |
| `ColdMemorySearchTool` | `search_cold_memory` | 对方提到过往而此刻已想起的内容（召回段/交谈摘要/当前对话）里都没有时；召回段已有答案则==不得==调用 |
| `TavilySearchTool` | `search_web` | 实时/最新信息、用户明确要求搜索 |

---

## 核心原则（区别于 /test）

1. **必须跑真实 LLM** —— 静态检查（看 prompt 文本是否对、字段是否替换）==不算通过==。LLM 处理 prompt 不可静态推断，软语气、错误条件、跨 Section 冲突==只有真实多轮对话才能暴露==
2. **必须正反两组用例** —— 每条 directive 配：(a) 条件满足时 LLM 是否==按期望行动==？(b) 条件不满足时 LLM 是否==不出现该行为 / 走对偶分支==？正向偏置是 prompt 工程最常翻车的盲区
3. **测试前两次审核** —— 步骤 1 拆 directive 完输出给用户审一遍；步骤 2 矩阵设计完再输出审一遍。==避免漏拆==关键 directive（如分支型 Section 的隐藏分支）
4. **状态切换必须覆盖** —— 不只测"hot 有值/无值"两个静态状态，==还要测状态切换瞬间==（如 stranger → half-known 那一轮、锚点切换后 AnchorPromptImpl 的注入变化）

---

## 执行规范

**所有费上下文的步骤（枚举源码、跑测试）放子 Agent；审核与决策回到主 Agent。**

---

### 第一步：枚举 directive（主 Agent 执行）

```bash
# 找所有 Prompt 实现
```
用 Grep `implements Prompt` 找全实现类。对每个实现 Read 其 `render()` 方法，==逐条==拆出 directive。`ToolsPromptImpl` 还需要 Read 各 `ToolPrompt.promptInstructions()`，以及 `UserImpressionFields.FIELDS` 的 `renderDirective()`。

输出格式如下表，==输出后暂停==，让用户审核是否有漏拆（特别注意 `if/else` 分支、`switch` 各 case、动态拼接的子段）：

```
## Directive 清单

| # | Section | 触发条件 | Directive 原文（缩略） | 期望行为 |
|---|---------|----------|----------------------|----------|
| 1  | Soul | 常驻（Soul 段无条件渲染） | "你是一个会持续记得对方的 AI 对话搭档" | AI 在对话中体现「持续记得对方」的人格 |
| 2  | Soul / name | soul_profile.name 非空 | "你叫小Z" | AI 在自报姓名时回答"小Z" |
| 3  | Soul / tone | soul_profile.tone 非空 | "温和、克制...不卖萌、不堆 emoji" | AI 不刷 emoji，不用卡通话气 |
| 4  | Soul / principles(1) | enabled | "信息密度高于装饰，回答尽量精炼" | 回复不堆砌寒暄 |
| ...（principles 共 4 条逐条拆） | | | | |
| N  | Identity / username（已知） | hot_memory user-impression username 非空 | "对方的称呼是「X」...每轮至少自然使用一次" | 每轮回复至少自然带一次称呼 |
| N+1| Identity / username（缺失） | username 为空 → 此 field 不渲染 | （字段不渲染，由 ImpressionGuidance 接管追问职责） | AI 不凭空叫名字；追问职责在 ImpressionGuidance stranger 模式 |
| ... | | | | |
| M  | RecallContext / 经历 | 语义召回命中 significant-event | 「## 此刻想起的相关记忆」列出相关经历 | AI 可自然引用，不当它是新信息 |
| ...| RecallContext / 承诺 | 语义召回命中 commitment | 「## 此刻想起的相关记忆」列出相关承诺 | AI 主动记起承诺，在相关时机提及 |
| ...| ImpressionGuidance / stranger | fill_ratio < 0.3（≤2/10 字段） | 先回应主线 → 自然引一次追问...反模式：绕过不引 | 每轮至少问一次未填字段 |
| ...| ImpressionGuidance / half-known | 0.3 ≤ fill_ratio < 0.7 | 每 2~3 轮一次自然追问；识别到新信息立刻调工具 | 不每轮追问、但不完全沉默 |
| ...| ImpressionGuidance / fully-known | fill_ratio ≥ 0.7 | 使用已知称呼+风格；引用认知给个性化建议 | 可显式引用对方特质；不再追问 |
| ...| AnchorContext | anchor_memory 其他锚点存在已压缩 summary | 列出其他锚点"「标题」：摘要" | AI 知道"我们聊过 X 话题"；可主动关联但不混入当前窗口细节 |
| ...| AnchorContext（无 summary） | 其他锚点 summary=NULL（活跃中/从未切走） | 段不渲染 | AI 不知道其他锚点内容 |
| ...| Tools / update_user_impression | 识别到画像属性 | "识别到...立刻调...不能只口头确认而不调工具" | 对方报名时 AI 调工具，不只说"好的我记下了" |
| ...| Tools / record_significant_event | 情绪显著事件 | 命中信号...调用规则 | 父母去世等事件立刻 record，临时事实不写 |
| ...| Tools / record_commitment | 承诺识别 | 3 种前缀必填 | 你应允时 record_commitment，description 带前缀 |
| ...| Tools / search_cold_memory | 对方提到过往，且此刻已想起的内容（召回段/交谈摘要/当前对话）里都没有 | 先回想、再检索、最后才能下结论；不说"我没有记录" | 召回段已有答案 → 直接答==不调工具==；都没有 → 先搜，不直接否认 |
| ...| Tools / search_web | 实时信息、明确要求搜 | 关键词含具体日期；日期核对规则 | 问今日天气 → AI 调 search_web，关键词含当天日期 |
| ...| Mood | 常驻（无开关） | 情绪转折处就地插 ⟦mood:KEYWORD⟧（0~N 个）；7词词表；倾向规则 | 情绪有起伏时回复内出现合法 mood 标记（被后端剥离为 mood 事件），平淡时可无 |
| ...| Mood / fallback 反模式 | 任意对话 | "「平静」只用于真正平淡的事务性对话，不是 fallback 默认值" | 用户分享好消息 → 兴奋，不默认平静 |
```

==关键审核点==：
- `UserImprPromptImpl` 10 个字段的 `renderDirective()` 是否逐条拆（`username` / `language` / `role` / `interests` / `tone` / `personality` / `habits` / `hobbies` / `values` / `communication_style`）
- `UserImprPromptImpl` 了解程度三模式（stranger/half-known/fully-known）是否都拆了
- `AnchorPromptImpl` 的 "有 summary" 和 "无 summary" 两路是否都拆了
- `Soul` 段的 `principles`（4条）、`forbidden_patterns`（3条）、`quirks`（2条）是否==逐条==拆开
- `Mood` 段的 7 词倾向规则（难过优先共情、兴奋优先共鸣、好奇优先、**拿不准优先愉快**）是否全拆

主 Agent 在此处停下来：
> "已枚举 N 条 directive，请审核是否漏拆 / 是否有需要合并的条目，确认后回复 OK 进入步骤 2"

---

### 第二步：设计正反测试矩阵（主 Agent 执行）

对每条 directive 设计正反用例，输出后==再次暂停==请用户审核：

```
## 测试矩阵

| 用例 | 验证 directive # | 类型 | 前置状态 | 操作 | 期望 |
|------|----------------|------|----------|------|------|
| TP-01 | Identity/username（已知） | 正向 | username=ZFX（见状态控制 SQL） | 发"今天天气怎么样" | 回复至少自然带一次"ZFX" |
| TP-02 | Identity/username（已知） | 反向 | username 未写 → 字段不渲染 | 发"今天天气怎么样" | 回复不含任何名字（避免幻觉造名） |
| TP-03 | ImpressionGuidance/stranger | 正向 | 0/10 字段（全空 userId）、全新 anchorId | 发"你好" | 回复先回应"你好"，然后自然问一次称呼 |
| TP-04 | ImpressionGuidance/stranger（重复抑制） | 边界 | 0/10 字段；chat memory 已有一轮 AI 问过称呼且用户拒答 | 发"今天好热" | 回复不追问称呼 |
| TP-05 | ImpressionGuidance/stranger（主动提供） | 状态切换 | 0/10 字段 | 发"我叫 ZFX" | AI 调 update_user_impression(username=ZFX)，回复不再问称呼，改用 ZFX 称呼 |
| TP-06 | ImpressionGuidance/half-known | 正向 | 3/10 字段已填（fill_ratio=0.3）、全新 anchorId | 发"最近工作很累" | 回复有共情，但不是每句都追问；且 ≤3 轮才穿插一次追问 |
| TP-07 | ImpressionGuidance/fully-known | 正向 | 7/10 字段已填（fill_ratio=0.7）| 发"帮我分析一下这个问题" | 回复引用对方已知特质，不追问 |
| TP-08 | AnchorContext（有 summary） | 正向 | anchor_memory 有另一个锚点且 summary="学 React hooks 相关" | 发"你还记得我学过什么吗" | AI 回复中提到"你之前聊过学 React hooks" |
| TP-09 | AnchorContext（无 summary） | 反向 | anchor_memory 无其他锚点或其他锚点 summary=NULL | 发"你还记得我学过什么吗" | AI 不凭空捏造"我们聊过 X"；可调 search_cold_memory 核实 |
| TP-10 | AnchorContext（锚点切换触发压缩） | 状态切换 | 锚点 A 已有消息；发送请求时携带 prevAnchorId=A、anchorId=B | 等待异步压缩完成后查 anchor_memory | anchor_memory.summary 不为空 |
| TP-11 | Tools/update_user_impression | 正向 | 0/10 字段 | 发"我是做 Java 后端的" | AI 调 update_user_impression(role=Java 后端)，不只说"我记住了" |
| TP-12 | Tools/update_user_impression（反模式） | 反向 | 0/10 字段 | 发"今天写了几行代码" | AI 不武断推断职业；不调工具或只记录 language 等明确字段 |
| TP-13 | Tools/record_significant_event | 正向 | 全新 anchorId | 发"去年我父亲去世了" | AI 调 record_significant_event("去年父亲去世")，回复有共情 |
| TP-14 | Tools/record_significant_event（临时性排除） | 反向 | 全新 anchorId | 发"今天迟到了" | AI 不调 record_significant_event |
| TP-15 | Tools/record_commitment（AI 承诺） | 正向 | username=ZFX | 对方说"你这周帮我梳理 React 路径吧"，AI 应允 | AI 调 record_commitment("我（AI）答应ZFX：本周帮其梳理 React 学习路径") |
| TP-16 | Tools/record_commitment（前缀验证） | 边界 | username=ZFX | 对方说"我下周把代码 review 完发给你" | AI 调 record_commitment("ZFX答应我：下周 review 完代码") |
| TP-17a | Tools/search_cold_memory（召回命中→不得调用）| 反向 | "喜欢美式咖啡"已索引进向量库；新锚点（自动召回会命中注入召回段） | 发"我之前说过我最喜欢喝什么吗" | AI 直接答出美式咖啡，==不调== search_cold_memory（无工具卡片） |
| TP-17b | Tools/search_cold_memory（想不起来→必触发）| 正向 | 向量库无相关内容（问从未提过的话题） | 发"你还记得我说过我养了什么宠物吗" | AI 先调 search_cold_memory，空结果后如实说想不起来；不直接说"我没有记录" |
| TP-18 | Tools/search_web（实时信息） | 正向 | 任意状态 | 发"今天北京天气怎么样" | AI 调 search_web，关键词包含当日日期 |
| TP-19 | Mood / 情绪共情 | 正向 | 任意状态 | 发"今天面试失败了，心情很差" | mood 事件含"难过"，不是"平静" |
| TP-20 | Mood / 正向共鸣 | 正向 | 任意状态 | 发"我终于拿到 offer 了！" | mood 事件含"兴奋"，不是"平静" |
| TP-21 | Mood / fallback 反模式 | 反向 | 任意状态 | 发"帮我列一下今天的待办" | 无 mood 标记或为"平静/愉快"（纯事务性，验证不过度情绪化） |
| TP-22 | Mood / 7词词表约束 | 边界 | 任意状态 | 任意多条对话，观察所有 mood 标记 | 每条 mood 都来自 {平静/愉快/兴奋/难过/愤怒/好奇/困惑}，无词表外的词 |
| TP-23 | Soul / forbidden_patterns | 正向 | 任意状态 | 发"哇你好厉害呀！" | 回复不含 "好棒呀""棒棒哒"等过度赞美；不堆 emoji |
| TP-24 | RecallContext（经历召回） | 正向 | significant-event "正在备考研究生" 已**索引进向量库**（经 record 工具/backfill，非仅 SQL） | 发"最近备考还顺利吗" | 命中召回 → AI 自然提及，不当它是新信息 |
| TP-25 | RecallContext（承诺召回） | 正向 | commitment "我（AI）答应ZFX：本周帮梳理 React" 已**索引进向量库** | 发"那个 React 路径" | 命中召回 → AI 提及/兑现之前的承诺 |
```

==状态控制工具==：

**浏览器操作（Playwright）**
- 全新 userId + anchorId：`browser_evaluate('() => { localStorage.clear(); location.reload(); }')` → 前端新建对话调 `POST /ai/anchors` 拿 anchorId
- 直接调 API 创建锚点：`curl -X POST http://localhost:8080/ai/anchors -H 'Content-Type: application/json' -d '{"userId":"zfx","title":"测试锚点"}'`

**SQL 状态注入**（`sqlite3 ./data/sqlite/zoufx-ai.db`）：
```sql
-- 设置 hot_memory 用户画像字段（UPSERT）
INSERT OR REPLACE INTO hot_memory (user_id, type, key, value, updated_at)
VALUES ('zfx', 'user-impression', 'username', 'ZFX', strftime('%s','now')*1000);

-- 批量填充 7 个字段（fully-known 前置状态，fill_ratio≥0.7）
INSERT OR REPLACE INTO hot_memory (user_id, type, key, value, updated_at) VALUES
  ('zfx','user-impression','username','ZFX',strftime('%s','now')*1000),
  ('zfx','user-impression','language','中文',strftime('%s','now')*1000),
  ('zfx','user-impression','role','Java 后端开发',strftime('%s','now')*1000),
  ('zfx','user-impression','interests','技术/爬山',strftime('%s','now')*1000),
  ('zfx','user-impression','tone','简洁直接',strftime('%s','now')*1000),
  ('zfx','user-impression','personality','偏谨慎',strftime('%s','now')*1000),
  ('zfx','user-impression','habits','早起跑步',strftime('%s','now')*1000);

-- 清空用户画像（stranger 前置状态）
DELETE FROM hot_memory WHERE user_id='zfx' AND type='user-impression';

-- 写入一条 significant-event
INSERT INTO hot_memory (user_id, type, key, value, updated_at)
VALUES ('zfx', 'significant-event', hex(randomblob(16)), '正在备考研究生', strftime('%s','now')*1000);

-- 写入一条 commitment
INSERT INTO hot_memory (user_id, type, key, value, updated_at)
VALUES ('zfx', 'commitment', hex(randomblob(16)), '我（AI）答应ZFX：本周帮其梳理 React 学习路径', strftime('%s','now')*1000);

-- 给另一个锚点写入 summary（触发 AnchorPromptImpl 注入）
UPDATE anchor_memory SET summary='聊了学 React hooks 的进展，给出了 useEffect 示例代码，还没有给完整路径建议' 
WHERE id='<另一个锚点的 UUID>' AND user_id='zfx';

-- 查看当前锚点状态
SELECT id, title, summary, datetime(last_active_at/1000,'unixepoch','localtime') as last
FROM anchor_memory WHERE user_id='zfx' ORDER BY last_active_at DESC;
```

**查看 Hot Memory 快照**：
```
GET http://localhost:8080/ai/memory/hot?userId=zfx&type=user-impression
GET http://localhost:8080/ai/memory/hot?userId=zfx&type=significant-event
GET http://localhost:8080/ai/memory/hot?userId=zfx&type=commitment
```

**触发锚点切换压缩**（ChatRequest 中带 `prevAnchorId`）：
```json
POST /ai/chat
{ "prompt": "你好", "anchorId": "新锚点UUID", "prevAnchorId": "旧锚点UUID" }
```

每条 ❌/⚠️ 必须能复现，==前置状态要写到能让人精确复现的程度==。

主 Agent 在此处停下来：
> "已设计 N 个用例，请审核是否覆盖所有 directive 的正反、是否有遗漏的状态切换场景，确认后回复 OK 启动子 Agent"

---

### 第三步：子 Agent 跑测试（Task tool，haiku 模型）

==与 `/test` 同款约定==：haiku 模型，Playwright MCP 操作浏览器，截图只传纯文件名落 `.playwright-mcp/`。

传入子 Agent 的 prompt 模板：

```
你是 prompt-behavior 测试工程师。按以下矩阵执行真实 LLM 多轮对话测试。

## 服务地址（本项目特殊：前端独立仓库）
- 前端 UI：http://localhost:3000  ← Playwright 操作
- 后端 API：http://localhost:8080
- 测试前确认两个服务都 200。若任一未启动，直接报错退出。

## 后端日志位置
{主 Agent 填入实际后台任务的 output 路径}

LC4J DEBUG 日志会打印每次请求的完整 SystemMessage / UserMessage / AiMessage / Tool calls，
是判定"是否生效"的==关键证据==——每个用例的报告必须引用对应时间窗的 system message 片段。

## 关于锚点（v0.145 关键）
- ChatRequest 必须带 anchorId；切锚点时带 prevAnchorId
- 调 GET /ai/anchors?userId=zfx 获取现有锚点列表
- 调 POST /ai/anchors 创建测试锚点拿到 anchorId
- 调 GET /ai/memory/hot?userId=zfx&type=user-impression 验证 hot memory 前置状态

## 测试矩阵
{主 Agent 填入步骤 2 审核通过的矩阵}

## 状态控制
{主 Agent 填入步骤 2 表中的状态控制工具列表}

## 判定规则
- ✅ 生效：行为完全符合 directive 期望
- ⚠️ 部分生效：条件满足但行为弱（如该问称呼时只用"您"未明确问；该用名字时只在第一轮带、后续轮丢失；mood 选了正确词但格式位置不对）
- ❌ 失效：条件满足但行为==完全没出现==（如不问称呼、不调工具、mood 默认平静）
- 🟡 冗余/冲突：多条 directive 同时生效但行为打架（如 stranger 追问 vs username 已知时"不问名字"的冲突场景）

## 报告格式

| 用例 | 验证 # | 结果 | LLM 实际输出片段 | system message 片段 | 根因猜测 |
|------|--------|------|----------------|-------------------|---------|

==不要==自己改代码或 prompt。每条 ❌/⚠️/🟡 给根因猜测但等主 Agent 决策。
```

子 Agent 必须做的事：
1. 先 curl 确认前后端 200
2. 先调 `GET /ai/anchors?userId=zfx` 获取已有锚点，按需 `POST /ai/anchors` 创建测试锚点
3. ==按用例顺序==跑，每个用例独立结束后再开下一个（避免上下文污染）
4. 状态切换用例要==精确观察==切换前后的 system message 对比
5. AnchorContext 用例需等待异步压缩完成（curl 轮询 `GET /ai/anchors/{anchorId}/context` 直到 summary 非空）
6. 报告时 ❌/⚠️ 必须附 system message 实证（用 Read 工具读后端日志）

---

### 第四步：分类报告 + 决策（主 Agent 执行）

子 Agent 返回后，主 Agent 分类整理：

```
## /test-prompt 报告

**测试范围**：{全部 / 指定 Section / diff}
**Section 数**：9（Soul / Identity / ImpressionGuidance / AnchorContext / Tools / ResponseFramework / OutputFormat / Mood / RecallContext）
**Directive 数**：{N}
**用例数**：{M}

### ✅ 生效（{n} 条）
{逐条列出 + 一句话证据}

### ⚠️ 部分生效（{n} 条）
{逐条列出 + 现象 + 根因猜测}

### ❌ 失效（{n} 条）
{逐条列出 + 现象 + 根因猜测 + 修复建议方向}

### 🟡 冗余/冲突（{n} 条）
{逐条列出 + 哪两条 directive 在哪个场景冲突 + 合并/裁剪建议}

### 优化空间
{超出"通过/失败"的发现：可合并的 directive、可删的冗余、可强化的软语气、可显式化的隐式条件}
```

==报告完成后==主 Agent 停下来：
> "测试结果如上。⚠️/❌/🟡 共 N 条需要决策，要修哪些 / 怎么修等你拍。"

==绝不==自己改代码或 yml——这一步用户必须显式发命令。

---

## 服务前置要求

- 后端 8080 + 前端 3000 都必须在跑
- 若未跑，主 Agent 启动前先 ==询问用户==（不擅自重启正在跑的服务）
- ==关键==：后端必须是当前代码——若刚改过 Prompt / yml / SoulDao，先确认重启过（项目无 devtools）
- mood 段无条件注入（无 enable 开关），直接测即可
- AnchorContext 测试需要至少有 2 个锚点，且至少一个锚点已有压缩过的 summary

---

## 注意事项

- 跑期间主 Agent 不修改代码，等子 Agent 报告
- 测试用例的"前置状态"必须==可精确复现==（userId、anchorId、hot_memory KV、anchor_memory summary 都写明）
- 截图沿用 `/test` 约定：纯文件名落 `.playwright-mcp/`，会话级钩子自动清理
- 报告里 ⚠️/❌/🟡 的"根因猜测"==只是猜测==，是给主 Agent 决策时的参考，不代表确诊
- AnchorContext 异步压缩是 fire-and-forget，测试时要等 summary 写入再跑验证步骤
- mood 标记由后端 `MoodEventProcessor` 从流末剥离发送 SSE，前端收到时已独立——判定依据是后端日志里 LLM 输出的原始 <!--mood:*--> 注释，而非前端展示
- 跑完不主动关闭服务

---

## 何时不该用 /test-prompt

- 改的是==非提示词代码==（如工具实现、记忆存储、controller、AnchorService 压缩逻辑）——用 `/test`
- 改的是==前端==（UI 渲染、SSE 解析、锚点切换动画）——用 `/test`
- 仅改 prompt 文本拼写错误且确信不影响行为——肉眼审代码即可
