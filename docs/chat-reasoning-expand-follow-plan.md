# 思考展开贴底平滑跟随——本地化适配方案

状态：已实施（核心修复见 f0858882，装机验证待用户执行）
日期：2026-09-02/03
关联：`caaf5eac`（上一版修复）、工作区未提交的版本4尝试（320ms 逐帧追底）

## 1. 目标行为

生成中用户钉在底部观看时，手动展开折叠的思考内容（或过程链 / 工具气泡等一切可折叠内容），视口应**逐帧跟随内容底边平滑下移**，与上游 rikkahub/rikkahub 行为一致：

- 不出现「先展开动画、再一次性蹦到底部」；
- 不出现「先往下展开、再跳下来」（滚动与高度动画节奏错位）;
- 不出现「贴底观看时展开后跟随停摆」；
- 读历史（不在底部）时展开，视口保持不动，不被拽回底部。

## 2. 上游机制还原

上游 `ChatList.kt` 的跟随核心非常简单：

```kotlin
snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
    if (!state.isScrollInProgress && loadingState) {
        if (visibleItemsInfo.isAtBottom()) {
            state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
        }
    }
}
```

平滑的来源：

1. `snapshotFlow` 对每次布局变化（高度动画的每一帧、每个流式 chunk）都发一次 emission；
2. `requestScrollToItem` 不走滚动手势互斥队列，在**下一次 measure 开始时**即时重锚到列表末尾——与该帧的高度增长在同一个 measure 里生效，无动画、无排队、无协程;
3. 「是否贴底」本身就是闩锁：用户上滑离开底部 → `isAtBottom()` 为 false → 跟随自然停止；用户滚回底部 → 恢复。没有独立的时间冷却窗、没有点按毒化。

关键容差：`isAtBottom()` 只需要覆盖**一帧**的增量。因为一旦第一帧增长后发出 request，后续每帧的「重锚 + 新增长」都在同一 measure 内完成，漂移不会累积。展开动画首帧增量小（animateContentSize 从静止起步），所以展开全程被逐帧重锚，视觉上内容下推、视口同步跟随。

## 3. 本地为什么做不到——根因

本地的跟随用**可取消的挂起 `scrollToItem`**（为修「排队中的滚动落入手势结束后把用户拽回」这类 bug，见 `ff875d922`），配套一套保护状态机：

| 机制 | 防的 bug |
| --- | --- |
| `userScrolledUp` 闩锁 | 读历史时被自动跟随拽回 |
| `USER_SCROLL_COOLDOWN_MS = 400` 冷却窗 | 内容突变恰好贴底造成误复位回弹（「工具调用后下拉回弹」） |
| 触点 down/up 即刷新 `lastUserScrollAt` | 「按下未消费滚动」「抬起后排队滚动才执行」两个窗口 |
| `isUserInteracting` 触点守护 | 手指按住时跟随抢滚 |
| `scrollingByProgram` | 冻结条程序滚动期间跟随抢滚 |

**根因**：点按思考头部本身是一次触点，down/up 都会刷新 `lastUserScrollAt` → 展开动画的 200ms 完全落在 400ms 冷却窗内 → 主跟随被挡 → 漂移无界累积 → 冷却结束后一发 `scrollToItem` 把整段展开高度一次性跳掉。这就是「先展开再蹦」。

caaf5eac（贴底时不武装闩锁）只解决了「跟随停摆」，没有解决「冷却挡住逐帧跟随」；展开的逐帧重锚从未真正发生。

### 历次失败模式归因

| 版本 | 做法 | 失败模式 | 归因 |
| --- | --- | --- | --- |
| 1 | 无条件武装闩锁 | 贴底观看展开后跟随停摆 | 把「钉底观看时的手动展开」误判为用户离开 |
| caaf5eac | 贴底时不武装 | 先展开动画再蹦到底部 | 点按冷却窗挡住主跟随，漂移在动画期内无界累积 |
| 3 | 独立 `animateScrollToItem` | 先往下展开再跳下来 | 滚动动画与高度动画节奏错位 |
| 4（工作区未提交） | toggle 回调里起 320ms 逐帧追底循环 | 复杂、魔法窗口 | 平行机制绕开主回路；320ms 写死动画时长；320–400ms 间仍有盲区；与 `scrollingByProgram` 抑制互相纠缠 |

## 4. 方案：内容切换时间窗解锁跟随

