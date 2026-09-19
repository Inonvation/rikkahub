# 聊天列表滚动交互重构——状态机收敛

状态：已实施（编译 + `:app:testDebugUnitTest` 全绿；装机验收清单见文末）
日期：2026-09-19
关联：`docs/chat-reasoning-expand-follow-plan.md`（展开跟随语义来源）、
`docs/chat-session-view-state-plan.md`（滚动存档/锚点恢复）、
`ChatListScroller.kt`（新状态中枢）、`ChatList.kt` / `ChatPage.kt`

## 1. 重构前的问题

滚动控制状态此前分裂在两个文件、三套独立收集器：

| 归属 | 状态/机制 | 说明 |
| --- | --- | --- |
| ChatList | `userScrolledUp` 跟随闩锁 | 上滑挂起、回底/新用户消息解除 |
| ChatList | `lastUserScrollAt`（400ms） | 任意触碰即刷新；跟随冷却与松手窗口 |
| ChatList | `lastContentToggleAt`（600ms） | 手动展开/折叠的跟随解锁窗 |
| ChatList | `ReengageGlideState` | 重新武装后的第一发动画滑贴 |
| ChatList | 触点追踪 `isUserInteracting` | ChatPage 持有 MutableState、ChatList 写入 |
| ChatList | 跟随 LaunchedEffect | snapshotFlow(layoutInfo+isScroll) 逐帧评估 |
| ChatPage | `userScrolledLatch` 接管闩锁 | 自动折叠暂缓用，与跟随闩锁语义相近但不等价 |
| ChatPage | 第二个 `lastUserScrollAt`（350ms） | 仅真实手势刷新；发送贴底/回底/用户控制判定 |
| ChatPage | 发送贴底 effect | pendingSendScroll + 尺寸触发 + 三重守卫 |
| ChatPage | 4 个 CompositionLocal 内联实现 | 各自重复计算 pinned / 守卫 |

结构性风险：两个收集器各自订阅同一布局流、`isChatListPinnedToBottom` 在 4 处重复计算、
两套闩锁可能演化出不一致（跟随已重武装而接管闩锁仍锁着自动折叠，或反之）、
6 个程序滚动入口各带一套手写守卫。

## 2. 目标结构

新文件 `ui/pages/chat/ChatListScroller.kt`：

- **`ChatListScroller`**：唯一持有"谁在控制列表"的状态——
  `userTouching`（触点）、`lastTouchAt`（跟随冷却，= 旧 ChatList lastUserScrollAt）、
  `lastUserGestureAt`（350ms 手势窗，= 旧 ChatPage lastUserScrollAt）、
  `lastContentToggleAt`、`followSuspended`（跟随闩锁，= userScrolledUp）、
  `userTookOver`（接管闩锁，= userScrolledLatch）、glide 状态、`sendScrollPending`。
  程序滚动入口收敛为方法：`consumeSendScroll()`（发送贴底）、`collapseRepin()`
  （折叠回底）、`scrollByProgram()`（吸顶条滚动）、`onManualContentToggle()`
  （内容展开/折叠）、`isPinned()`（唯一贴底判定）、`isUserControlled()`
  （唯一"用户控制中"判定）。
- **`ChatListScrollEffect`**：单一主循环（snapshotFlow(layoutInfo+isScrollInProgress)
  + collectLatest）同时驱动两个闩锁。接管判定（userTookOver）在
  `enableAutoScroll` 门控**之外**无条件运行（自动折叠抑制不受开关影响，与旧
  ChatPage 收集器一致）；跟随判定在其内（与旧 ChatList effect 一致）。
  旧跟随 effect 的空布局帧早退（`visibleItemsInfo.isEmpty()`）也保留在门控内。

不变式（行为等价性核对）：

