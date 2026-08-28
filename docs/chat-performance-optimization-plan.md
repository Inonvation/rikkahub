# 聊天界面流畅性优化计划

> 状态：已评审（2026-08-28）并实施完成 P1-A / P1-B / P2-A 保守档 / P2-B 保守档 / P3-A isActive 修复；
> 真机回归清单（§6）待跑。P2-B 激进档、P3-A debounce、P3-B 扩容仍挂起待拍板。
> 适用范围：`app` 模块聊天链路（ChatService / ChatPage / ChatList / ChatMessage / Markdown 渲染）
> 行号以 2026-08-28 工作区为准；后续若文件有改动，请按「代码标识」检索定位，勿死守行号。
>
> 评审结论摘要：
> - **P1-B 前提已过时**：`computeTokenStats` 已被重写为读 `ContextCompositionStore` 快照（O(1) 查表，
>   快照在每次生成请求发出前更新，`GenerationHandler.kt` 约 717 行），流式期间双算只剩 HashMap 查表，
>   性能收益≈0。降级为可选的「口径单源」清理项，不作为性能项。
> - **P3-A 原处方有误**：「超长文本跳过预热」已由 `warmMarkdownCache` 自身实现（`Markdown.kt` 的
>   `MARKDOWN_PARSE_MAX_CHARS` 提前返回），从计划删除；真问题是预取循环体全是纯 CPU 操作、无挂起点，
>   `prefetchJob?.cancel()` 对已运行批次无效（协程取消是协作式的），修正为循环逐 node 检查 `isActive`。
>   60ms 冷却窗口若吞掉用户最终停住的窗口会永久跳过该次预取，若要限流应改用窗口流 debounce。
> - **P2-A 风险比原计划更小**：`verticalScroll(scrollState)` 只在 Preview 态（100dp 小窗）挂载，
>   Expanded 态无内部滚动、`maxValue` 为 0/过期值本就是 no-op，爆炸半径只有预览窗。
> - **P3-B 缓存污染担忧排除**：流式每 chunk 解析走裸 `parseMarkdown` 不写缓存，只有首组合与预取写入；
>   扩容前应先验证淘汰确实发生（加命中率/淘汰计数），否则改了等于没改，暂缓。

## 1. 背景与目标

针对长对话、工具调用密集、流式输出等场景下的卡顿/掉帧/跳字问题，本次调查结论（详见
`docs/` 下上一轮调查）指向：**问题集中在「每 chunk 的主线程重复遍历」与「流式期间动画/解析的
重复劳动」**，而非结构性渲染错误。消息列表基础设施（节点引用复用、版本短路、稳定闭包、
滚动预取、异步 markdown 解析、自动跟随闩锁）已经过系统性治理，**不属于本次改动范围，且是
防回归红线**（见 §3）。

目标：在不改变任何用户可见行为的前提下，消除可证明的每 chunk 冗余计算；对涉及观感的项
（推理自动滚动、markdown 上屏节奏）提供保守档与可选档，实施前需拍板。

## 2. 调查结论摘要（已核实）

| # | 结论 | 状态 |
|---|---|---|
| 1 | 流式每 chunk 主路径已 `checkFiles=false`（`ChatService.kt:2043`），文件全表扫描只发生在生成结束收尾（每回合一次），**非问题** | 已修复，勿动 |
| 2 | `displayMessagesForChunk`（`ChatService.kt:224-236`）每 chunk O(N²) id 扫描 + 列表分配 | **待优化** |
| 3 | `computeTokenStats` 每 chunk 在 `ChatPageContent` 与 `TopBar` 各跑一次——**前提过时**：现已读 `ContextCompositionStore` 快照（O(1)），仅剩口径单源清理价值 | 降级可选 |
| 4 | 推理卡内部每 chunk 重启 `animateScrollTo`（`ChatMessageReasoning.kt:126-132`） | **待优化** |
| 5 | Markdown 流式管线每「解析完成」即整树重排版（`Markdown.kt:327-336`），chunk 快于解析时连续全量排版 | **可选优化** |
| 6 | 滚动预取任务在快速 fling 时重叠（`ChatList.kt:533-588`），CPU/GC 尖峰 | **可选优化** |
| 7 | `key(block.index)` 的 `index` 是原始 parts 下标（append-only 天然稳定），**无 key 漂移 remount 问题** | 排除 |
| 8 | 可见消息项在 chunk 间可被 Compose 跳过（依赖 §3 红线 5 的引用复用） | 正常路径 |

