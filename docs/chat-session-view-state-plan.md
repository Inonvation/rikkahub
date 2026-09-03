# 会话切换视图状态保持——问题梳理与修改方案

状态：已实施（代码落地，2026-09-03；待真机验收清单见文末「验收」）
日期：2026-09-03
关联：`ChatScrollStore`（ui/hooks）、`SectionExpandStore`（ui/components/message）、
`ChatMessageReasoning` / `ChatMessage` / `ChatMessageTools` / `ChatList` / `ChatPage`

## 0. 实施记录（2026-09-03 落地，与方案的差异）

按 Phase 1 → 3 全部落地，编译通过（`:app:compileDebugKotlin`）。与方案的差异如下：

- **Phase 1**：完成 effect 落库按方案执行（ChatMessageReasoning.kt 139-167：三分支定稿
  后统一 `setSectionExpanded(stateKey, expanded)`）。ChatMessage.kt（过程区）与
  ChatMessageTools.kt（工具气泡 isExecuted 自动折叠）经推演确认各自"保持展开不写
  store"的分支推导初值与保持态一致、无记录重建即还原，**无需改逻辑**，仅补注释固化
  语义（见文件内 468-512 / 346-354 注释）。
- **Phase 2**：锚点上报的落点比方案更进一步——原 ChatPage 侧 snapshotFlow 保存
  effect 整体移除，改由 ChatList 上报"视口首条真实消息 id + item index + offset"
  （ChatList.kt 675-701），ChatPage 只消费回调写 store。理由：item↔消息的换算
  （预设开场 intro 占一位）只有 ChatList 内部可知，ChatPage 侧裸 index 拿不到锚点
  消息 id。换算函数抽到 ChatScrollUtils.kt 共享（`matchPresetMessageCount` /
  `chatMessageItemIndex`），恢复/定位/上报同源，不再两处分叉。
- **Phase 3**：`ChatScrollStore.prune` 不挂在 SectionExpandStore（两个 store 文件各自
  管辖），改由 **ChatPage 治理 effect**（ChatPageContent 内 LaunchedEffect）在
  `trackRecentConversation` 之后借 `recentConversationIds()` 对齐回收；toolBubbleExpanded
  会话维度化（`tool:<conv>:<toolCallId>`，LocalConversationId 为 null 退化为裸 id）按方案执行。
- **单测**：本次未补（方案 Phase 2 备注为"若抽出"）。prune 为纯 retainAll 一行、
  换算函数极简且被 ChatPage/ChatList 双端编译期约束，暂缓；如需可后补。

各文件改动明细见上方正文的逐条描述。

## 1. 用户报告的两类问题

> 注：以下第 2/3 节为**改动前**机制盘点（历史基线）；第 4 节方案与第 0 节实施记录
> 描述改动后行为，正文行号以实施记录为准。

1. **普通切换即失真**：切走再切回同一会话，窗口（滚动）位置改变；思考内容 / 工具气泡等组件的折叠、展开状态被重置。
2. **开启自动折叠后加剧**：开启「自动折叠思考」或「AI 思考完成后自动折叠所有步骤」时，AI 生成完成后，用户再调整滚动位置 / 折叠状态，切走再切回——状态又变回去（回到的不是用户离开时所见）。

## 2. 现状机制盘点（先摸清事实）

### 2.1 导航模型决定了一切状态必须“可恢复重建”

切会话 = `navigateToChatPage` 压入新 Chat 页 → `cleanupChatPages()`（NavContext.kt:40）清掉旧 Chat 页 → 旧页组合树、ChatVM（`koinViewModel` 作用域）整体销毁；切回 = 全新组合 + 新 VM + 新会话（ChatService 新建/复用 session，必要时从 Room 重载）。因此：

- `remember` / `rememberSaveable` 全部不可依赖（注释中多次确认 Navigation 3 重建不可靠）；
- 任何“离开时所见”都必须落在**进程级单例 store**，重建时读回。

### 2.2 已存在的三个状态通道

| 通道 | 载体 | key | 写入时机 | 恢复语义 |
| --- | --- | --- | --- | --- |
| 滚动位置 | `ChatScrollStore`（进程级 map） | conversationId | 页面存活期间 snapshotFlow 持续写（index+offset，ChatPage.kt:376-387） | init 建 LazyListState 时 + 首帧布局后 scrollToItem |
| 展开折叠（section） | `SectionExpandStore.sectionExpanded`（进程级 map） | `前缀:会话id:条目`（process:/chain:/reasoning:/todo:） | **仅用户手动 toggle / 少数“滚出视口”自动折叠** | 读记录；无记录→按开关推导 |
| 工具气泡 | `toolBubbleExpanded`（进程级 map） | toolCallId（**无会话维度**，UUID 全局唯一） | 手动 toggle；工具 executed 翻转时的自动折叠（有守卫条件） | 读记录；无记录→按工具类型默认 |

