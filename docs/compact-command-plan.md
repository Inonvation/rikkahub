# /compact 命令与上下文压缩优化方案

> 目标：新增 `/compact` 斜杠命令手动触发上下文压缩，并对照主流 coding agent（Codex CLI / Claude Code / OpenCode）的压缩方案补齐架构差距。
>
> 结论先行：**本项目压缩管线已完成约 80%**（分块摘要、快照、非破坏性 UI、自动压缩、Codex 式提示词均已存在），真正的缺口是 ①`/compact` 命令入口、②工具调用结果没有进入摘要器输入、③（可选）无 LLM 成本的旧工具结果修剪层。

## 实施状态（2026-08-28）

**已落地**（P0 全部 + P1 摘要质量 + P2 弹窗默认值，`:app:compileDebugKotlin` 与 `:ai`/`:app` 全量单测通过）：

- `/compact [附加指令]` 命令：`parseCompactCommand`（ChatService.kt，internal 纯函数）+ `sendMessage` 内拦截分支（不落库、processingStatus 反馈、群组会话友好拒绝、默认 keep=10 / target=窗口一半、附加指令透传 `additional_context`）。
- 摘要输入增强：`UIMessage.serializeForSummary()`（ai/ui/Message.kt）——工具调用入参/结果预览（500/1200 截断）、附件占位、Reasoning 跳过；`compressConversation` 切换到该序列化。
- 压缩范围/分块重构：`splitCompressScope`（短会话返回 null → no-op 语义，`compressConversation` 返回 `Result<Boolean>`）、`splitByCharBudget`（96k 字符预算切块替代固定 256 条，防压爆压缩模型窗口）。
- `DEFAULT_COMPRESS_PROMPT` 升级：6 段结构化 + 防语境漂移（关键原文直引）+ 工具交互要点，占位符与用户自定义机制不变。
- UI 收尾：自动压缩与弹窗默认值统一 `DEFAULT_COMPRESS_KEEP_RECENT_MESSAGES = 10`；弹窗 target 默认窗口一半（修掉 0 值静默关闭 bug）；`resolveContextTokenLimit` 下沉 `utils/ContextTokenLimit.kt` 三处共用；新增字符串 `chat_page_compress_nothing_to_compress`、`error_compact_group_unsupported`。
- 测试：`ChatServiceTest`（命令解析/范围切分/字符预算切块）、`MessageSummaryTest`（ai 模块，6 用例）。

**未实施（Phase 3 可选项，待后续任务）**：microcompact 旧工具结果修剪层（4.3）、超长反应式压缩 + 熔断（4.4）、`CompressedHistoryCard` 元信息行（4.6 剩余项）。

## 一、现状盘点（已有能力）

| 能力 | 位置 | 说明 |
|---|---|---|
| 压缩执行器 | `ChatService.kt:2586` `compressConversation()` | 分块（256 条/块，递归二分）并行摘要；保留最近 N 条（默认参数 32）；专用压缩模型 `compressModelId`（默认跟随当前聊天模型）+ 用户可配置 `compressPrompt`；后台小预算重试 `BACKGROUND_RETRY_POLICY` |
| 压缩快照 | `Conversation.kt:163` `CompressedHistory` | `messages`（摘要 USER 消息 + 保留的尾部消息）+ `lastOriginalMessageId` + `summaryText` |
| 生成时生效 | `Conversation.kt:78` `effectiveMessages()` | AI 请求用「压缩快照 + 压缩后新增消息」，`handleMessageComplete` 已接入（`ChatService.kt:1657`） |
| 非破坏性展示 | UI 保留完整 `messageNodes` | 摘要消息打 `isSynthetic` 只进请求不进显示列表（`ChatService.kt:224` `displayMessagesForChunk`），OpenCode 式「数据不丢」 |
| 摘要卡片 | `ChatList.kt:766` + `CompressedHistoryCard` | 压缩后在消息列表顶部展示 summaryText |
| 手动入口 | `ChatPage.kt:777` → `CompressContextDialog` | 菜单弹窗：附加指令 / 目标 tokens / 保留条数 / 自动压缩开关与阈值 |
| 自动压缩 | `ChatPage.kt:705-728` | 阈值默认 80%（`AUTO_COMPRESS_THRESHOLD`），触发参数 `targetTokens = limit/2`、`keep = 32`；回落阈值 = 阈值-10 才允许下次触发 |
| 默认提示词 | `prompts/CompressPrompt.kt` | 已是 Codex 式「CONTEXT CHECKPOINT COMPACTION 交接摘要」：4 要点 + 累积 Historical Context 段 + `{content}/{target_tokens}/{additional_context}/{locale}` 占位符 |
| 快照失效 | `compressedHistory = null`（多处） | 编辑 / 重生成 / 删除消息等历史截断路径全部重置，防止快照失配 |
| 命令先例 | `/init`（`ChatService.kt:1136`）、`/search` 等（`SubAgentCommands.parseAll`） | 斜杠命令在 `sendMessage` 内拦截改写 |