## 3. 防回归红线（本次及后续改动一律禁止触碰）

以下位置是历史 bug 修复的守卫，**任何优化不得修改其语义**；若改动点与其相邻，必须
逐条复核区间：

1. **自动跟随状态机**（`ChatList.kt:408-523`）：`userScrolledUp` 的置位/复位条件、
   `USER_SCROLL_COOLDOWN_MS`(400ms)、触点守护（`isUserInteracting` down 即置位）、
   `scrollToItem` 挂起语义（可取消，不用 requestScrollToItem）、`scrollingByProgram`
   标志——这是「AI 生成完后下滑查看上方消息被拽回/回弹抽搐」的修复核心。
2. **页面闩锁**（`ChatPage.kt:563-593` 的 `userScrolledLatch`/`lastUserScrollAt`、
   `ChatPage.kt:816-844` 的 `LocalScrollChatToBottom` 消费即复位语义、350ms 冷却窗、
   触点/手势/程序滚动三分判定）。
3. **过程区自动折叠守卫**（`ChatMessage.kt:468-512`）：`prevChainLoading` 只认「本组合内
   loading true→false」、350ms 延迟窗口、`finalOutputTopY ≤ viewportTop` 视口外判定、
   收尾「无动画瞬时折叠 + `chainCollapseAnimated` 复位」——折叠绝不发生在可见区。
4. **推理卡展开/折叠守卫**（`ChatMessageReasoning.kt:125-152`）：`prevReasoningLoading`、
   `atBottom()` 判定、`withFrameNanos {}` 让位；流式 Preview 态语义。
5. **消息节点引用复用**（`Conversation.kt:110-113` + `updateCurrentMessages` 注释）：
   引用相同即复用原 node——移除会导致每 chunk 全部可见 item 重组（历史掉帧主因）。
6. **version 字段 equals 短路**（`ChatService.kt:2756-2758` 注释）：StateFlow 去重先比
   version，避免每 chunk 深比较消息树。
7. **流式每 chunk `checkFiles=false`**（`ChatService.kt:2043`）：文件清理语义由非流式路径
   （编辑/删除/重建）承担，流式只增不减。
8. **Markdown 解析管线结构**（`Markdown.kt:320/327-336`）：首帧同步走缓存 + 后台 mapLatest
   解析；**不得加 debounce**（会重现「冻结→停顿后整段蹦出」）、不得改回主线程同步解析。
9. **LazyColumn 槽位布局**（`ChatList.kt:644-785`）：`key = node.id`、`contentType` 分类、
   `PresetMessagesIntro`/`CompressedHistorySummary`/`ConversationSystemPrompt`/`ScrollBottomKey`
   占位 item、动作栏 `heightIn(min = 32.dp)` 占位（`ChatMessage.kt:232-235`）、加载指示器/
   建议条悬浮不占 item。
10. **`trimBlankLines`**（`StreamChunkHandler.kt:120-131`）：生成收尾的微跳动属既有取舍，不动。

## 4. 优化项明细

### P1-A　`displayMessagesForChunk` O(N²) → O(N)（必做，零风险）

- **位置**：`ChatService.kt:224-236`（`displayMessagesForChunk`），调用点 `ChatService.kt:2037`。
- **现状**：对 `chunk.messages`（每 chunk 全量列表，见 `GenerationHandler.kt:782` 与
  `StreamChunkHandler.kt:117-118`）逐条 `indexOfFirst` 定位 → O(N²) 次 id 比较 + 每次
  `toMutableList`/遍历的分配。N=500 时约 25 万次比较/每 chunk。
- **改动**：先用一次 O(N) 建 `id → index` HashMap（保留**第一个**匹配语义——
  `indexOfFirst` 取首个，`put` 需 `containsKey` 守卫），再对 chunk 消息 O(1) 定位替换；
  ASSISTANT 追加到末尾的语义不变。
- **目的**：长对话每 chunk 比较次数从 O(N²) 降到 O(N)，chunk 密集（60~120/秒）时显著
  释放主线程。