**核心洞察**：点按展开/折叠折叠内容的瞬间，是用户明确的「我要看下面内容 / 我在管理内容」意图信号——此时应解锁主跟随让它逐帧接管（上游同款机制）；而其它触点（点空白、点选文本后立刻上滑）保持现有冷却保护完全不变。修复门控本身，而不是另起一条滚动通路。

改动全部集中在 `ChatList.kt` 的跟随门控与 provider；`ChatMessageReasoning.kt` 回退到 caaf5eac 版本。不新增任何滚动调用点、不引入平行循环。

### 4.1 ChatList.kt

**a) 新增常量与状态**（与 `USER_SCROLL_COOLDOWN_MS`、`lastUserScrollAt` 同位）：

```kotlin
// 手动展开/折叠内容后的跟随解锁窗：窗口内点按冷却不再拦跟随，主跟随逐帧重锚贴底。
// 必须 >= USER_SCROLL_COOLDOWN_MS：点按本身会刷新触点冷却（up 刷新在下一帧），
// 窗口短于冷却会在「窗口到期、冷却未到」之间留下无跟随盲区，漂移重新累积成小硬跳。
private const val CONTENT_TOGGLE_FOLLOW_UNLOCK_MS = 600L
```

```kotlin
var lastContentToggleAt by remember { mutableLongStateOf(0L) }
```

函数级 `remember`（跟随 effect 会因 loadingState 变化重启，与 `lastUserScrollAt` 同理）。时间戳跨会话切换残留无害（窗口早已过期）。

**b) 跟随门控加一个 OR 项**（`requestNow` 处）：

```kotlin
val toggleUnlocked =
    SystemClock.elapsedRealtime() - lastContentToggleAt < CONTENT_TOGGLE_FOLLOW_UNLOCK_MS
val requestNow = !inProgress && !liveScrolling && !folding && shouldFollow &&
    (followCooledDown || toggleUnlocked) && (isUserInteracting?.value != true)
```

**为什么是 OR 时间窗、而不是直接把 `lastUserScrollAt` 改小**：触点抬起时 `LaunchedEffect(isUserInteracting?.value)` 会在下一帧把 `lastUserScrollAt` 刷新回当前时刻（顺序竞态，写在 click 回调之后），直接改时间戳会被覆盖回去。OR 窗口对任何刷新顺序都稳健，且自过期，不改写任何现有状态。

**c) provider 贴底分支改为「清闩锁 + 记时间戳」**（替换版本4的追底循环）：

```kotlin
LocalOnManualContentToggle provides {
    if (loadingState) {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val atBottom = last != null && isChatListPinnedToBottom(
            totalItemsCount = info.totalItemsCount,
            lastVisibleIndex = last.index,
            lastItemEnd = last.offset + last.size,
            viewportEnd = info.viewportEndOffset,
            afterContentPadding = info.afterContentPadding,
        )
        if (atBottom && !state.isScrollInProgress && (isUserInteracting?.value != true)) {
            // 钉底观看时手动展开/折叠：这是明确的跟随意图信号。
            // 清闩锁（防「回底后闩锁尚未稳定复位」边缘态卡住跟随）+ 开解锁窗，
            // 主跟随从展开首帧起逐帧重锚贴底（上游同款视觉）。
            userScrolledUp = false
            lastContentToggleAt = SystemClock.elapsedRealtime()
        } else {
            // 离底（读历史/上翻）展开：武装闩锁防高度突变被拽回（caaf5eac 语义，保留）
            userScrolledUp = true
            lastUserScrollAt = SystemClock.elapsedRealtime()
        }
    }
},
```

### 4.2 ChatMessageReasoning.kt

回退工作区未提交改动到 caaf5eac（`git checkout HEAD --` 该文件即可，工作区对该文件只有 toggle 一处改动）：

- 恢复 `wasAtBottom = !next && atBottom`（折叠兜底重新包含 loading）。版本4 把 loading 排除是因为它与追底窗口重叠会二次跳动；追底移除后无重叠。loading 中贴底折叠由 LazyColumn 末端 clamp 自动回锚（内容收缩 → 最大滚动位下降 → 当前位置越界 → 每帧 clamp 重贴），250ms 兜底 `scrollChatToBottom` 退化为空操作保险，无二次跳动。

### 4.3 逐帧跟随如何发生（改后时序）

