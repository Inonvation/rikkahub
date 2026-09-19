# AI 使用体验优化方案（提示词 / 工具声明 / 注意力 / 反馈 / 环境感知）

> 2026-09-18 创建 · 状态：**P0–P2 已实施**（见 §8 实施记录）· 对照基线：OpenAI Codex CLI（rust-v0.128.0-alpha.1，prompt mirror 实测）
> 目标：让 AI 的内容生成质量与工具使用体验对齐 Codex —— 提示词信息结构正确、注意力集中、反馈干净。
> 关联文档：`capability-isolation-plan.md`（已完成的能力隔离）、`subagent-token-optimization-plan.md`（子代理 token，未实施）、`compact-command-plan.md`（压缩管线，已实施）

## 0. 结论先行

当前 fork 的问题**不是「提示词太长超标」**（静态前缀在 128k 窗口下占 5–15%，从未逼近 30% 预算线），而是**信息结构错位**，按严重度排序：

1. **wire 层裁剪把载荷信息砍掉了（P0，最严重）**：fork 自加的 `WireTool` 把工具 description 统一截到 300 字符、参数 description 截到 80 字符，但 25 个工具的描述超 300、12 个参数描述超 80——**被砍掉的恰恰是"何时用/怎么用"的关键句**：搜索的引用格式（`[citation,domain](id)`）在 1156 字符处、子代理清单在 agentId 参数描述 80 字符后、记忆的护栏句（先查 `<memories>`、不主动复述）全在 300 字符外。模型看到的是"工具能做什么"的残句，看不到"该怎么用"。**搜索引用卡片事实性死亡、子代理选型盲选、记忆去重全靠碰运气，根因都在这里。**
2. **记忆注意力过载（P0）**：写入侧措辞强驱动（"proactively, without being asked"）、检索侧无相关性门控（"保证每轮都有记忆"）、副查询用模型上一轮回答（自我强化回路）、默认助手人设还写着"Remember details to build rapport"。四者叠加 = 每轮都在提醒模型"你记得用户的一切"。
3. **todo 提醒过密（P1）**：3 个工具步就催一次 + 同一套规则在 desc/systemPrompt/reminder 三处重复。
5. **反馈链路**：工具错误回传完整 stack trace；无 shell 时工具输出完全不截断（32K 兜底只在 shell 存在时生效）。
6. **环境层冗余（P2）**：`<workspace>` 块与工具描述、AGENTS 注入、write 结果字段三处重复讲同一件事。

**一句话方案**：把「描述」和「策略」拆成两个信道——description 只写"是什么 + 何时用"且**必须写进 300 字符**（用约定+测试保证，而不是事后截断）；usage policy 统一放 `systemPrompt`（不裁剪、按内容去重、缓存稳定）；记忆从"每轮强推"改为"相关性门控的弱推"；反馈从"堆细节"改为"给下一步"。

---

## 1. 现状事实（探索结论，均带 file:line）

### 1.1 提示词组装管道（七层，每层已各司其职）

```
1. 身份层：assistant.systemPrompt（会话可重写）；全空时注入 BASE_IDENTITY_PROMPT     GenerationPrompts.kt:35
2. 用户层：user profile（全局稳定段）                                              GenerationPrompts.kt:179
3. 能力层：工具 systemPrompt（去重注入）                                           GenerationPrompts.kt:214
4. 行为层：mode + Plan&Act + Tool Groups + Ask User + SubAgent                    AgentBehaviorPrompt.kt:14
5. 环境层：<workspace> → <trusted_folder> → <knowledge_base>（transformer 追加）    ChatService.kt:1896-1917
6. 注入层：模式/lorebook → 占位符
7. 记忆层：<memories> 追加到最后一条 USER 消息（不进 system，护缓存前缀）             MemoryContextTransformer.kt
```

这套分层本身是对的（缓存前缀稳定、能力隔离已完成）。问题出在**第 3/5 层与 wire 层的交互**。

### 1.2 wire 层裁剪：fork 自加，且与内容写法冲突（核心矛盾）

`ai/src/main/java/me/rerere/ai/core/WireTool.kt:20-23`（fork 独有，upstream 无此文件）：

- `WIRE_DESCRIPTION_LIMIT = 300`：工具级 description 截到 300 字符 + 省略号
- `WIRE_PARAM_DESCRIPTION_LIMIT = 80`：参数内嵌 description 截到 80 字符
- 应用点：4 条 provider 路径全部经过（`ChatCompletionsAPI.kt:432`、`ResponseAPI.kt:301`、`ClaudeProvider.kt:519`、`GoogleProvider.kt:421`）