- **注意**：保持 `result` 顺序与「只替换、不移动」语义；不改 `updateCurrentMessages` 调用
  形态（红线 5）。
- **风险**：极低，纯算法等价替换。
- **验证**：`ChatServiceTest` 已有该函数用例（`app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`），
  跑 `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest"`。

### P1-B　`computeTokenStats` 双份调用去重（降级为可选清理，非性能项）

> **评审修正**：原前提（每 chunk 双份 `effectiveMessages()` 全量遍历）已不成立——
> `computeTokenStats` 现读 `ContextCompositionStore` 快照（`ChatPage.kt` 的
> `ContextCompositionStore.get(...)`，O(1) 查表），快照在生成请求发出前更新一次
> （`GenerationHandler.kt` 约 717 行），流式期间两处调用均无遍历。
> 剩余价值仅为「顶栏与浮窗口径单源」的一致性清理，随 P1-A 一并提交。

- **位置**：`ChatPage.kt:595-598`（`ChatPageContent` 已算 `tokenStats`）、
  `ChatPage.kt:748-774`（`TopBar` 调用处）、`ChatPage.kt:1426-1428`（`TopBar` 内部重复计算）。
- **改动**：`TopBar` 增加参数（如 `tokenStats: TokenStats`），删除 `TopBar` 内部
  `computeTokenStats` 调用；`ChatPageContent` 计算一次并传入。
- **注意**：`TopBar` 为 `ChatPage.kt` 私有函数，签名变化仅影响本文件一个调用点；数值口径
  不变（同一 conversation/settings 下结果恒等）。
- **风险**：极低。
- **验证**：编译 + 真机顶栏用量圆圈与压缩弹窗数值与改前一致。

### P2-A　推理卡内部自动滚动去动画 + 贴底守卫（推荐，体验微调需确认）

- **位置**：`ChatMessageReasoning.kt:126-132`（`rememberReasoningState` 内 loading 分支）。
- **现状**：每收到一个推理 chunk（`LaunchedEffect(reasoning.reasoning, loading)` 随文本
  变化重启）执行 `state.scrollState.animateScrollTo(state.scrollState.maxValue)` —— 每 chunk
  取消上一个动画再起新动画；推理文本长、chunk 密集时持续占帧，且用户流式中上翻推理
  历史会被动画拽回。
- **改动（保守档）**：
  1. 改用 `scrollTo(maxValue)`（无动画，直接贴底）；
  2. 增加「用户已上翻则不滚动」守卫：`state.scrollState.value >= maxValue - 阈值`（如 24dp，
     **需经 `LocalDensity` 转像素**，`maxValue` 单位是 px）时才滚动，否则跳过；
  3. 保留 `yield()` 让位与 loading 翻转分支（红线 4）不动。
- **评审补充**：`verticalScroll(scrollState)` 只在 Preview 态（`heightIn(max = 100.dp)` 小窗）挂载，
  Expanded 态无内部滚动、`maxValue` 为 0 或过期值，现 `animateScrollTo` 本就近似 no-op——
  本项爆炸半径只有 100dp 预览窗，碰不到外层 LazyColumn 红线。`maxValue` 相对新内容的一帧滞后
  是既有行为，下个 chunk 自愈，非新问题。
- **目的**：消除每 chunk 动画重启开销；修正「流式中上翻推理内容被自动拽回」。
- **注意**：这是本次唯一改变可见行为的项（自动滚动从平滑变为直接贴底；用户上翻后不再
  被拽回）。若想保留平滑观感，可选「节流档」：距上次滚动 <300ms 用 `scrollTo`，否则
  `animateScrollTo`——二选一，需真机确认。
- **风险**：中低。只改推理卡内部滚动，不触碰 LazyColumn 层（外层「生成完下拉抽搐」守卫
  全部在红线 1/2/3，不受影响）。
- **验证**：流式推理真机——文本增长平滑、不掉帧；流式中上翻推理内容不被拽回；
  生成结束折叠/展开行为与改前一致。

### P2-B　Markdown 流式上屏节奏（可选，保守档先行）

