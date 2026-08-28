# 子代理 Token 优化方案

> 状态：方案（未实施）｜ 关联代码：`app/.../data/ai/subagent/`、`app/.../data/ai/tools/SubAgentTools.kt`
> 背景：用户反馈子代理即使完成简单任务也消耗大量 token。本方案对照主流 agent 实现（Claude Code、DeepSeek、Gemini、Codex 的公开实践）给出按 ROI 排序的优化路径。

## TL;DR

子代理烧 token 的**根因不是单请求贵，而是多步循环里上下文单调膨胀 + 每步全量重发**，叠加三个放大因素：

1. **推理内容（Reasoning）完整回传**：OpenAI 系（含 DeepSeek）请求序列化时思考文本默认随历史重发（`ProviderSetting.kt:99` `includeHistoryReasoning=true`），思考常是输出的数倍且**无上限**（`SubAgentLoop.kt` 只对 Text 部分做 8000 字符裁剪，Reasoning 不裁）。
2. **上下文天花板偏高**：最多保留 30 条消息 × 单条 8000 字符（`SubAgentLoop.kt:34/38`），长任务可膨胀到 60–120k token 才触发裁剪，而裁剪是**硬丢弃**（无摘要补偿）。
3. **简单任务无快路径**：除 planner（maxSteps=1）外，搜索/文档/代码子代理默认 16–32 步（`SubAgentCatalog.kt`），一步能答完的搜索也按完整 worker 跑。

主流的对应解法：**DeepSeek 官方指引多轮重发时移除 reasoning_content；Anthropic 用 prompt caching + 上下文自动压缩（auto-compact）；Gemini 用稳定前缀 cachedContent；社区共识是"简单任务给瘦子代理/快通道，不要加载完整 agent 上下文"**（Claude Code 用户实测：子代理加载全部上下文只跑 3 条命令，token 爆炸，与本项目现象一致）。

值得肯定：本项目已有 Claude 完整 prompt caching（provider 级开关，system+tools+滚动断点自动打，见 `ClaudeProvider.kt:448-475`）、工具输出结构安全截断、搜索工具 concise 模式、父上下文摘要（8 条/1600 字符）。方案在其之上补短板，全部改动集中在 subagent 包内，不动 provider 序列化层。

## 现状：token 花在哪（数字推导）

`subAgentRunLoop`（`SubAgentLoop.kt:59`）每步把完整 `messages` 重发给 provider，消息体量单调增长：

| 项 | 现状上限 | 说明 |
|---|---|---|
| 消息条数 | 30 条（含 system） | `MAX_CONTEXT_MESSAGES`，超出丢最早 |
| 单条 Text | 8000 字符 | `MAX_MESSAGE_TEXT_CHARS`，超出丢较早文本段 |
| 工具输出 | 3000 字符/条 | `MAX_TOOL_OUTPUT_CHARS` |
| Reasoning | **不裁剪** | append-only，随每一步重发，无上限 |
| 步数 | 16–32 | web_researcher=24、code_runner=32 |

最坏态势：24 步任务，context 涨到约 60–120k token，每步按全量重发，总投入 ≈ 数十万 token；无缓存的 OpenAI 兼容 provider 全价计费。这解释了"简单任务也贵"：
- 简单搜索 1–3 步能完成的，仍会先付 system（含全部工具 schema 说明）+ 任务 + 父上下文的全价首包；
- 其中若有 reasoning 模型，思考文本每步重发一次。

### 静态前缀实测构成（"没调多少工具也 40-60k"的根源）

**任务累计输入 ≈ 静态前缀 × 步数 + 每步增量**。工具**调用**次数占比很小，工具**声明**数量和步数才是乘数：

| 成分 | 量级（token） | 说明 |
|---|---|---|
| system 消息（职责 prompt + Final Output Contract + 并行说明 + todo） | 1.5–2.5k | 每步重发 |
| 工具声明（wire 层统一裁剪：描述 ≤300 字符、参数描述 ≤80 字符，见 `WireTool.kt`） | 150–400/个 | `search_web`/`scrape_web` + 每个启用搜索源 ×2；**MCP = assistant 所挂全部已连接服务器的全部启用工具，全量原样注入**（`SubAgentToolAssembler.kt:66-84`，无数量上限、无相关性过滤） |
| 任务消息（母代理按模板写的 Background/Objective/…） | 0.5–3k | 每步重发 |
| 父上下文摘要（8 条 / 1600 字符） | ~0.8k | 每步重发 |
| 每步增量（tool_call + 工具结果 ≤3000 字符/条） | 0.7–1k/步 | 仅当步数增长 |

典型账本：**5 个搜索源（≈12 个工具 ≈ 3–4k）+ 2 个 MCP server 共 15 个工具（≈ 3–6k）→ 前缀 8–15k token，× 3–5 步 = 24–75k**——与用户实测 40–60k 完全吻合。注意 usage 表里 `promptTokens` 是**原始计数**：Claude 缓存命中的部分账单打一折但数字照计；无缓存的 OpenAI 兼容 provider 全价。