但工具描述的写法完全没考虑这个上限。**超限清单（实测）**：

**A. description > 300 且**没有** systemPrompt 兜底 —— 被砍的内容全丢（27 个：三引号长描述 22 个 + 管理模式单行长描述 5 个 + 搜索类函数拼装描述）**：

| 工具 | 长度 | 被砍掉的载荷内容（实测截断点） |
|---|---|---|
| `calendar_create/update/query` | 728/626/622 | 提醒/重复事件语义（"multiple lead times. Recurring events…"） |
| `get_screen_time` | 566 | 参数预设说明 |
| `workspace_read_file` | 537 | `start/maxBytes` 分片读语义 |
| `workspace_edit_file` | 474 | **唯一匹配语义 + 空白容忍回退**（"By default old_text must occur exactly once…"） |
| `spawn_subagent` | 469 | **整个 Usage 块**（不空等/自动唤醒/交叉验证）**与 Task 模板** |
| `workspace_glob/grep/set_env/move/delete/restore/task_status` | 308–446 | glob 语法示例、审批域、overwrite 规则等尾部语义 |
| `study_quiz/read/list`、`recent_chats`、`scrape_web`、`eval_javascript`、`trusted_folder_edit`、`delete_wrong_question` | 308–394 | 尾部"何时用/输出形态"句 |

**B. description > 300 但有 systemPrompt（4 个，部分内容在 system 兜底）**：`solve_question`(1780/`SolverTools.kt:45`)、`memory_tool`(1597/`MemoryTools.kt:45`)、`todo_write`(657)、`ask_user`(485)。

**C. 参数 description > 80（12 个）**：

| 参数 | 长度 | 后果 |
|---|---|---|
| `spawn_subagent.agentId` | 529 | **子代理清单只剩"Sub-agent id. Available sub-agents:" + 第一条的开头**；enum 值保留（id 本身可读），但"各子代理职责"的设计意图（`SubAgentTools.kt:37-38` 注释）被静默废掉 |
| `ask_user.selection_type` | 170 | "confirmation (yes/no)" 语义丢失（enum 保留） |
| `ask_user.options` | 125 | chips/选择语义丢失 |
| `skill read 的 path` | 207 | 默认读 SKILL.md 的行为丢失 |
| `update_wrong_question.title` | 191 | 30 字符限制/纯文本要求丢失 |
| `calendar_create.begin` 等 | 84–95 | 预设语义丢失 |

**D. 最严重的单点：主聊天搜索的引用格式在 300 字符外（载荷级 bug）**

- `SearchTools.kt:112-156` 完整描述 1790 字符：
  - `Multi-source:` 在 737 → 砍
  - `Response format:` 在 907 → 砍
  - `Citations:`（`[citation,domain](id)` 格式）在 **1156** → 砍
  - `Images:`（配图规则）在 **1316** → 砍
- 主聊天用它产出的回答**永远不会带引用角标**——因为模型从没被告知这个约定；而 UI 侧 `MarkdownNew.kt:1076`、`Markdown.kt:1407` 专门为 `citation,` 前缀写了渲染。**一条完整实现的功能被截断成了死代码。**（子代理不走面向用户引用，但 `web_researcher` 自己的 prompt 里保留了该格式，`SubAgentCatalog.kt:91`。）

### 1.3 静态成本实测（粗估，非超标但需知道量级）

| 配置 | 静态前缀（system + tools schema） |
|---|---|
| 默认助手（4 个工具） | ≈ 2–4k token |
| 工作区模式（PTC，约 30–40 工具） | ≈ 4.5–7.5k token（含 AGENTS/MEMORY 注入） |
| 管理模式（CREATIVE，**90+ 个工具**） | ≈ 15k token 量级 |

预算线（30% of 128k ≈ 38k）从未逼近 → **没有"必须砍总量"的压力，只有"别把关键信息砍错"的压力**。

### 1.4 记忆系统：四重敏感源