- **位置**：`Markdown.kt:327-336`（`MarkdownBlock` 内异步解析管线）。
- **现状**：后台 `mapLatest` 每次解析完成即 `setData` → 主线程整棵 AST 重新排版。
  长回答时解析耗时 > chunk 间隔，出现「连续全量排版」；观感为输出中后段一顿一顿。
- **改动（保守档）**：`collect` 前加 `conflate()`（只保留最新解析结果上屏）+ `setData` 前
  `withFrameNanos {}` 对齐帧边沿，让排版与合成同帧。
- **评审补充**：收益根源是 `flowOn(Dispatchers.Default)` 内置 64 槽缓冲 channel——主线程忙时
  解析结果排队、随后逐个 `setData` 造成「连续全量排版风暴」，`conflate()` 对症。放置位置：
  `catch` 之后、`collect` 之前。含 HTML 的内容走 `MarkdownNew(content = content)` 分支直接吃
  原始字符串，`setData` 只影响 MarkdownNode 分支与 `hasHtml` 翻转，收益范围以此为界。
  `withFrameNanos` 挂起期间被取消至多少一次 `setData`（≤1 帧），无害。
- **改动（激进档，需 A/B 拍板）**：渲染合并——后台循环解析「当前最新 content」，完成后若
  内容又前进了则**不 setData 直接解析最新版**（跳过中间版本渲染），把每 chunk 一次整树
  排版降为「追得上的频率」。
- **目的**：降低流式中后段主线程重复全量排版次数。
- **注意**：不得加 debounce（红线 8）；观感从「逐段蹦出」变为「略滞后但连续」，需真机
  对比后决定激进档是否实施。保守档无可见差异。
- **风险**：保守档极低；激进档中（观感变化）。
- **验证**：长回答（>2k 字）流式真机帧率；完成后内容与改前逐字一致。

### P3-A　滚动预取任务取消失效修复（可选）

> **评审修正**：原处方（60ms 冷却 + 超长文本跳过预热）作废——
> ① 「超长文本跳过预热」已由 `warmMarkdownCache` 的 `MARKDOWN_PARSE_MAX_CHARS` 提前返回实现，删除；
> ② 60ms 冷却若吞掉用户最终停住的窗口，该窗口预取被永久跳过（冷却不保证最后一个窗口被处理），
>    若要限流应改用窗口流 debounce（50-100ms），且需真机 A/B；
> ③ 真问题是：预取循环体（`ChatList.kt:557-586`）全是纯 CPU 操作、无挂起点、无 `isActive` 检查，
>    `prefetchJob?.cancel()` 对已在运行的批次无效（协程取消是协作式的），被取消的 job 仍会把
>    整批 ~28 条消息跑完，快速 fling 时多批次叠加才是 CPU/GC 尖峰主因。

- **位置**：`ChatList.kt:533-588`。
- **改动（本次实施）**：循环体每个 node 迭代前加 `if (!isActive) return@launch`，让已有的
  取消语义真正生效。一行改动，无行为变化（取消后预取本来就该停）。
- **改动（可选，需真机 A/B 拍板）**：窗口流 debounce 限流。
- **目的**：快速 fling 时不让过期批次的解析继续占 CPU/GC。
- **风险**：极低（isActive 检查）；debounce 低-中（影响首帧命中率）。

### P3-B　markdown 解析 LRU 扩容（可选，暂缓）

- **位置**：`Markdown.kt:248`（`markdownParseCache`，`4 * 1024`）。
- **改动**：`4 * 1024` → `8 * 1024`。
- **目的**：长对话大消息减少互挤淘汰，降低视口回滚时的同步解析（单次 3-26ms 主线程阻塞）。
- **风险**：极低（内存换命中）。
- **评审补充**：流式中间版本不污染缓存（每 chunk 解析走裸 `parseMarkdown` 不写缓存，
  只有首组合与预取写入），压力只来自去重后的消息文本；未达淘汰线时扩容无效果，达到后
  最坏驻留堆内存翻倍（AST 常为文本的 5-20 倍）。**先加命中率/淘汰计数验证淘汰确实发生，
  再决定翻倍——本次不实施。**

## 5. 实施顺序