## 二、主流方案调研摘要

来源：[掘金《Codex、Claude Code 与 OpenCode 的上下文压缩术》](https://juejin.cn/post/7627400127372361774)、[openai/codex 仓库](https://github.com/openai/codex)、[Codex issue #21468（/compact 摘要可见性与自定义指令）](https://github.com/openai/codex/issues/21468)、[Codex 最佳实践指南](https://liduos.com/posts/codex-prompting-guide/)、[badlogic 的 Context Compaction Research](https://gist.github.com/badlogic/cd2ef65b0697c4dbe2d13fbecb0a0a5f)。

参考场景数据：一条 26 条消息、~15.4k tokens 的排查对话中，**5 条工具结果占 ~12.5k tokens（81%）**——工具结果是上下文的大头，这是下面差距分析的基准。

| 维度 | Codex CLI | Claude Code | OpenCode | 本项目现状 |
|---|---|---|---|---|
| 手动触发 | `/compact`（本地 `compact.rs`，提示词模板 `templates/compact/prompt.md`） | `/compact [自定义指令]` | `/compact`、`/summarize` | 仅弹窗入口，**无命令** |
| 自动触发 | 接近窗口上限时；不够再头部修剪 | 有效窗口 − 13k tokens；优先用 session memory 免 LLM 压缩 | 阶梯式 | 已有（80% 阈值，UI 层触发） |
| 摘要范围 | **保留全部 User 原文**，物理删除 Assistant/Tool，插入伪造 Assistant 交接摘要 | 全量摘要，但要求直接引用关键原文短语防语境漂移 | 全量摘要 + 自动重放最后一条用户消息 | 全量摘要（文本 part），保留尾部 32 条 |
| 工具结果 | 物理删除 | 第一层：旧结果替换为 `[Old tool result content cleared]` 占位符（零 LLM，保护最近若干调用） | 时间戳标记隐藏（释放 >20k 才执行，保底 40k，保护最近 2 个用户回合） | **无此层**（仅执行时的 32KB 截断 `GenerationHandler.kt:878`） |
| 缓存策略 | 无特殊设计 | 只在尾部修整，保持前缀稳定以命中 Prompt Cache | 侧重减少重复读取 | 压缩后头部全变（一次性缓存失效，与 Codex 同级） |
| 压缩失败兜底 | 头部修剪 | `prompt_too_long` → 反应式压缩重试；连续 3 次失败熔断 | — | 无 |
| 压缩后行为 | 被动等待 | 重读最近编辑的文件（≤5 个/50k 预算）+ 注入延续引导 | 重放最后一条用户消息 | 保留尾部消息已覆盖 |
| 透明度 | issue 求摘要可见 | — | — | **领先**：CompressedHistoryCard 常驻可见 |
| 提示词风格 | 4 要点交接（进展/约束/待办/关键数据） | 9 段结构化（意图/概念/文件/错误/推理链/用户消息摘要/待办/当前工作/下一步） | 5 段 + 跟随用户语言 | Codex 4 要点 + 累积历史段（已对齐） |

**共同设计模式**：交接摘要（handoff）而非会议纪要；用户最后意图必须原样可达；非物理删除留后路；分层治理（便宜的先上：规则修剪 → 标记隐藏 → LLM 摘要）；压缩本身不当对话轮。

## 三、差距分析（按优先级）

1. **P0 缺 `/compact` 命令**：主流全部以命令为一等入口，本项目只有弹窗（深、慢、参数多）。命令应支持 `附加指令`（对齐 Claude Code `/compact Focus on API changes`，现有 `additional_context` 占位符已支持透传）。
2. **P1 摘要器看不到工具交互**：`UIMessage.summaryAsText()`（`ai/ui/Message.kt:43`）只输出 `Text` part，`Tool` part 输出空串、图片/文档也丢——**81% 权重的工具结果完全不进摘要**，长 agent 会话压缩后必然丢失关键信息（读了什么文件、改了什么、测试结果）。这是当前实现最大的质量缺陷。
3. **P1 缺「旧工具结果修剪」层**（Claude Code 第一层 / OpenCode Prune）：零 LLM 成本就能腾空间，对本项目 agent 模式（workspace 工具、子代理长会话）收益最大。
4. **P2 缺反应式压缩**：请求因超长失败时自动压缩重试（Claude Code `prompt_too_long` 路径）。
5. **P2 弹窗默认值缺陷**：`CompressContextDialog.kt:207` 要求 target/keep 都 >0 才执行，而初始值都是 0——用户不改参数点保存会静默关闭，疑似 bug。
6. **领先项保持**：压缩快照非破坏性 + 摘要卡可见 + 可配置提示词，比 Codex（不可逆、不可见）更好，无需改动。

## 四、方案设计

### 4.1 P0：`/compact [附加指令]` 命令

**语法**：`/compact` 或 `/compact <附加指令>`；附加指令非空时作为 `additionalPrompt` 透传给现有 `compressConversation`。

**拦截位置**：`ChatService.sendMessage()` 内、群组分支之后、`/init` 与 `SubAgentCommands` 判定之前（新增独立分支，参照 `/init` 的解析写法 `ChatService.kt:1136`）。要点：

- **命令本身不落库**：不追加 messageNodes、不生成回复轮。主流 agent 均不把命令当消息（避免污染历史 + 烧 token）；用户反馈靠 ① 顶部 `CompressedHistoryCard` 出现 ② 输入框上方 processingStatus ③ 上下文占用圈下轮刷新。
- **复用生成 job 通道**：分支内直接跑 `compressConversation`，仍包在 `launchGenerationJob` 里（`keepAliveInBackground = true`，长压缩有前台服务保活）；`sendMessage` 既有 `previousJob.join()` 语义让「生成中输入 /compact」自然排队而不是打断。
- **状态反馈**：压缩前 `session.processingStatus.value = "正在压缩上下文…"`，结束置 null（该状态流 UI 已接）。
- **默认参数**：`targetTokens = (contextTokenLimit / 2).coerceAtLeast(1)`、`keepRecentMessages = 10`（约 5 轮：保住最后意图与当前工作集，又不至于在 agent 会话里把压缩释放的空间吃回去——一条 assistant 消息会打包整轮工具结果，32 条可能让压缩后占用仍高于自动压缩的重置带）。contextTokenLimit 取 `resolveContextTokenLimit(modelContextTokenLimit, assistant.contextTokenLimit)`（`CompressContextDialog.kt:37` 同款逻辑，下沉到可共用处）。自动压缩路径（`ChatPage.kt:719`）的 32 同步改为 10，两入口一致。
- **守卫**：群组会话直接 return；压缩模型缺失走现有 `IllegalStateException("No model available for compression")` → `addError` 报错；消息内容含附件/非文本时只取文本指令部分。`compressConversation` 在 `allMessages.size <= keep` 时会抛"消息不够"（`ChatService.kt:2617`）——`/compact` 与弹窗默认值场景应改为友好 no-op 提示（"会话太短，无需压缩"），不报错。
- **与子代理命令的关系**：`SubAgentCommands.parseAll` 目前不含 `/compact` 不冲突；在 `sendMessage` 注释中声明优先级——`/compact` 先于子代理命令解析。

**改动面**：`ChatService.kt`（一个分支 + 常量）、`ChatVM.kt`（无需新方法，走 sendMessage）、`strings.xml`（状态文案，可选）。ChatVM 已有的 `handleCompressContext`（`ChatVM.kt:266`）保留给弹窗使用。

### 4.2 P1：摘要输入增强——把工具调用喂进摘要器

新增序列化函数（放 `ai` 模块 `Message.kt`，不影响现有调用点）：

```kotlin
/** 供压缩摘要用的富序列化：文本 + 工具调用 + 附件占位 */
fun UIMessage.serializeForSummary(
    toolInputLimit: Int = 500,
    toolOutputLimit: Int = 1200,
): String
```

- `Tool` part → `[工具调用 <toolName>] 入参: <input 截断> → 结果: <output 首段文本截断>`；失败/取消的调用标注状态。
- `Image`/`Document`/`Video`/`Audio` → `[图片]` / `[文档: <name>]` 占位（摘要器无需内容，但需知道存在过）。
- `Reasoning` 跳过（噪声）。
- `compressConversation`（`ChatService.kt:2632`）把 `summaryAsText(maxLength = 2000)` 换成 `serializeForSummary()` 并按角色差异化截断（USER 宽、ASSISTANT 中、含工具的更宽）。
- 标题/建议生成处的 `summaryAsText(500)`（`ChatService.kt:2155,2454,2558`）**保持原样不动**。

### 4.3 P1（可选）：旧工具结果修剪层（microcompact）

- **形态**：新增 `InputMessageTransformer`（或 `generateInternal` 装配处，`GenerationHandler.kt:663` `limitContext` 旁）：保护最近 N 个工具调用（建议 8 个），更旧的 `Tool.output` 替换为 `[旧工具结果已清理: <toolName>，可重新调用获取]`——保留 part 结构与 `toolCallId`，API 合法性不破坏。
- **顺序**：放在 `limitContext` 截断之后、transforms 之前；对已压缩会话同样生效（`effectiveMessages` 产物同样适用）。
- **开关**：全局 `settings` 加 `microCompactEnabled`（默认关，agent/工作区模式用户手动开），避免普通聊天场景不必要的缓存破坏。
- **缓存权衡**：替换中段内容会使该点之后的前缀缓存失效——Claude Code 接受同样代价；换来的是免 LLM 调用 + 避免触发整段摘要。v1 只做整轮替换一次（随下一请求生效），不做逐请求渐进。

### 4.4 P1（可选）：超长反应式压缩

- 在 `sendMessage`/`handleMessageComplete` 的失败路径识别「上下文超限」类错误（保守白名单匹配：`context_length`、`maximum context`、`prompt_too_long`、`too many tokens` 等），命中则自动跑一次 `compressConversation` 后重试一次生成。
- 熔断：同一会话连续 3 次失败暂停自动重试，转普通报错（对齐 Claude Code）。

### 4.5 默认提示词升级（随 4.2 一起做）

现有 `DEFAULT_COMPRESS_PROMPT` 结构保留（Codex 4 要点 + 累积历史段是对的），增补：

1. **结构化段落输出**（吸收 Claude Code 9 段式，收敛为 6 段）：`用户意图与目标 / 关键决策与原因 / 涉及文件·数据·来源 / 错误与已尝试的修复 / 未完成事项 / 下一步建议`。摘要卡与下一轮模型都更易扫读。
2. **防语境漂移**：「关键结论直接引用对话原文短语，不要全部改写」。
3. **工具交互要点**：「记录读/改过哪些文件与关键工具结果（配合 4.2 的输入增强）」。
4. `{target_tokens}/{locale}/{additional_context}/{content}` 占位符与用户自定义机制（`settings.compressPrompt`）不变；改默认值不影响已自定义的用户。

### 4.6 P2：UX 收尾

- `CompressContextDialog` 初始值改为 `keep = 10`、`target = limit/2`（修掉 0 值静默关闭问题）；若后续要自适应聊天/agent 两种形态，可演进为按 token 预算保留（从尾部累计到 `contextTokenLimit` 的 10–15% 封顶，条数是它的退化近似）。
- `CompressedHistoryCard` 增加元信息行：压缩时间、覆盖范围（N 条消息 → 摘要）、节省 tokens 估算（`ContextComposition` 快照的 messageTokens 前后差即可）。
- `/compact` 完成后发 `AppEvent` toast（可选）；顶部占用圈在下轮请求时自然按快照刷新，无需额外处理。

## 五、实施步骤

**Phase 1（P0，建议先做）**
1. `ChatService.sendMessage` 加 `/compact` 分支（不落库、join 语义复用、processingStatus 反馈、默认参数）。
2. `ChatServiceTest` 增补用例：`/compact` 不落库、附加指令透传、群组会话跳过、生成中排队后执行、压缩模型缺失报错。
3. `./gradlew :app:compileDebugKotlin` + `./gradlew :app:testDebugUnitTest --tests "*ChatService*"`。

**Phase 2（P1 摘要质量）**
1. `ai` 模块 `serializeForSummary` + `MessageTest` 用例（Tool 入参/出参截断、附件占位、Reasoning 跳过、空输出）。
2. `compressConversation` 替换调用点；`DEFAULT_COMPRESS_PROMPT` 按 4.5 增补。
3. 跑 `:app` 单测确认 `CompressedHistoryTest`、`ChatServiceTest` 不回归。

**Phase 3（可选增强，可拆独立任务）**
1. microcompact transformer + settings 开关 + 单测（保护窗口、占位替换、与 limitContext 叠加）。
2. 超长反应式压缩 + 熔断。
3. 弹窗默认值修正 + CompressedHistoryCard 元信息。

## 六、风险与注意事项

- **压缩请求本身不带 system/工具**：当前实现是单条 user prompt 直出摘要，块内 256 条 × 截断 2000 字符是上限护栏；分块并行已缓解长会话问题，4.2 之后单块内容变大，需观察块大小（可把 `maxMessagesPerChunk` 降到 128）。
- **`isSynthetic` 语义链**：摘要消息的合成标记由 `effectiveMessages()` 按结构还原（`Conversation.kt:80-86`），`displayMessagesForChunk` 依赖它过滤——`serializeForSummary` 等改动不得绕过该路径直接改 messages 列表。
- **`lastOriginalMessageId` 失配**：编辑/重生成/删除路径已全部置 `compressedHistory = null`，新增任何截断历史的操作必须同样重置，否则 `effectiveMessages` 会退化为「只有摘要没有尾部」。
- **缓存失效**：压缩后头部消息 id 全变，请求级前缀缓存必然 miss 一次——与 Codex 同级代价，属于压缩的固有成本；4.3 的权衡已注明。
- **未提交改动冲突**：`ChatService.kt`、`ChatPage.kt` 等当前有 in-flight 修改，动手前先 `git status` 对照，避免混入无关回滚。
- **Windows 测试怪癖**：全量 `test` 会挂在 workspace 模块，按目标模块任务/`--tests` 过滤验证。