| # | 机制 | 位置 |
|---|---|---|
| 1 | 写入提示词强驱动："When to save (**proactively, without being asked**): 用户表达持久个人事实…" | `MemoryTools.kt:30-31`（随 system 每轮注入） |
| 2 | 检索**无相关性门控**：命中不足用最新补齐（注释"保证 system prompt 始终有记忆"）、恒兜底最近 2 条、≤6 条时全量注入 | `MemoryRepository.kt:196-219`（`MEMORY_FALLBACK_RECENT_N=2`、`MEMORY_FULL_INJECTION_THRESHOLD=6`） |
| 3 | 检索**副查询 = 模型上一轮回答** → 模型展开过的话题持续把相关记忆拉回上下文（自我强化） | `MemoryRepository.kt:57-62` |
| 4 | 默认助手人设写明 "Remember details about the user (via memory) to build rapport"；写入免审批、每次调用留气泡 | `PreferencesStore.kt:1353`；`MemoryTools.kt` 无 needsApproval |

另：`autoMemory`（回合结束自动整理）默认 false，不是当前主因；`enableMemory` 默认 false 但**默认助手"日常聊天"出厂即开**。

### 1.5 todo 提醒

- 触发：跨轮 ≥5 条 USER **或** 单轮 ≥3 个工具步（后者带 8 步冷却）——单轮阈值偏密。`TodoReminderTransformer.kt:11-18`
- 同一套规则三处重复：`todo_write` desc（657，尾部被砍）+ systemPrompt（356，完整）+ reminder（完整状态回显）。

### 1.6 工作区 / 环境层冗余点

- `<workspace>` 块的图片/链接 Markdown 约定（2 行长句，`WorkspaceReminderTransformer.kt:123-124`）与 `workspace_write_file` 返回体的 `"markdown"` 字段（`WorkspaceTools.kt:275-283`）重复；
- `Prefer workspace_shell / …` 行与行为层 Tool Groups、各工具 desc 三处重叠；
- AGENTS(≈900 字符自动生成) + MEMORY 索引(≈340) + cwd AGENTS(≤4096) 三段注入，上限合计 ≈12KB；
- `currentDateLabel()` 是死代码（`GenerationPrompts.kt:39`，全仓唯一引用是定义处）。

### 1.7 反馈链路

| 项 | 现状 | 问题 |
|---|---|---|
| 工具异常 | 回传 `[类名] message\n完整 stackTrace`（`GenerationHandler.kt:1228`） | 堆栈对模型无信息量、耗 token；应只给一行错误摘要 |
| 输出截断 | 32K 阈值仅在**存在 `workspace_shell` 时**生效（`GenerationHandler.kt:1090`）；无 shell 时完全不截断 | MCP/搜索大结果会整段进上下文；宜源头限流或统一上限 |
| 拒绝反馈 | `{"error":"Tool execution denied by user. Reason: …"}` | 干净，保留 |
| 死循环 | 连续 4 轮完全相同调用 → 自动停止 | 合理，保留 |
| 自动停止文案 | 中文 append 进 assistant 消息（`GenerationHandler.kt:110`） | 面向用户可接受，非问题（保持） |

---

## 2. 与 Codex 的对照（为什么它的体验"稳"）

| 维度 | Codex（实测数字） | 本 fork 现状 | 差距判定 |
|---|---|---|---|
| 工具数量 | ~15 个（shell/apply_patch/update_plan/view_image/spawn_agent/少数 MCP） | 默认 4 个，工作区模式 30–40，管理模式 90+ | 数量本身可接受（分模式隔离已做对） |
| 工具描述长度 | `update_plan` 123 token、`request_user_input` 231、`spawn_agent` 318、`exec_command` 316 —— **500–1300 字符，无截断** | 15+ 个工具写 400–1800 字符，但被截到 300 | **Codex 描述更长却无损；fork 写长却被砍** → 差距在"声明的信道设计" |
| 长说明放哪 | 进 base prompt / 独立 fragment（`spawn_agent` 的 `{agent_role_guidance}` 运行期展开） | 混在 description 里，靠 systemPrompt 兜一部分 | 需按「desc=触发条件，systemPrompt=使用策略」重新归位 |
| 记忆 | `/memories` **按需技能**（读路径 1532 token 只在用到时加载），不进每轮上下文 | 每轮强推（无门控） | **本方案 P0 的对照依据** |
| 计划 | `update_plan` 123 token，**无提醒机制**，纯被动 | todo desc 657 + systemPrompt 356 + 高频提醒 | 降到"被动 + 跨轮低频" |
| 环境 | `environment_context` ≈335 token 单片段（cwd/shell/AGENTS.md） | `<workspace>`+AGENTS+MEMORY 合计可到 12KB | 方向对，量偏大且重复 |
| 审批 | approval policy 短片段（`never` 23 token / `on_request` 758 token） | 审批门控完整（`needsApproval`），说明文案在 UI 侧 | 无差距 |
| 错误反馈 | 短文本 | 带堆栈 | 需修 |