| 顺序 | 项 | 前置条件 |
|---|---|---|
| 1 | P1-A | 无（有单测背书） |
| 2 | P1-B（可选清理） | P1-A 后编译一次 |
| 3 | P2-A | 保守档直接实施；节流档需拍板 |
| 4 | P2-B 保守档 | 无；激进档另行 A/B 后决定 |
| 5 | P3-A isActive 修复 | 无 |
| 6 | P3-A debounce / P3-B 扩容 | 真机确认 P1~P2 后，按需 |

每步落地后：`./gradlew :app:compileDebugKotlin` + 相关单测 + 真机回归清单（§6）。
不建议一次性合入，P1 系列可独立提交，P2/P3 各自独立提交便于回滚。

## 6. 回归验证清单（真机）

历史 bug 防回归场景（改任何一项后必须过一遍）：

1. **AI 生成完成瞬间立即下滑查看上方消息** → 不得被拽回底部、不得回弹抽搐（红线 1/2/3）。
2. **发送消息后立即上翻历史** → 不得被贴底拽回（350ms 冷却 + 触点守卫）。
3. **折叠「已处理」过程区** → 延迟贴底不得打断用户拖拽，贴底后位置正确。
4. **流式推理中上翻推理卡内容** → 不被自动滚动拽回（P2-A 后新行为，重点确认无副作用）。
5. **长对话（300+ 消息）高速生成** → 帧率与改前对比（P1-A/B 收益点）。
6. **工具密集回合（10+ 工具）** → 流式期间与生成结束后快速滚动无卡顿。
7. **快速 fling 浏览历史 200+ 条** → 首帧无长卡（预取生效），无 GC 停顿（P3 收益点）。
8. **顶栏用量圆圈 / 压缩弹窗数值** → 与改前一致（P1-B）。

## 7. 实施记录（2026-08-28）

- **P1-A** 已实施：`ChatService.kt` 的 `displayMessagesForChunk` 改为先建 `HashMap<Uuid, Int>`
  （`putIfAbsent` 保首个匹配）再 O(1) 定位替换；新增单测
  `display messages duplicate id updates first occurrence and keeps order` 钉住 indexOfFirst 等价语义。
  `ChatServiceTest` 全绿。
- **P1-B** 已实施（口径单源清理）：`TopBar` 增加 `tokenStats: TokenStats` 参数，删除内部
  `computeTokenStats` 调用，由 `ChatPageContent` 计算一次传入。
- **P2-A** 已实施（替代方案）：原「24dp 贴底阈值」单独使用会被大 chunk 一步增长误判为用户上翻、
  导致跟随永久脱落，改为「上次自动滚动基准」判定——自动滚动只往底部增大 value，
  **value 跌破 `lastAutoScrollValue` 基准 = 用户上翻**（暂停跟随不拽回）；用户停在中途
  （基准 < value < 底部）也尊重；用户回到底部时下个 chunk 接管恢复正常跟随。
  `animateScrollTo` → `scrollTo` 消除每 chunk 动画重启；`yield()` 与 loading 翻转分支（红线 4）未动。
- **P2-B 保守档** 已实施：`Markdown.kt` 管线 `flowOn` 后加 `conflate()`（消除 64 槽缓冲导致的
  连续 setData 排版风暴），`collect` 内 `withFrameNanos {}` 帧对齐后再 `setData`。
- **P3-A isActive 修复** 已实施：`ChatList.kt` 预取循环每个 node 迭代前 `if (!isActive) return@launch`，
  让 `prefetchJob?.cancel()` 对已运行批次真正生效（循环体无挂起点，取消本是协作式的、原样跑完整批）。
- 编译 `:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 全绿。
- 中途 `:app:compileDebugKotlin` 曾因 `ContextStatusPopover.kt` 的未完成浮窗改动报
  `tokensStr`/图标名 unresolved——该文件非本计划改动，由并行会话自行修复后编译通过。

## 8. 明确不做（防止范围蔓延）

- ChatPageContent 每 chunk 全页重组（`conversation` StateFlow 全量下发）：架构级改造，
  收益不确定、风险高，仅记录。
- ChatInput 每 chunk 重组（接收整个 `conversation` 参数）：同上，另行立项。
- 交错输出（文本后又推理）的渲染归属（`ChatMessage.kt:433` `finalOutputStart` 语义）：
  功能语义问题非性能问题，另行讨论。
- 群组讨论（`GroupDiscussionOrchestrator`）链路：独立场景，不在本计划。