生命周期治理：`trackRecentConversation`（SectionExpandStore.kt:70）进程级记录最近 8 个会话，`pruneSectionExpanded` 删其它会话全部 section 记录；toolBubbleExpanded 按插入序容量淘汰（600）；ChatScrollStore **无上限、无清理**。

### 2.3 各折叠组件读写的现状（关键）

- **思考卡片**（ChatMessageReasoning.kt）
  - init（`rememberReasoningState` 93-117）：读 `reasoning:$conv:${createdAt}`；有记录按记录；无记录 `finished && !autoCloseThinking → Expanded`，否则 `Collapsed`。
  - 手动 toggle（ControlledChainOfThoughtStep onExpandedChange 458-475 / 冻结条 335-401）→ 写 store。
  - **生成完成的自动折叠 effect（123-154）：只改内存态 `state.expandState`，全程不写 store**（注释明言“生成中自动 preview/autoClose 不经过此回调，不影响记忆”）。
- **过程区整卡（chain 折叠卡）**（ChatMessage.kt 468-512）
  - init 读 `process:$conv:$nodeId`（remembered 取反恢复）；无记录按 `autoCollapseAllSteps && !loading` 推导。
  - 手动点卡 → 写 store；完成瞬间“滚出视口才折叠”的自动折叠在满足条件下**写 store=false**（504 行），不满足（过程区可见）则保持展开、不写——推迟到“滚出视口后的重建按开关推导折叠”。
- **ChainOfThought 容器“显示 N 步”**（ChainOfThought.kt 90-92）：init 读 `chain:$conv:$nodeId`，默认 false；仅手动写。
- **工具气泡**（ChatMessageTools.kt 332-373）：手动 toggle 写；`autoCollapseAllSteps && 工具刚 executed && 贴底未受控` 时写 false；非贴底保持展开不写（默认展开的工具重建后推导一致，折叠类默认工具也一致）。

## 3. 根因分析

### R1（结构根因，主因）：“结束形态”与“恢复通道”语义分裂

生成完成瞬间系统自动折叠产生的**最终形态只落在组件内存**，未与 store（重建时唯一可信源）对齐：

- 无记录场景：重建走开关推导。推导默认值与“离开时所见”在特定分支不一致：
  - `autoCloseThinking=ON`、完成时**用户在看历史（非贴底）**：effect 保留当前态（往往是生成中 auto-preview 的 Preview/Expanded），重建却按开关推导成 **Collapsed** → 切回后思考内容消失。
  - `autoCollapseAllSteps=ON`、完成时过程区**可见**：折叠被推迟到“滚出视口后的重建按开关推导”，重建即折叠——**甚至不需要切会话，滚出视口再滚回就会塌缩**；切会话重建同理。
- 有记录但过期场景：生成中用户曾手动展开（store=true），完成后在底部被 autoClose 折叠（内存 Collapsed，store 仍 true）→ 切回重建读到 true → **又被展开**。

R1 的共性：**写 store 的时机是“用户手动操作/特定守卫通过”，而不是“形态真实落地的那一刻”**。7 千行注释守卫是给“动画不跳”服务的，不是给“状态记忆”服务的——两套目标混在一起，记忆保真成了补丁的副作用。

### R2（滚动位置）：恢复语义脆弱 + 与折叠状态强耦合

- 存档是 `firstVisibleItemIndex + offset`（裸索引）。会话离开期间若有消息追加/删除/压缩（后台生成、FGS 续答、远端同步），索引错位 → 恢复落点漂移。
- 恢复发生在重建后**折叠状态尚未一致**时（R1 使 item 高与离开时不同）→ 同 index+offset 落在不同像素。
- 现映射还隐含 preset intro 的 item 偏移问题（恢复按 LazyColumn 原始 item index 处理，preset 会话会偏一位，见 ChatPage.kt:363/434 与 ChatList.kt:612 偏移口径不一致）。
- ChatScrollStore 无生命周期治理（不随会话淘汰），长期多会话累积不回收。

### R3（治理副作用）：生命周期上限本身会“重置”状态

- 最近 8 会话之外，section 记录被 prune → 回默认态（用户切到第 9+ 个不同会话再切回早前会话即触发；注释称“可接受”，但正是“组件状态被重置”的另一种来源）。
- `toolBubbleExpanded` 无会话维度，无法随会话精准清理，只能插入序粗淘汰。

### R4（次要）：“tool 折叠卡”与“reasoning 折叠”之间无主从关系