---

## 3. 必要性评估（逐族判定）

| 能力/功能 | 现状 | 判定 | 处置建议 |
|---|---|---|---|
| LOCAL：`get_time_info` | 默认开 | **保留** | 时间感知刚需 |
| LOCAL：`eval_javascript` | 默认开 | **降级** | 对聊天场景价值低（Codex 无此工具）；建议默认关，代码/工作区类助手单独开 |
| LOCAL：`ask_user` | 默认开，审批流承载弹窗 | **保留** | 仅修参数描述裁剪 |
| TODO | 默认开（全局） | **保留但降噪** | 提醒降频 + 三处文案合一 |
| MEMORY（写入+注入） | 助手级，日常聊天默认开 | **保留但降温** | 见 §4 P0-B |
| SEARCH | 助手级 | **保留（核心）** | 修引用格式不可见（P0-A） |
| DOCUMENT 解析 | 默认 | **保留** | — |
| STUDY（13–15 工具） | 导师助手按需 | **保留** | desc 微压缩；重复句式合一 |
| MCP（使用+管理） | 助手绑定/管理模式 | **保留** | 参数描述 80 限对 MCP 透传 schema 同样生效，需放宽 |
| WORKSPACE（13 工具） | 工作区模式 | **保留（核心）** | desc 修复 + `<workspace>` 去重 |
| TRUSTED_FOLDER（10 工具） | 助手绑定 | **保留** | 9 处重复句合并 |
| SUBAGENT | 全局默认关 | **保留** | spawn 描述重写（当前 Usage 全被砍） |
| QUESTION_SOLVER | 助手级默认关 | **保留** | desc 压缩，策略已在 systemPrompt |
| DEVICE（13 工具） | 仅特定助手 | **保留** | 已 opt-in，无整 |
| MANAGEMENT（39）+ MCP_ADMIN(7) + CREATIVE(7) + CONFIG(6) | 管理模式 | **保留** | 模式隔离已正确；长 desc 重写或上移 |
| SKILL | 装了就绪 | **保留** | — |
| 提示词注入/Lorebook/时间提醒/占位符 | 默认 | **保留** | — |
| AGENTS/MEMORY/cwd-AGENTS 注入 | 工作区模式 | **保留，限额** | 总量预算 6KB |
| `currentDateLabel()` | 死代码 | **删除** | 或接入（get_time_info 已覆盖，直接删） |

---

## 4. 优化方案

### P0-A：修复 wire 截断破坏载荷的问题（≈半天）

**原则（写成约定 + 测试）：description = "是什么 + 何时用/何时不用"，300 字符内写全；使用策略/输出规范/格式约定 = `systemPrompt`（不裁剪、内容去重、缓存稳定）。** wire 裁剪保留为兜底护栏而非主机制。

具体动作：

1. **搜索引用格式上移**（修"引用死代码"）：把 `Response format / Citations / Images / Example` 段从 `SearchTools.kt` desc 移到搜索工具的 `systemPrompt`（多服务商共享同一静态文本 → 自动去重；`concise=true` 子代理路径去掉 citation/images 段）。desc 压缩为 ≤300 的触发说明。
2. **`memory_tool` 护栏上移**：desc 保留 action 教学（≤300）；把"先查 `<memories>` 等价则 edit / 重复自动拒绝 / 不主动展示记忆内容"移入 `MEMORY_TOOL_SYSTEM_PROMPT`（`MemoryTools.kt:27-34`，已有该段，追加即可）。
3. **`spawn_subagent` 重写**：desc ≤300（"派发到隔离子代理，返回 dispatched 标记"）；Usage 块 + Task 模板整体移入 spawn 工具的 `systemPrompt`；`agentId` 参数描述收敛为一句话，子代理清单移入同一 systemPrompt（enum 保留）。同步删除 `AgentBehaviorPrompt.kt:110-137` 中重复的 SUB_AGENT_DELEGATION_SECTION，只留一行指针，实现**单一来源**。
4. **参数描述上限 80 → 160**（`WireTool.kt:23`）：参数描述无处可去（schema 绑定），12 个超限参数中 10 个可恢复完整语义；实测全局增量 <1KB。MCP 透传 schema 同步受益。
5. **27 个被砍 desc 重写**（§1.2 表 + `provider_update` 550 / `mode_delete` 425 / `model_add` 420 / `assistant_update` 411 / `mode_list` 333 五个单行长描述）：统一压缩到 ≤300，尾部语义择要前移或并入各自 systemPrompt（workspace 一族可共享一段 workspace 约定 systemPrompt，天然去重）。
6. **护栏测试**：新增 `ToolDescriptionBudgetTest`——所有内置工具 `description.length ≤ WIRE_DESCRIPTION_LIMIT`、关键工具（search/memory/spawn）的"何时用"句在前 150 字符内；参数描述 ≤ 新上限。以后超限直接编译期失败，而不是运行时静默砍。