1. T0：用户点按思考头部展开，click 回调 → 贴底分支 → 清闩锁 + `lastContentToggleAt = T0`；
2. T0 同帧：展开动画首帧增长（增量小）→ 布局变化 → 跟随 emission → 门控：`loading ✓`、`!userScrolledUp ✓`、`!pinned ✓`（漂移超出 8px 容差）、`followCooledDown ∨ toggleUnlocked ✓`、`!isUserInteracting ✓`（手指已抬起）→ 发起挂起 `scrollToItem`；
3. 之后每帧：增长 → emission → 重锚（collectLatest 取消上一发在途滚动），与上游「每帧 request + measure 内生效」同构，仅多一帧（≈16ms）的视觉滞后，流式 chunk 跟随今天就是这个特征，实际不可感；
4. T0+400：点按冷却自然到期，与窗口（T0+600）无缝衔接，此后由常规冷却保护接管；
5. 展开动画结束、漂移归零 → `pinned = true` → 跟随自然静默。

## 5. 旧 bug 回归核对表

| # | 历史 bug | 改后的保护机制 | 是否受影响 |
| --- | --- | --- | --- |
| 1 | 读历史展开被拽回（「展开后突然跳到底部」） | provider 离底分支武装闩锁（caaf5eac 原样保留）+ `userScrolledUp` 闩锁 | 不变 |
| 2 | 先展开再蹦底（本次修复目标） | 时间窗让主跟随从首帧起逐帧重锚，漂移无法累积 | **修复** |
| 3 | 展开/滚动节奏错位 | 不再有独立滚动动画，主跟随逐帧即时重锚 | **修复** |
| 4 | 贴底观看展开后跟随停摆 | 贴底分支只解锁、不武装 | **修复** |
| 5 | 点按后立刻上滑被拽回（ff875d922 类） | 非切换触点的冷却行为一字未改；切换触点后用户上滑，首帧拖拽 emission 即武装闩锁 + collectLatest 取消在途滚动，手势接管优先 | 不变 |
| 6 | 内容突变恰好贴底误复位（「工具调用后下拉回弹」） | path 2 的 `settledAfterInteraction` 冷却判定未动 | 不变 |
| 7 | 生成结束自动折叠被「刚跟随过」误杀 | `scrollingByProgram` 机制未动 | 不变 |
| 8 | 到阈值突然吸到底（重新武装第一发硬跳） | 本方案不涉及；属工作区 `ReengageGlideState` 未提交改动，独立验证 | 独立处理 |

## 6. 已考虑并否决的替代方案

- **照搬上游 `requestScrollToItem`**：它不走手势互斥、会在用户拖拽中强行改锚。本地历史上正因此换成了挂起 `scrollToItem` + 冷却（「闪到底又弹回」根因）。否决。
- **保留版本4的 320ms 追底循环**：平行机制 + 魔法窗口；>320ms 的动画（慢设备/长内容）窗口外仍有漂移，320–400ms 与冷却间有盲区，且与 `scrollingByProgram` 抑制互相纠缠。否决。
- **删除触点 down/up 刷新（不区分点按/滑动）**：重开 ff875d922 修过的窗口，影响所有触点路径，回归面太大。否决。

## 7. 工作区未提交内容处置

当前工作区混有三类改动，建议按 hunk 隔离提交（沿用 `git apply --cached` 分 hunk 的既有流程）：

1. **本方案核心修复**（ChatList.kt 门控 + provider；ChatMessageReasoning.kt 回退）——先行提交，独立验证；
2. **新消息入场动画**（ChatList.kt 入场 alpha/translationY + `ChatMessage(modifier=…)`）——与本任务无关，已验证可编译（`ChatMessage` 本有 `modifier` 参数）；作为独立 feature 提交，归属实现方自行验证；
3. **重新武装平滑滑贴**（`ReengageGlideState`）——修的是另一类 bug（回底第一发硬跳），与本方案无交集（展开跟随时 `pending` 恒为 false，走即时分支）；独立提交、独立验证。

## 8. 验证清单

编译：`./gradlew :app:compileDebugKotlin`（当前工作区已验证 exit 0，改动后复验）。

装机场景矩阵（用户自验）：

1. **核心**：生成中钉底 + 展开思考 → 内容平滑下推、视口逐帧跟随，无先展开再蹦；
2. 生成中钉底 + 折叠思考 → 视口随内容上收，无空洞、无跳动；
3. 读历史（离底）展开思考 → 视口不动、不被拽回；
4. 展开后思考/正文继续流式 → 持续贴底（窗口→冷却无缝衔接）；
5. 冻结条开合 → 先滚到吸顶线再切换，行为与现在一致；
6. 生成中点按消息后立刻上滑 → 不被拽回（ff875d922 回归位）；
7. 工具气泡 / 过程链展开 → 同样逐帧跟随（同一 provider 入口）；
8. 生成结束自动折叠 → 照旧。