| 历史 bug / 行为 | 保护机制（重构后位置） | 等价性 |
| --- | --- | --- |
| 读历史被自动跟随拽回 | 用户手势滚动即挂 followSuspended（不判方向） | 逐条等价 |
| 工具调用后下拉回弹 | 重武装 1 的触点冷却（400ms） | 等价 |
| 生成完下滑查看被拽回 | 触点守护（finger-down 即挡）+ 松手窗口 | 等价 |
| 打断跟随后滑回底部不恢复 | 重武装 2（用户驱动手势结束即贴底 → 立即恢复） | 等价 |
| 展开思考"先展开再蹦底" | toggle 解锁窗（600ms）+ 逐帧重锚 | 等价 |
| 离底展开被拽回 | onManualContentToggle 离底分支武装闩锁 | 等价 |
| 审批/ask_user 后视口后退 | toggle 不再判 loading（工作区既有修复） | 保留 |
| 到阈值突然吸底 | glidePending 第一发动画滑贴 | 等价 |
| 生成结束自动折叠被误杀 | 跟随/回底置 scrollingByProgram，接管判定排除程序滚动 | 等价 |
| 发送后翻历史被拽回 | consumeSendScroll 三重守卫（滚动中/触碰中/350ms） | 等价 |
| 折叠回底撞上用户起手 | collapseRepin 守卫 + 300ms 超时 | 等价 |
| 纯点击误伤"点折叠卡→回底" | lastUserGestureAt 仅真实手势刷新 | 等价 |
| 生成中离开→返回跳动 | 定位直接落当前末条（ChatPage 保留） | 不变 |
| 切会话位置漂移 | 锚点存档恢复（ChatScrollUtils，未触碰） | 不变 |

已知的有意微小差异（均朝更保守方向，无可见行为变化）：

1. `lastUserGestureAt` 的刷新时机从"Triple 去重后的变化帧"放宽到"触碰/手势期间的
   每个布局帧"——350ms 判定更保守（自动折叠暂缓稍宽、发送贴底更少抢滚），
   稳定贴底解除路径不受影响。
2. glide 状态从 ChatList 实例级改为页面级（`ChatListScroller` 由 ChatPage remember）：
   AnimatedContent 转场的 200-300ms 内新旧两个列表实例共享同一份，转场期间本就
   同内容同目标，无可感差异。

## 3. 明确不做的

- **不照搬上游 8 行 `requestScrollToItem` 跟随**：上游"isAtBottom 即闩锁"虽简洁，
  但 requestScrollToItem 不走手势互斥、会在拖拽中强改锚，且上游没有本 fork 的
  思考吸顶条程序滚动、内容切换解锁窗等场景。本地挂起 scrollToItem + 冷却的
  设计是历史 bug 换来的，保留。
- **不触碰**：滚动存档/锚点恢复（ChatScrollUtils + ChatScrollStore，有单测）、
  滚动预取、入场动画、消息组件的 CompositionLocal API（四个消息组件零改动）。
- GroupDiscussionPage 有自己独立的一套简化自动滚动，不在本次范围。

## 4. 验证

- `./gradlew :app:compileDebugKotlin` ✅
- `./gradlew :app:testDebugUnitTest`（全量，含 ChatScrollUtilsTest / ChatScrollStoreTest）✅

装机验收清单（对应第 2 节不变式逐条手测）：

1. 生成中贴底观看：流式逐帧跟随、无跳动；展开思考逐帧重锚无"先展开再蹦"。
2. 生成中上滑翻历史：不被拽回；手动滑回底部恢复跟随（第一发平滑滑贴）。
3. 生成结束：无任何滚动；自动折叠在贴底时执行、翻历史时暂缓。
4. 发送消息：贴底跟发；生成中发送引导同理；发送后立即翻历史不被拽回。
5. 切会话→切回：滚动位置还原（锚点）；折叠/展开态还原。
6. 折叠思考卡：视口回底抵消高度收缩；紧接起手拖拽则放弃回底。
7. 音量键滚动：解除跟随，回底后恢复。
8. 消息快速跳转（左右侧箭头）：跳转、回底按钮恢复跟随。