### P0-B：记忆注意力降温（≈半天）

| 动作 | 位置 | 说明 |
|---|---|---|
| 写入措辞降驱动 | `MemoryTools.kt:30-31` | 删 "proactively, without being asked"；改为"仅当用户**明确陈述**跨会话有用的持久事实时保存；不要保存一次性的提及/临时状态/可从当前对话推导的内容" |
| 去掉"恒有记忆" | `MemoryRepository.kt:210-217` | 删恒兜底最近 2 条与"命中不足补齐"；**检索无命中 → 本轮不注入 `<memories>` 块**（`buildMemoryContextBlock` 空列表已返回空，天然支持） |
| 小池也走检索 | `MemoryRepository.kt:198` | 全量注入阈值 ≤6 取消或降到 ≤2：记忆少时"每轮全量"正是最敏感期 |
| 副查询降权/移除 | `MemoryRepository.kt:57-62` | 去掉 assistant-answer 副查询（自我强化回路），只保留 USER 主查询（短则拼 3 条，已实现） |
| 人设句清理 | `PreferencesStore.kt:1353` | 删 "Remember details about the user (via memory) to build rapport" |
| 检查默认值 | `Assistant.kt:47` | 默认关保持；日常聊天助手出厂开启需用户拍板（建议保持开，但依赖上面 4 条降敏） |

预期：记忆从"每轮必出现"变为"相关才出现"，写入从"主动抓取"变为"明确陈述才记"。

### P0-C：todo 提醒降噪（≈1 小时）

- 单轮工具步提醒：阈值 3 → **6**（或仅在存在 `in_progress` 项时触发）；跨轮提醒 5 轮 → **8 轮**。
- 三处文案分工收敛：desc = 调用方式（压缩后），systemPrompt = 节奏（"一步一更"），reminder = 纯状态 + 一句话（删重复的"Do NOT batch"长句）。

### P1-A：反馈链路修复（≈2 小时）

1. 工具异常反馈：只回 `{"error":"[类名] 摘要（≤500 字符）"}`，堆栈只进 Logcat（`GenerationHandler.kt:1226-1232`）。
2. 工具输出上限：无 `workspace_shell` 时也给 32K 兜底（截断标记 + "用更精确的参数重试"提示，替代"落盘文件"路径）；同时给 `search`/`scrape` 源头限流（`resultSize` 已是 10，检查 scrape 大页截断）。
3. `AUTO_STOP_NOTICE` 保持中文（用户可见，属有意设计），无需改。

### P1-B：环境层去重与限额（≈半天）

1. `<workspace>` 块：图片/链接两行长句合并为一行，细节由 `workspace_write_file` 返回体的 `"markdown"` 字段承担（已有）。
2. 删 "Prefer workspace_shell / …" 行（Tool Groups + 工具 desc 已覆盖）。
3. AGENTS + MEMORY + cwd-AGENTS 注入总量预算 6KB（各自仍 ≤4096，超总量时按 cwd-AGENTS > env > memory-index 裁剪）。
4. 删除死代码 `currentDateLabel()`。

### P2：行为层与微优化（≈2 小时）

1. Tool Groups 段：工具总数 ≤12 时省略（小集合自描述，纯噪声）。
2. `<memories>` 块 JSON 从 pretty 改紧凑序列化（`GenerationPrompts.kt:159`，省 whitespace token；需同步指纹）。
3. `TextToSpeechTool` MiMo 条件下 1389 字符 systemPrompt 保留（条件注入，非默认路径）。
4. 管理模式 desc 模板化：39 个管理工具的 desc 大量重复句式（"Requires user approval." 等），抽公共后缀。

---

## 5. 风险与验证