- 折叠类重工具（workspace 文件类默认折叠）+ 过程区折叠 + 思考折叠各自独立推导，多个开关叠加时用户手动调整的对象与重建推导对象不同一，易出现“折叠了整体、单条又展开”等不一致观感。属 R1 的同源表现，不单独修。

## 4. 方案设计

### 设计原则

1. **状态唯一源 = store**；组件内存态只是 store 的影子（`loading` 中间态除外——生成中强制 Preview/展开不落库）。
2. **“形态真实落地”即写 store**（含系统自动折叠），而不是“用户手动才写”。写全量不玩“与推导一致就省写”的优化——记录生命周期由治理层兜底，先正确后省内存。
3. 滚动存档加**锚点消息**，弱化裸索引漂移；恢复以“锚点优先、索引兜底”。
4. 所有治理上限（含滚动 store）走同一套“最近 N 会话”口径，避免个别 store 溢出。

### 4.1 折叠状态：完成落地即落库（治 R1）

原则改为：“凡 `loading=false` 后形态定稿（系统折叠 or 手动 toggle），都写入 store；store 值 = 该条目最终展开态（true=展开）”。

改动点：

1. **ChatMessageReasoning.kt（核心）** —— 完成 effect（123-154 行）三个分支定稿后统一落库：
   - `autoClose && atBottom` → Collapsed，写 `false`；
   - `!autoClose` → Expanded，写 `true`（当前推导默认也 Expanded，但写成显式记录可清除“生成中曾手动展开”的过期残留，语义更稳）；
   - `autoClose && !atBottom`（保留态）→ 若保留态为 Preview/Expanded 写 `true`，防止重建按开关推导成 Collapsed（该分支是 R1 里“切回后思考消失”的直接根因）。
   - 放在 effect 内定稿分支末尾，注释写明“根因：完成形态与记忆通道分裂，重建只信 store/开关推导”。
   - Preview 是加载态枚举，store 只有布尔；保留态写 `true` 重建后落 Expanded（非 Preview），观感比直接塌成 Collapsed 好，作为已知近似。

2. **ChatMessage.kt 过程区**（468-512）：现状已基本正确（可见区自动折叠被推迟到重建按开关推导，语义自洽）。**唯一缺口**：`autoCollapseAllSteps=ON` 且完成时过程区可见（推迟折叠），用户此刻若**手动展开过/展开观看**后切走——store 已由手动写 true，重建 true，一致；若从未手动操作，store 无记录，重建按开关推导折叠 = 与“推迟折叠”的产品语义一致（保留）。**结论：无需改动**，但要加注释固化该语义（“推迟折叠 = 重建时按开关推导收敛”，防后人误加“立即落库”导致可见区瞬时塌缩回归）。

3. **ChatMessageTools.kt 工具气泡**（332-340）：transition 折叠已写 false。补“非贴底保持展开”分支显式写 true？——普通工具默认展开推导一致、折叠类默认工具默认折叠一致，**重建与离开所见均一致**，不写也不会失真的场景只有“折叠类默认工具被用户展开（写了 true）”，已覆盖。结论：现状自洽，仅在注释中说明“为何完成折叠只写贴底分支”（防误解）。

> 边界：上述写发生在 effect/回调内，全部落在主线程组合期后（LaunchedEffect/回调），无并发写问题；key 均含会话 id + 条目稳定 id（node.id / reasoning.createdAt），消息重建不换 key。

### 4.2 滚动位置：锚点消息化（治 R2）

1. `ChatScrollPosition` 扩展：

```kotlin
data class ChatScrollPosition(
    val anchorNodeId: Uuid?,   // 离开时视口首条消息的 MessageNode id（可空：视口内无消息）
    val index: Int,            // 原存档保留：LazyColumn item 索引，作为无锚点/锚点失效时的兜底
    val offset: Int,
)
```

2. **换算收敛到 ChatList**：LazyColumn item index ↔ messageNode 的换算（preset intro item、preset 消息 drop、底部 spacer）目前只存在于 ChatList.kt（612 行 `listItemOffset` 一带）。由 ChatList 向上报告“当前视口锚点消息”：新增可选参数 `onScrollAnchorChanged: ((anchorNodeId: Uuid?, index: Int, offset: Int) -> Unit)?`，在 ChatList 内用与 prefetch 相同的换算，订阅 `snapshotFlow { layoutInfo }` 变化（节流/去抖到帧粒度即可，与 ChatPage 现 effect 同语义）上报；ChatPage 的保存 effect 改为**消费该回调**（去掉对 raw index 的 snapshotFlow，避免两套口径打架）。