## 主流方案对照

| 主流做法 | 出处 | 本项目现状 |
|---|---|---|
| 多轮重发时移除 reasoning_content | DeepSeek 官方 API 文档 | ❌ 默认回传（includeHistoryReasoning=true） |
| prompt caching（system+tools+滚动断点，命中最优先保证前缀稳定） | Anthropic 文档 / Claude Code | ✅ Claude 已完整支持；Google/OpenAI 依赖各自自动缓存，未验证命中 |
| 上下文自动压缩（接近上限时把旧消息摘要替换） | Claude Code auto-compact / Codex digest / LangGraph summarization | ❌ 硬截断丢弃 |
| 简单任务快通道（瘦上下文、1 步、廉价模型） | Claude Code subagent 实践、社区共识「subagents 加载完整上下文跑 3 条命令浪费 token」 | ❌ 只有 planner 是 1 步 |
| 步骤早停（重复工具、空转检测） | 通用 agent 工程实践 | ⚠️ 仅"无 pending 工具即 break" |
| 用量可观测（prompt/cached/completion 拆分） | Claude Code /status 等 | ✅ 已落库 `SubAgentUsageEntity`，但无展示、无对比手段 |
| 工具描述精简、只装配必要工具 | Anthropic token 优化实践 | ✅ 已做（concise、CanonicalOrder、MCP 白名单） |

## 优化方案（按 ROI 排序）

### P0-A 推理内容剥离（单个收益最大）

**做法**：`subAgentRunLoop` 每步发送前，把消息列表里的 `UIMessagePart.Reasoning` 过滤掉（Text/Tool 保留）。UI 时间线不受影响——`onMessagesUpdate` 推给 UI 的仍是完整序列，只在构造请求体时剥。
**开关**：沿用 `includeHistoryReasoning`（默认 true）会与主聊天耦合，建议子代理侧独立默认关闭——发送前过滤时不过问该字段，直接剥离；若个别模型需要先前思考续接（部分 OpenAI o 系），留一个设置项（`Settings.subAgentKeepReasoning`）恢复。Claude 系无需处理（Anthropic 协议本身不回传 thinking）。
**预期收益**：reasoning 模型长任务 context 直降 50–80%；简单任务首包也降。
**风险**：个别模型缺思考续接质量略降 —— 以开关兜底，默认关闭。

### P0-B 收紧上下文上限 + 回传截断

`SubAgentLoop.kt` 常量调整：

- `MAX_CONTEXT_MESSAGES` 30 → **14**；
- `MAX_MESSAGE_TEXT_CHARS` 8000 → **4000**；
- `MAX_TOOL_OUTPUT_CHARS` 3000 → **2000**（搜索/抓取结果足够，且结构安全截断已保证 JSON 合法）；
- 新增 `MAX_REASONING_CHARS`（如 4000，仅影响内存/详情页展示，发送前已剥离）。

**回传截断**（母代理侧省 token）：`subAgentResultSummary`（`SubAgentLoop.kt:305`）的 `summary` 目前是**最后一段文本完整返回**（无 take 截断，仅 toolTexts 截了）。改为 `take(4000)`，超长加 `…[已截断]` 提示；`subAgentResultPayload`（`SubAgentTools.kt:196`）的 `summary` 字段随之受益。
**预期收益**：全任务 context 上限从 ~120k token 级降到 ~30k 级；母代理不被长摘要撑爆。
**风险**：极低。4k 字符足够承载结论级输出（Final Output Contract 本就要求结论前置）。

### P0-C 快路径 quick mode（直接治"简单任务也贵"）

**做法**：

1. `SubAgentRequest` 加 `quick: Boolean = false`；`spawn_subagent` 工具参数加 `quick`（布尔，描述"简单任务用此模式，单步直接作答"）。
2. `runInternal` 中 `quick=true` 时：`maxSteps = min(def.maxSteps, 1)`，system 追加一句 `You are in QUICK MODE: give a direct, final answer with at most ONE tool call. Do not plan, do not iterate.`
3. `spawnBehavior` 提示词补充："需要一次搜索/一次抓取即可回答的简单问题，务必用 quick"。强制指令（/search）保持完整模式。

**预期收益**：简单搜索类任务从 2–6 步压到 1 步，结合 P0-A/B，单任务 token 降 50%+。
**风险**：quick 模式结果深度不够 —— 由母代理自由裁量是否派完整 worker 兜底（失败/信息不足时再补派），系统已有该能力（spawn 不中断母代理回合）。

### P0-D MCP 工具声明瘦身（前缀最大可变项）

`SubAgentToolAssembler.kt:66-84` 把 MCP 工具全量原样注入（description/schema 不裁剪、不设数量上限）——对挂了多个 MCP server 的用户，这是静态前缀的最大可变项。做法：