| 风险 | 缓解 |
|---|---|
| 重写描述丢语义 | 以本方案 §1.2 的实测截断点为清单逐条核对；`ToolDescriptionConventionTest` 已有 "Use when/Avoid" 断言可扩展 |
| 提高参数上限增加 token | 实测仅影响 12 个参数，+<1KB；若在意可只对非 MCP 工具提 |
| 记忆门控后"该记的时候不记" | 保留工具写入路径不变，只改触发措辞；A/B 观察日常对话 |
| 改提示词打穿缓存 | 所有改动都是设置级静态变更，只影响首个请求；`PROMPT_REVISION` 同步升级 + `PromptRevisionTest` 指纹更新 |
| 搜索无引用是"一直如此" | 先修格式可见性，再用一次联网问答验证回答带引用角标 |

验证方式：
- `./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest`（含新增护栏测试）
- prompt 指纹测试按 `PROMPT_REVISION` 流程升级
- 真机抽查：联网问答（引用角标）、日常聊天（记忆不再主动出现）、工作区任务（desc 修复后的文件编辑语义理解）

## 6. 不建议做的事（明确排除）

- 不删工具族、不做"按需工具加载"重架构（能力隔离已把暴露面控制住，收益/风险比不划算）
- 不把记忆改回全量自动注入（Codex 的做法是按需技能，本 fork 保留"检索 + 尾注"是其轻量等价物）
- 不动缓存前缀设计（memories 在最后 USER 消息、system 逐字节稳定、canonicalToolOrder —— 这些是已沉淀的正确决策）
- 不引入新的"plan/update_plan"式工具（todo 已覆盖，问题只在提醒节奏）

## 7. 改动文件索引（预估）

| 动作 | 文件 |
|---|---|
| wire 参数上限 80→160 | `ai/src/main/java/me/rerere/ai/core/WireTool.kt` |
| 搜索 desc/systemPrompt 拆分 | `data/ai/tools/SearchTools.kt` |
| memory desc/systemPrompt 调整 | `data/ai/tools/MemoryTools.kt` |
| spawn desc/systemPrompt 重写 + 行为层去重 | `data/ai/tools/SubAgentTools.kt`、`data/ai/prompts/AgentBehaviorPrompt.kt` |
| 长 desc 重写（27 个） | `WorkspaceTools.kt`、`TrustedFolderTools.kt`、`CalendarTool.kt`、`StudyReadTools.kt`、`StudyQuizTools.kt`、`ConversationTools.kt`、`JavascriptTool.kt`、`ScreenTimeTool.kt`、`StudyEditTools.kt`、`CreativeTools.kt`、`ManagementTools.kt`、`AgentConfigTools.kt`、`SkillsTools.kt`、`WrongQuestionTools.kt`、`local/AskUserTool.kt`、`SolverTools.kt`、`TodoTools.kt` |
| 记忆降敏 | `data/repository/MemoryRepository.kt`、`service/ChatService.kt`、`data/datastore/PreferencesStore.kt` |
| todo 提醒降频 | `data/ai/tools/TodoReminderTransformer.kt` |
| 反馈修复 | `data/ai/GenerationHandler.kt` |
| 环境层去重/限额 | `data/ai/transformers/WorkspaceReminderTransformer.kt`、`GenerationPrompts.kt` |
| 护栏测试 | `app/src/test/.../ToolDescriptionBudgetTest.kt`（新增）、`tools/check_tool_desc.py`（新增脚本）、`PromptRevisionTest.kt`、`AgentBehaviorPromptTest.kt`、`WorkspaceReminderTransformerTest.kt`、`MemoryRepositoryQueryTest.kt`、`UserProfilePromptTest.kt`、`MemoryToolsTest.kt` |

---

## 8. 实施记录（2026-09-18 落地）

全部 P0–P2 已实施。`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest`（110 类 / 887 用例）与 `:ai:testDebugUnitTest`（35 类 / 240 用例）全绿。
`PROMPT_REVISION` → `2026-09-18-v1`，指纹 `58513989da0e7f52`（PromptRevisionTest 已验证，新增 7 个 system 信道片段纳入覆盖）。

### 已实施