3. **ChatPage 恢复逻辑**（399-449）：优先按 `anchorNodeId` 在当前 `messageNodes` 中定位（`indexOfFirst { it.id == anchorNodeId }`），命中则 `scrollToItem(该节点 item index, offset)`；未命中（消息被删/压缩）→ 回落旧逻辑：saved index 钳制到合法区间（去掉 `in 0..lastIndex` 的口径混淆，index 统一按 ChatList 上报的原始 item index 语义保存与恢复）。
4. 发送贴底 / 自动跟随等程序滚动产生的中间位置仍照旧保存（语义不变：离开时在哪，回来仍在哪）。

### 4.3 生命周期治理对齐（治 R3）

1. `trackRecentConversation` 在 prune section 的同时，清理非保留会话的 `ChatScrollStore` 记录（新增 `ChatScrollStore.prune(keep: Set<Uuid>)`），滚动态与折叠态同生命周期。
2. `toolBubbleExpanded` 增加会话维度信息：记录类型改 `toolCallId → (conversationId, expanded)`（或 key 改 `tool:$conversationId:$toolCallId`，内部 API 同步改），使 `pruneSectionExpanded`/`trackRecentConversation` 能按会话清理；保持现有调用点语义不变。改动面集中在该文件 + SectionExpandStore 的两处治理函数。
   - 取舍：UUID 全局唯一使跨会话串状态概率极低，加会话维度的主要收益是“随会话精准回收 + 上限治理一致”，成本是 key 结构变更，属于可选加固项。若想控制改动面，可**降级为仅清理（不加会话维度）**：prune 时对非保留会话不可知，只能靠容量淘汰——维持现状。建议做，成本低。

### 4.4 已知取舍与不做的事

- **不做跨进程持久化**：store 语义与 ChatDraftStore 一致，进程被杀即失效（冷启动恢复底部/默认态）。若需“杀进程重开仍记住”，要落 DataStore/Room，本次不扩范围。
- **不做按会话无限记忆**：保留“最近 8 会话”上限；用户场景多为 2~3 个会话互切，8 已覆盖。若实测常驻会话数多，只调常量。
- **折叠动画行为（贴底跟随/防回弹）一律不动**：本方案只改“记忆一致性”，不触碰 `ChatList` 跟随状态机与 `onManualContentToggle` 等动画守卫。
- **Preview 与 Expanded 的差异**（见 4.1-1 已知近似）：store 布尔模型下重建不可能恢复“半透明滚动预览”，接受 Expanded 近似。
- **冻结条（吸顶）不记忆**：瞬态 UI，随页面重建自愈，不在“组件折叠状态”范围内。

## 5. 落地顺序与验证

### Phase 0：复现确认（改动前，真机）

按用户场景逐条复现并记录（哪些已中、哪些不中，用于裁剪改动面）：
1. 关全部自动折叠：会话 A 读历史 → 手动折叠某思考/工具 → 切 B → 切回 A → 状态是否还原；滚动位置是否还原。
2. 开「自动折叠思考」：生成中展开思考阅读 → 完成贴底自动折叠 → 切 B → 切回 A → 思考是否又展开（预期：R1 命中）。
3. 开「自动折叠所有步骤」：生成完过程区可见保持展开 → 上翻滚出再滚回 → 是否塌缩（预期命中）；再验证切走切回。
4. 后台继续生成（切走时 A 仍在生成）→ 回 A 的窗口位置。

### Phase 1：折叠保真（4.1，核心）

- 改 ChatMessageReasoning.kt 完成 effect；ChatMessage.kt / ChatMessageTools.kt 仅注释（若复现结果需要再动）。
- 验证：`./gradlew :app:compileDebugKotlin`；真机复跑 Phase 0 的 2/3（切回状态=离开所见；且确认无“完成瞬间可见区跳变”回归——写 store 不影响动画，因 store 只被 init 读取）。

### Phase 2：滚动锚点（4.2）

- 改 ChatScrollStore / ChatPage / ChatList（新增上报回调）。
- 验证：长会话上翻到中间 → 切走（期间开后台生成让 A 追加消息）→ 切回落点不漂移；无 preset 与带 preset 会话各验一次；消息删除后锚点失效回落兜底不崩。
- 补单测：ChatScrollStore 的 prune；锚点解析的纯函数（若抽出）。

### Phase 3：治理对齐（4.3）

- ChatScrollStore.prune 挂进 trackRecentConversation；toolBubbleExpanded 会话维度化。
- 验证：切 10+ 个会话后早前会话记录被清（section/scroll/tool 一致）；`testDebugUnitTest` 相关用例通过。

### 验收（最终真机清单）

- 关自动折叠：A↔B 反复互切，滚动位置逐像素级还原，所有折叠卡形态还原。
- 开两项自动折叠：生成完成后用户调整的一切（滚动+折叠）在切走切回后保持；滚出视口塌缩语义不再把“用户看着展开的过程区”误塌。
- 快速连切（1s 内多会话）无闪动/无状态串写。
