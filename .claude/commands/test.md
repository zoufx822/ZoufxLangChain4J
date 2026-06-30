# /test — 系统性测试

## 调用方式

- `/test` — 测试最近变更（基于 git diff）
- `/test full` — 全量测试所有功能模块
- `/test <描述>` — 测试指定功能，如 `/test 流式输出` 或 `/test 会话记忆`

---

## 执行规范

**以下所有步骤必须在子 Agent（Task tool）中执行，避免污染主上下文。**

---

### 第一步：变更分析（主 Agent 执行）

```bash
git diff HEAD --name-only          # 变更文件列表
git diff HEAD --stat               # 变更概况
```

根据结果判断受影响的模块：

| 变更路径                             | 受影响模块                  |
|----------------------------------|------------------------|
| `src/main/java/**/controller/**` | API 接口、SSE 流           |
| `src/main/java/**/service/**`    | 业务逻辑、会话记忆              |
| `src/main/java/**/config/**`     | LangChain4J 配置、模型切换    |
| `src/main/resources/**`          | 配置项（API Key、模型、budget） |
| `src/main/resources/static/**`   | 前端：UI、SSE 解析、渲染        |

---

### 第二步：生成测试矩阵（主 Agent 执行）

在启动子 Agent 之前，**先推导出完整测试矩阵**，格式如下：

```
## 测试矩阵

### 功能测试（happy path）
- [ ] TC-01：...
- [ ] TC-02：...

### 边界 / 异常测试
- [ ] TC-10：...（空 prompt）
- [ ] TC-11：...（超长 prompt，500+ 字）
- [ ] TC-12：...（特殊字符：<script>、换行符、中英混合）

### 视觉 / 布局测试
- [ ] TC-20：...（元素是否溢出/截断）
- [ ] TC-21：...（加载状态样式是否正常）

### 回归测试
- [ ] TC-30：...（与改动相邻的功能）
- [ ] TC-31：...（本次未改动但共享状态的功能）
```

测试矩阵确定后再启动子 Agent。

---

### 第三步：子 Agent 执行测试

**模型选择**：子 Agent **必须用 `sonnet` 模型**（`Task` tool 的 `model` 参数传 `"sonnet"`）。原因：

- 测试虽是执行型任务，但**观测与判定容易出错**——曾用 haiku 跑，结果漏看 SSE 事件（工具明明触发却报"无 tool_call"）、误判通过/失败，整份报告不可信，主 Agent 不得不逐条回日志复核
- 判定"行为是否符合预期"需要一定推理（区分 tool_call 有无、mood 选词对错、边界语义），haiku 在这类观测+判定上不可靠
- sonnet 在准确性和成本间平衡，子 Agent 独立上下文不占主会话包袱
- 失败用例重测（第四步）同样用 sonnet

不要用 haiku（实测判定不可靠）。Opus 不应在此场景使用（成本过高，sonnet 已够）。

以 Task 方式启动子 Agent，传入以下 prompt 模板：

```
你是测试工程师。请严格按照以下测试矩阵执行测试，不得跳过任何用例。

【测试矩阵】
{第二步生成的测试矩阵}

【测试约定】
- 服务地址：http://localhost:8080
- 使用 Playwright MCP 操作浏览器
- 每个测试用例结束后记录结果：✅ 通过 / ❌ 失败（附截图路径）/ ⚠️ 异常（描述现象）
- 发现失败时：描述复现步骤，不要自行修复，等待主 Agent 处理
- ==截图约束==：调用 `browser_take_screenshot` 时**只传纯文件名**（如 `tc10-fail.png`），
  不要传带斜杠的相对路径、不要传绝对路径、不要传 `/tmp/...`。
  Playwright MCP 会自动落到项目内的 `.playwright-mcp/` 目录，由 SubagentStop 钩子统一清理。

【测试前准备】
1. 确认服务已启动（curl http://localhost:8080 或 mvn spring-boot:run）
2. browser_console_messages 监控开启，记录基线 error 数量

【执行顺序】
1. 功能测试（happy path 优先）
2. 边界异常测试
3. 视觉/布局检查
4. 回归测试
5. 输出完整测试报告

【测试报告格式】
## 测试报告
**时间**：{时间}
**触发范围**：{变更描述}

| 用例 | 描述 | 结果 | 备注 |
|-----|------|------|------|
| TC-01 | ... | ✅ | |
| TC-10 | ... | ❌ | 截图：.playwright-mcp/tc10-fail.png |

**Console Errors**：{数量}（基线 vs 测试后）
**失败用例**：{列表}
**待修复问题**：{列表}
```

---

### 第四步：处理失败（主 Agent 执行）

子 Agent 返回报告后：

1. **逐一分析失败用例**，确定根因
2. **修复代码**
3. **重新编译验证**：
   ```bash
   mvn compile          # 后端有改动时必须
   ```
4. **针对失败用例重新执行子 Agent 测试**（仅覆盖失败的 TC，不重跑全量）
5. 直到所有用例通过，输出最终报告

---

### 第五步：清理

==无需手动清理==。`.claude/settings.json` 配置了 `SubagentStop` + `SessionStart` 钩子，
子 Agent 结束 / 下次会话启动时会自动清空 `.playwright-mcp/` 下的 *.log / *.yml / *.png / *.jpg / *.webp。
==测试时如需保留特定截图，请用非 `.playwright-mcp/` 的输出路径==。

---

## 异常识别清单

子 Agent 测试时，以下现象必须标记为 ❌ 或 ⚠️：

**功能异常**

- SSE 流中断（流式输出突然停止，无 `[DONE]`）
- 会话记忆丢失（第 2 轮对话不记得第 1 轮内容）
- thinking 模式未生效（发送了 `thinking: true` 但无 thinking 事件）
- 响应体为空或 JSON 解析错误
- HTTP 状态码非 200

**视觉异常**

- 打字机文字溢出容器
- Markdown 渲染后代码块无高亮
- thinking 区域展开/折叠状态错误
- 滚动条异常（内容未自动滚动到底部）
- 按钮/输入框不可点击或样式错位

**性能 / 稳定性异常**

- 页面加载后 `browser_console_messages(level=error)` > 0
- 同一 sessionId 发送第 10 轮消息时报错
- 切换 thinking 模式后上一次对话状态残留

**边界异常**

- 空 prompt 提交后无错误提示或服务崩溃
- prompt 含 `<script>alert(1)</script>` 时被执行（XSS）
- 超长输入（>2000 字）导致服务超时或截断

---

## 说明

- 子 Agent 使用 Task tool 启动，拥有独立上下文，不影响主会话
- 测试期间主 Agent 不修改代码，等待子 Agent 报告
- 测试截图用完即删，不留在工作目录
- 若服务未启动，先执行 `mvn spring-boot:run` 再测试