| 项 | 内容 | 位置 |
|---|---|---|
| **P0-A wire 预算** | 参数描述上限 80 → **160**（12 个超限参数中 10 个恢复完整语义）；新增 `ToolDescriptionBudgetTest`（desc ≤300 / 参数 ≤160 / citation 契约在 system / 工具名唯一）；仓库脚本 `tools/check_tool_desc.py` 做全量扫描（含 JVM 不可构造的工具） | `ai/.../core/WireTool.kt`、`app/src/test/.../ToolDescriptionBudgetTest.kt`、`tools/check_tool_desc.py` |
| **P0-A 搜索引用格式** | `Response format / Citations / Images / Example` 段从 description（第 1156 字符处被截）上移到共享 `systemPrompt`（多服务商内容相同 → 自动去重注入一次）；desc 压到 251/218 字符；scrape 转换语义同类上移 | `data/ai/tools/SearchTools.kt` |
| **P0-A memory** | desc 从 1597 → 295 字符（保留 action 教学）；护栏（保存门槛/先查 `<memories>`/敏感信息/不主动复述）移入 `MEMORY_TOOL_SYSTEM_PROMPT`（544 → 780） | `data/ai/tools/MemoryTools.kt` |
| **P0-A spawn_subagent** | desc 从 2556(469 头) → 281 字符；子代理清单从 agentId 参数描述（80 字符截断点后）移到 `SUBAGENT_SPAWN_SYSTEM_PROMPT`（清单 + 委派时机 + 异步用法 + Task 模板）；行为层 `SUB_AGENT_DELEGATION_SECTION`（1637 字符）删为一行指针，消除双重叙述 | `data/ai/tools/SubAgentTools.kt`、`data/ai/prompts/AgentBehaviorPrompt.kt` |
| **P0-A 其余 27 个超限 desc** | workspace 12（cwd 语义删重复——`putPathProperty` 已承载）、calendar 4（时区/权限提取为共享 `calendarSystemPrompt()`）、study 4、trusted_folder 1、conversation 1、javascript/screen_time/ask_user/solve_question/todo_write 各 1；管理面 4（mode_* 共用 `modeToolsSystemPrompt()` 动态生成能力名单、provider/assistant 管理提取 systemPrompt） | 各 tools 文件 |
| **P0-B 记忆降温** | ① 写入措辞删 "proactively, without being asked" → "Only when the user explicitly states..."；② `MEMORY_FALLBACK_RECENT_N` 恒兜底删除、≤6 全量注入阈值 → 2、检索不足不补齐；③ 副查询（模型上一轮回答）删除，只留 USER 主查询；④ 检索失败不再降级全量（宁缺毋滥）；⑤ 默认助手人设句 "build rapport" 删除 | `MemoryTools.kt`、`MemoryRepository.kt`、`ChatService.kt:2074`、`PreferencesStore.kt:1353` |
| **P0-C todo 降噪** | 单轮工具步阈值 3 → 6、跨轮 5 → 8；reminder 删 "Do NOT batch..." 重复纪律（该分工归 systemPrompt） | `TodoReminderTransformer.kt` |
| **P1-A 反馈修复** | 工具异常只回 `[类名] 摘要(≤500 字符)`，堆栈只进 Logcat；输出上限对所有工具生效（无 shell 时给"收窄请求"引导，有 shell 保持落盘 + cat/grep 路径） | `GenerationHandler.kt` |
| **P1-B 环境层** | `<workspace>` 块：图片/链接两行长句合并为一行（细节由 write 结果 `markdown` 字段承担）、删 "Prefer workspace_shell..."（Tool Groups/desc 已覆盖）；三段 AGENTS/MEMORY/cwd 注入共享 6KB 总预算（`allocateEnvBudget` 纯函数，cwd > env > memory 优先级，新增 5 条单测）；删死代码 `currentDateLabel()` | `WorkspaceReminderTransformer.kt`、`GenerationPrompts.kt` |
| **P2 微优化** | Tool Groups 工具数 <12 时跳过（小集合自描述）；记忆块 JSON 紧凑序列化（省 whitespace token，测试防 pretty 回归） | `AgentBehaviorPrompt.kt`、`GenerationPrompts.kt` |

### 与原方案的偏差

1. **未改 `provider_update` / `model_add` / `assistant_update` 的字段清单删除**：字段清单是"能改什么"的发现性来源，删除会降低能力可发现性，改为上移 systemPrompt（`PROVIDER_ADMIN_SYSTEM_PROMPT` / `ASSISTANT_ADMIN_SYSTEM_PROMPT`）。
2. **`mode_*` 能力名单动态生成**：原计划手写压缩，实施改成从 `Capability.entries` 生成——新增能力项自动同步，根治"两处手工维护漂移"。
3. **`allocateEnvBudget` 单段上限先于总预算生效**：cwd 段先受 4096 单段上限约束，剩余额度给 env/memory（测试按此语义固化）。
4. **ToolDescriptionConventionTest 的 "Use when/Avoid" 约定保留**：memory desc 压缩时补回 "Use when ... avoid ..." 句式以同时满足两条测试约定。