1. **装配层裁剪**：description 来到就 `trimDescription(WIRE_DESCRIPTION_LIMIT)`，schema 递归裁剪（复用 `WireTool.kt` 的机制，不再依赖各 provider 的 wire 层兜底）；
2. **数量上限 + 保序**：单 server 工具数超过 N（如 12）时，按 description 长度排序只保留前 N 个，剩余并入一个 `mcp__other__available` 提示工具（列出名字，不暴露 schema）——模型需要时经母代理或提示等待；
3. **低频工具降级**：`needsApproval=true` 的工具子代理本来就不能执行（`SubAgentLoop.kt:160` 回填错误跳过），**装配时直接剔除**，省声明 + 省一次必然失败的回填。

**预期收益**：MCP 重用户前缀降 30–70%；顺带消除"子代理必踩审批工具"的无效往返。
**风险**：模型可能短暂找不到边缘工具——由降级提示兜底，且母代理侧工具不受影响。

### P1-E 上下文压缩 compaction（长任务核心手段）

**做法**：`subAgentRunLoop` 每步开头检测：非 system 消息累计字符 > **24k**（或步数 > 8）时触发一次压缩——
取最早的非 system 消息（任务必须保留，它是部署根），调一次模型生成 300–600 字符「已执行内容摘要」，替换为一类新消息 `UIMessage.user("【早前执行摘要】（由系统生成）：\n…")`；最近 6–8 条保持原文；system 不动。
摘要 prompt 要点：**完整保留已确认的事实、数据、文件路径、URL、工具结论**，按时间线组织。
**成本**：每任务 0–3 次小输出调用（压缩输出本身 300–600 token），换来 context 从 60k+ 封顶到 ~10k 级。
**实现位置**：压缩调用复用循环内 provider/client，可加 `maxTokens` 小值；失败静默跳过（不阻塞主流程）。
**风险**：摘要丢细节 → 摘要 prompt 已约束信息完整性 + 保留最近原文；与 P0-B 的硬截断并存，作为兜底。

### P1-F 早停启发式（防"空转长跑"）

- **重复检测**：连续两轮工具名+入参完全相同且其间无新文本输出 → 第二轮结果回填 `error: "与上一步完全相同的操作已执行过，请更换策略或直接给出结论"`，若仍重复则 break（保留已产出内容）。
- **空转检测**：一轮内工具全部失败/全部空输出 → 终态前最后一步输出提示模型转为直接回答。
- **成本**：近零；与现有"无 pending 工具即 break"互补。

### P1-G 轻模型分层

`resolveModel`（`SubAgentRunner.kt:132`）已支持 `defaultModelId`，但 catalog 中全部为 null，实际全部落 `subAgentModelId`（未配置则 `chatModelId`）。低成本做法：

- 设置页加说明文案：建议把 `subAgentModelId` 配成轻快模型（quick 模式强制走它），长任务子代理（code_runner）用 `defaultModelId` 覆盖。
- `SubAgentDefinition` 各条加合理的 `defaultModelId` 注释指引（不强制，随用户配置）。
- 不动结构，仅引导。

### P2-H 用量观测（验证一切的前提）

数据已在 `SubAgentUsageEntity`（prompt/completion/cached/cacheWrite + 模型 + 状态）。补两块：

1. 子代理详情页/管理台展示 **cache 命中率**（`cached/(prompt+cached)`）与单任务总 token —— 一眼看出哪家 provider 没吃到缓存。
2. 任务终态打一行汇总日志：`steps / prompt / completion / cached / cacheWrite / total`，便于优化前后 A/B。

## 预期收益（粗估）

| 场景 | 现状 | 优化后 |
|---|---|---|
| 简单搜索（1–3 步，非缓存 provider） | 每步全量重发 + 完整 system/tools | quick 1 步 + reasoning 剥离 + 4k 截断 → **降 50–70%** |
| 长文档分析（10+ 步） | context 膨胀至 60k+ 再硬丢 | 14 条/4k 上限 + compaction + 早停 → **降 60–80%** |
| Claude 用户 | 已吃缓存，但 reasoning 重发、上限松 | 缓存命中保持，payload 再降 → **再降 30–50%** |

## 实施顺序与验证

1. P0-A/B/C/D 一次性落地（同一批文件：`SubAgentLoop.kt`、`SubAgentRunner.kt`、`SubAgentRequest.kt`、`SubAgentTools.kt`、`SubAgentToolAssembler.kt`）。
2. 验证：`./gradlew :app:compileDebugKotlin` + 三个手工场景（简单搜索 / 长文档 / 代码任务）跑通，对比 P2-H 的日志与 `SubAgentUsage` 表。
3. P1-E/F 第二批；P1-G 与设置 UI 一起做。
4. 每批落地后对比同场景 usage 数据，回写本文件结论。

## 明确不做的

- **并行执行单步内多个工具**：不省 token（内容照收），只省时间，另立优化项。
- **子代理间共享缓存预置**：当前单循环结构无此必要。
- **改 provider 序列化层统一剥 reasoning**：会波及主聊天行为，风险大；子代理侧过滤即可。