### 验证

- `./gradlew :app:compileDebugKotlin` 通过
- `./gradlew :app:testDebugUnitTest`：110 测试类 / 887 用例，0 失败
- `./gradlew :ai:testDebugUnitTest`：35 测试类 / 240 用例，0 失败
- `python tools/check_tool_desc.py`：违规 0（实施前 35）
- 新增/更新测试：`ToolDescriptionBudgetTest`（新）、`WorkspaceReminderTransformerTest`（+5 环境预算用例）、`MemoryRepositoryQueryTest`（副查询移除防回归）、`PromptRevisionTest`（指纹扩展）、`AgentBehaviorPromptTest`（小集合跳过分组）、`UserProfilePromptTest`（紧凑 JSON 断言）、`MemoryToolsTest`（Guardrails 断言）

### 待真机抽查（用户自查）

1. 联网问答 → 回答应带 `[citation,domain](id)` 角标（此前该格式从未送达模型）
2. 日常聊天 → 记忆不再每轮主动出现；仅相关时注入
3. 工作区任务 → 文件编辑语义（唯一匹配/空白容忍）恢复可见
4. 子代理派发 → 模型按 Task 模板组织 task 参数（此前模板在截断点外）

## 9. P1-A 自审修复（2026-09-19 补记）

P1-A 落地后的自审发现：移除 `|| !hasShellAccess` 让截断对无 shell 会话生效时，JSON 分支
沿用的 `boundToolOutput` 是**子代理尺度**的预算（字符串 500 / 数组 6）——无 shell 会话
没有 /tool_outputs 找回通道，trusted_folder_search 的 50 条匹配、search_web 的 10 条结果、
kb_search 的分块、长笔记正文会被静默砍成 6 条 / 500 字符且无任何标记。属能力性倒退。

### 修复（BoundBudget 分档 + 渐进降档）

| 项 | 内容 | 位置 |
|---|---|---|
| 预算分档 | 新增 `BoundBudget`（DEFAULT 500/6 / MEDIUM 2000/20 / WIDE 4000/50）：有 shell 主聊天与子代理保持 DEFAULT（全文可 cat 找回，对齐旧行为）；无 shell 主聊天按 WIDE → MEDIUM 降档 | `SubAgentLoop.kt` |
| 档位选择 | `pickBoundedJsonBudget` 纯函数：无 shell 从宽到严试算产物，取首个 ≤32K 的档位，全部超限/非 JSON 落 DEFAULT；选择逻辑与 Android Context 解耦、可 JVM 单测 | `SubAgentLoop.kt` |
| 接线 | GenerationHandler 的 `boundedJsonOrNull` 改由 `pickBoundedJsonBudget` 供档，替代"WIDE 一步降 DEFAULT"的断崖 | `GenerationHandler.kt` |
| 防回归测试 | 19 用例（+6）：MEDIUM 必须装下"10 块 × 5KB"（WIDE ≈41K 超限、MEDIUM ≈21K 合规）；shell 恒 DEFAULT；单大串命中 WIDE；多块超限降 MEDIUM；病态形状（200×2000）落 DEFAULT；非 JSON 落 DEFAULT | `BoundToolOutputTest.kt` |

关键点：**不能 WIDE 一步降 DEFAULT**。kb_search 10 块 × 5KB 的 WIDE 产物 ≈41K 恰好超 32K
上限，一步降 DEFAULT 会砍回 6×500——与分档要修的"10 块 → 6 块"完全相同。MEDIUM 中档
让该场景保留全部 10 块（内容 2000/块）。数组分支（顶层 JSON 数组）保持原数组形状不注
入标记（渲染器按数组读，注入字段会破坏形状），多块对象输出均有 `truncated` 标记。

### 自审覆盖范围（未发现其他问题）

错误单行化（`[SimpleName] message≤500`，堆栈在 Log.w 进 Logcat）、hasShellAccess 判定
（`workspace_shell` 是唯一能读 /tool_outputs 挂载的工具，trusted_folder 无 shell）、文件
落盘/找回提示的 shell 门控、下游消费方（ToolParseCache/UI 渲染器/文件变更提取/死循环
检测用入参指纹不受错误文案影响）均核对无回归。P0-B 记忆降敏与 P1-B 环境预算各有独立
测试覆盖。
