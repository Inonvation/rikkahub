# 过程区自动折叠时序重构方案（codex 式"正文开始即收卡"）

状态：**最终版（已完成代码结构审核与会话切换专项推演，待实施）**
范围：`ChatMessage.kt` 过程区折叠状态机；不动思考卡（`ChatMessageReasoning.kt`）、冻结条、滚动跟随。

## 1. 背景

「AI 思考完成后自动折叠所有步骤」(`autoCollapseAllSteps`) 当前实现为**消息完成定稿时折叠**：
正文全部输出完（loading 翻 false）→ 延迟 350ms → 按 A/B/C 三分类落地（用户控制中暂缓 /
过程区滚出视口无动画折叠 / 贴底可见折叠），配套 `autoCollapseHold` 临时固化、
`DisposableEffect` 离场收卡、snapshotFlow 补折叠。该状态机自 7c06ac91 引入以来，
围绕"完成瞬间折叠与用户操作碰撞"反复打补丁（ec2885a2 / 9306109a / 1b00bd78 /
5347d026 / d8d6f529 / b5512fad），仍是 bug 高发区。

问题本质：**折叠时机（消息完成）与用户操作高峰（生成刚结束、注意力还在屏幕上）重叠**。

## 2. 上游对照结论（upstream/master = 12ee935e）

| 项 | 上游 | 本地 |
|---|---|---|
| 自动折叠思考 | `autoCloseThinking`，思考卡完成即**无条件**折叠（无贴底/用户控制守卫） | 同名开关，已对齐（2026-09-09 修复） |
| 过程区整体折叠 | **不存在**（全树无 `autoCollapseAllSteps`） | 本地自创整套状态机 |
| 工具气泡 | `expanded = remember { true }`，手动开合，无自动收起、无记忆 | step 级完成自动收起 + 进程级 store 记忆 |
| 折叠/展开动画 | `animateContentSize()`（motionScheme spring） | 全部改 `tween(200)`（防 spring 长收敛期锚点漂移） |

结论：**"折叠所有步骤"没有上游行为可抄**，上游参照物只有交互动效节奏——
"思考结束即收、不等待、不守卫"。本地 `tween(200)` 替换 spring 是为修复
"折叠期间 LazyColumn 锚点持续漂移"的真实回归，**保留不回退**。

设置文案 `..._auto_collapse_all_steps_desc` = "before the final answer is shown"，
当前"完成后才折"实现本身就偏离文案；本次重构是拨回文案语义。

## 3. 新时序设计

物理边界：流式数据同帧到达，无法"先折完再输出"。可达成的最优时序是
**正文首 chunk 到达当帧触发收卡，200ms 收起动画与正文开头并行**——观感即 codex
（reasoning 收起 + 答案开始逐字出现）。

### 3.1 派生信号（`MessagePartsBlock` 内，`groupedParts` 已有）

```kotlin
// 正文开始：最终输出区出现首个块（finalBlocks 非空）
val finalOutputStarted = finalBlocks.isNotEmpty()
// 过程区仍有进行中的步骤（Reasoning 未 finish / Tool 未执行完 / ServerTool 未结束）
val processActive = parts.any {
    (it is UIMessagePart.Reasoning && it.finishedAt == null) ||
    (it is UIMessagePart.Tool && !it.isExecuted) ||
    (it is UIMessagePart.ServerTool && !it.isFinished)
}
```

### 3.2 折叠触发（替代完成定稿 A/B/C）

```kotlin
// 用户手动点击过折叠卡：本生成周期内自动折叠让位（手动优先）。
// 替代被删除的 autoCollapseHold 的"手动接管"语义，loading 重启（重新生成）时复位。
var manualOverride by remember(nodeId) { mutableStateOf(false) }

// 触发条件聚合（示意；落地时可直接用局部 data class 或四个独立 snapshotFlow 合流）
data class CollapseGate(val started: Boolean, val active: Boolean, val collapsed: Boolean, val manual: Boolean)

LaunchedEffect(autoCollapseAll, loading, hasProcessContent) {
    if (autoCollapseAll && loading && hasProcessContent) {
        snapshotFlow {
            CollapseGate(finalOutputStarted, processActive, chainCollapsed, manualOverride)
        }.collect { gate ->
            if (gate.started && !gate.active && !gate.collapsed && !gate.manual &&
                isUserControlled?.invoke() != true
            ) {
                chainCollapsed = true
                chainStateKey?.let { setSectionExpanded(it, false) }
            }
        }
    }
}
```

- `collect`（而非 `first`）保证**循环收敛**：折叠后若模型又发起工具/思考
  （工具循环中间输出→再调工具），`processActive` 翻 true → 唤醒展开（3.3）→
  步骤完成 → 再次满足 → 再折。无需一次性判定。
- 唯一守卫是 `userControlled`（触碰中/滚动中/翻历史闩锁）：用户正在控制列表时
  暂缓，条件消失后 snapshotFlow 自动重估，**天然自带重试**，无"漏折"路径。
- 工具审批可用性：正文开始 = 此前所有工具已结束，折叠不会隐藏待审批气泡；
  折叠后新出现的审批（唤醒展开路径）在展开态可见。

### 3.3 唤醒展开（折叠后新过程活动）

```kotlin
LaunchedEffect(autoCollapseAll, loading) {
    if (autoCollapseAll && loading) {
        snapshotFlow { processActive }.collect { active ->
            if (active && chainCollapsed) chainCollapsed = false  // 生成中临时展开，不写 store
        }
    }
}
```

生成中展开不落库——形态由后续触发/完成兜底定稿，语义与 init"生成中恒展开"一致。

**与手动折叠的取舍（有意为之）**：用户手动折叠后若新工具/思考到来，唤醒展开仍会
展开过程区（**审批可见性 > 手动折叠意图**，与现状"生成中 init 强制展开"同优先级）；
工具完成后触发条件重新满足，自动回归折叠。唤醒展开受 `manualOverride` 影响的
仅限折叠方向：手动接管后自动折叠不再执行，但展开不被抑制。

### 3.4 完成兜底（极简版，替代原 A/B/C 定稿）

正文开始时被 userControlled 暂缓、且用户一直控制到消息完成的极少数场景，
保留"补折叠"effect（现 579-604 行）原样兜底：完成时未折叠 → 等贴底/滚出视口/
用户放手后折叠。**删除** A 分支的 `autoCollapseHold` 固化与 `else` 固化展开：
等待期间保持展开但不写 store，消息若中途滚出回收/切页销毁，重建读 store
（无记录）按开关推导为折叠卡——与开关的目标形态一致，不再视为"意外塌缩"。

## 4. 删除 / 简化清单

| 现状机制 | 处置 |
|---|---|
| 完成定稿 A/B/C 三分类（520-572 行） | 删，替换为 3.2/3.4 |
| `autoCollapseHold` + `DisposableEffect` 离场收卡（485-497 行） | 删；"手动接管"语义由轻量 `manualOverride` 承接 |
| 定稿三分支统一落库注释块 | 随分支删除 |
| store 写入点：手动点击 / 定稿三分支 / 离场回收（3 类自动写） | 缩为手动点击 + 自动折叠落地（2 类） |
| 渲染层 `if (!loading && chainCollapseAnimated)`（952 行） | 去掉 `!loading`，生成中允许 200ms 折叠动画（codex 观感的前提） |
| 补折叠 effect（579-604 行） | 保留，条件中 `autoCollapseHold` 恒 false 化简 |

保留不动：思考卡 `autoCloseThinking`（已对齐上游）、工具气泡 step 级收起
（独立低风险）、`SectionExpandStore` 会话治理、冻结吸顶条、`tween(200)` 动画参数、
ChatList 跟随状态机。

## 5. 边界场景推演

**渲染分组事实（先行说明）**：`groupMessageParts` 中 `finalOutputStart = indexOfLast
{ ContentBlock }`——中间输出 text1 出现后、最终正文 text2 出现前，新工具/思考块被
渲染在**最终输出区**（text1 下方），直到 text2 出现才重新分组划回过程区。此为现状
既有行为，本方案沿用不改变。

- **纯 thinking → answer**（主流）：思考完成与正文首 chunk 几乎同帧 →
  `finalOutputStarted` 且 `!processActive` → 立即收卡 + 正文出现。✓
- **工具 → 中间输出 → 再工具/思考**（用户重点问询场景）：
  1. thinking 完成、text1 流式 → 收卡（过程区=思考链）；
  2. text1 后发起 tool2 → `processActive=true` → 唤醒展开；tool2 本渲染在
     text1 下方（最终输出区），审批气泡始终可见；
  3. tool2 完成、text2 开始 → 重分组（text1+tool2 划回过程区）→ 再次收卡，
     text1/tool2 一起收进"已处理"卡。中间输出的重排跳动是现状已有行为。
  4. 审批 pending / ask_user 未答：`isExecuted=false → processActive=true`，
     折叠被天然抑制，交互工具不会被折叠吞掉。✓
- **用户手动展开后正文继续流式**：`manualOverride` 置位，自动折叠不再执行，
  手动形态保持到本生成周期结束。✓（修正项 ①）
- **用户手动折叠后新工具到来**：唤醒展开（审批可见优先，取舍见 3.3），
  工具完成后回归自动折叠。✓（取舍项 ②）
- **重新生成**：loading 重启，`manualOverride` 复位，init 强制展开 →
  思考重新流式 → 正文开始再折。✓
- **生成中消息滚出视口回收重建**：init loading 强制展开 → 触发条件已满足
  → 再折。重建发生在视口外，展开-再折不可见。✓
- **生成中用户上翻历史**：userControlled 暂缓，正文照常输出；用户停手 →
  snapshotFlow 重估 → 补折（此时可能已在视口外，高度变化不可见）。✓
- **历史消息**：不经过触发 effect（loading=false），init 读 store / 开关推导，
  与现状一致。✓
- **关闭开关**：`autoCollapseAll=false` 全部 effect 短路，仅手动折叠。✓

## 6. 风险与验收

### 6.1 旧 bug 逐项归因（新时序下）

| 历史 bug | 根因 | 新时序下 |
|---|---|---|
| 完成瞬间折叠 × 起手下拉碰撞（回弹主根因，多轮补丁） | 折叠时机=消息完成，与用户操作高峰重叠 | **结构性消失**：折叠在正文流式增长期，完成瞬间无折叠动作 |
| 完成定稿固化展开残留 / 切回形态分裂（b5512fad/d8d6f529） | 完成瞬间只改内存不落库，重建两套语义分裂 | 折叠在 loading 中即落库，无"定稿瞬间"特殊态 |
| 用户控制列表时被折叠拽回（ec2885a2） | 可见区高度骤减 | `userControlled` 守卫保留 + snapshotFlow 自动重估自带重试 |
| 手动展开被自动覆盖（d8d6f529"手动优先"） | 自动写入覆盖手动形态 | `manualOverride` 承接（修正项 ①） |
| spring 收敛期锚点漂移 | 长 spring 动画 | `tween(200)` 保留不变 |

### 6.2 已识别的新风险与修正

1. **手动展开被 collect 折回**（删除 hold 后暴露）→ `manualOverride` 修正。
2. **唤醒展开 vs 手动折叠冲突** → 有意取舍（审批可见性优先），见 3.3。
3. **生成中放开折叠动画**（渲染层去 `!loading`）：正文 Column 的
   animateContentSize 在流式期本就活跃，叠加风险低，但属新行为，真机重点验证。

风险：过程区折叠是回弹敏感区，重构虽净删代码，仍需真机全场景回归。

验收清单（真机）：
1. thinking→answer 直出：正文出现瞬间收卡，无下拉跳动、无吸底；
2. 多工具循环：每轮正文/工具切换时折-展不闪烁卡死、审批气泡始终可见；
3. 生成中上翻历史：收卡暂缓，停手后补折无感；
4. 生成完立即下拉：无回弹（对比重构前）；
5. 切走切回 / 滚出视口回收重建：已完成消息收卡形态稳定；
6. 两开关叠加（自动折叠思考 + 折叠所有步骤）：层级正确（思考卡一行 → 整区收卡）；
7. 手动展开过程区后生成完成：手动形态保持，不被自动折叠覆盖。

## 7. 会话切换专项推演（折叠状态不被重置）

前提事实：折叠记忆存于进程级 `sectionExpanded`（key `process:<conv>:<nodeId>`），
ChatPage:654 治理 effect 按最近 N 会话回收；切换会话走导航清栈整页销毁，重建全靠
store / 开关推导。本方案**不改变 key 结构与治理入口**，store 写入口径见第 8 节。

| 场景 | 切走时形态 | 切回后行为 | 结论 |
|---|---|---|---|
| 已完成 + 曾自动折叠 | store false | init 读 false → 折叠卡 | ✓ 不重置 |
| 已完成 + 用户手动展开 | 点击时已写 store true | init 读 true → 展开 | ✓ 不重置 |
| 已完成 + 用户手动折叠 | store false | 折叠 | ✓ |
| 已完成 + 无任何记录 | — | init 开关推导（autoCollapseAll→折叠） | ✓ 与现状一致 |
| 生成中 + 正文开始已折叠 | store false（折叠落地即写） | init loading 强制展开 → 触发条件已满足 → 再折 | ✓ 收敛；重建在 AnimatedContent fade 内、过程区多在视口外，闪现不可感 |
| 生成中 + 折叠被 userControlled 暂缓 | 展开，store 无记录 | 已完成 → derived 折叠；仍在生成 → 强制展开→触发再折 | ✓ 两种落点均为开关目标形态，无"展开残留" |
| 生成中 + 用户手动展开 | store true（点击即写） | 已完成 → 展开（手动形态持久）；生成中 → 强制展开 | ✓ |
| 超过 N 会话被治理回收 | 记录删除 | 回落开关推导默认态 | ✓ 与现状同口径 |

对照旧 bug"切走切回折叠态重置"（SectionExpandStore 进程级单例修复的实例级队列
问题）与本方案无关；旧 bug"固化展开残留使切回又展开"随 hold 删除而**结构性消失**。

## 8. 最终修改方案（实施清单）

唯一改动文件：`ChatMessage.kt`（`MessagePartsBlock`）。其余文件一律不动：
`ChatMessageReasoning.kt`（autoCloseThinking 已对齐上游）、`ThinkingFreeze.kt`、
`SectionExpandStore.kt`、`ChainOfThought.kt`、`ChatMessageTools.kt`、`ChatList.kt`、
`ChatPage.kt`。

### 8.1 新增派生信号（groupedParts 派生处，~443 行旁）

```kotlin
val finalOutputStarted = finalBlocks.isNotEmpty()
val processActive = parts.any {
    (it is UIMessagePart.Reasoning && it.finishedAt == null) ||
    (it is UIMessagePart.Tool && !it.isExecuted) ||
    (it is UIMessagePart.ServerTool && !it.isFinished)
}
```

### 8.2 manualOverride 取代 autoCollapseHold（485-497 行）

- 删 `autoCollapseHold` 与其 `DisposableEffect`（离场收卡）；
- 新增 `var manualOverride by remember(nodeId) { mutableStateOf(false) }`；
- 新增复位 effect：`LaunchedEffect(loading) { if (loading) manualOverride = false }`
  （rising edge 复位 = 重新生成时让位自动折叠；完成时保留手动接管形态）。

### 8.3 触发 effect（新增，替代完成定稿为第一折叠时机）

见 3.2（CollapseGate snapshotFlow collect，条件含 `!manualOverride` 与
`userControlled` 暂缓）。折叠落地即 `setSectionExpanded(chainStateKey, false)`。

### 8.4 唤醒展开 effect（新增）

见 3.3。生成中临时展开不写 store。

### 8.5 完成定稿 effect 重写（520-572 行）

- 删 A 分支（userControlled → 固化展开 + hold）；
- 保留 B（过程区滚出视口 → 无动画瞬时折叠）/ C（贴底 → 带动画折叠）快速路径，
  条件加 `!manualOverride`；delay(350ms) 保留；
- else（可见且非贴底）：不再固化，留待补折叠 effect（579-604 行，条件中
  `autoCollapseHold` → `!manualOverride`，其余不动）。

### 8.6 渲染层（952 行）

`if (!loading && chainCollapseAnimated)` → `if (chainCollapseAnimated)`：
生成中折叠走 200ms tween，与正文首行并行（codex 观感前提）。

### 8.7 手动点击（896-907 行）

`autoCollapseHold = false` → `manualOverride = true`；store 写 `!willCollapse` 不变；
`onManualContentToggle` 通知不变；折叠卡 UI / collapseAtBottom 重贴底 effect 不变。

### 8.8 store 最终写入口径

| 写入点 | 值 |
|---|---|
| 手动点击折叠卡 | `!willCollapse`（现状不变） |
| 触发折叠落地（8.3） | false |
| 完成定稿 B/C（8.5） | false |
| 补折叠（8.5 else 路径） | false |
| 唤醒展开 / 生成中 init 强制展开 / manualOverride | **不写** |

写入点从现状 3 类自动写（定稿三分支 + 离场回收）缩为折叠单向写 false +
手动双向写，语义单向化，消除"展开残留"类分裂。

### 8.9 验证步骤

1. `:app:compileDebugKotlin`；
2. 真机按第 6 节验收清单 + 第 7 节会话切换矩阵逐项回归；
3. 通过后回填本文件"落地记录"。

## 9. 落地记录（2026-09-09）

按第 8 节清单实施完毕，全部改动集中于 `ChatMessage.kt`：

- 新增派生信号 `finalOutputStarted` / `processActive`（finalOutputStart 与 parts 派生）；
- `autoCollapseHold` + `DisposableEffect` 删除，替换为 `manualOverride`（remember(nodeId)）
  + loading rising-edge 复位 effect；
- 新增触发 effect（正文开始 + 过程区静止 + 非手动接管 + 非用户控制 → 收卡落库）与
  唤醒展开 effect（折叠后 processActive 翻 true → 展开，不写 store）；
- 完成定稿 effect 重写：删 A 分支固化，保留 B（视口外无动画）/ C（贴底带动画）+
  `!manualOverride`，else 留待补折叠；补折叠条件 `autoCollapseHold` → `!manualOverride`；
- 渲染层 `!loading &&` 条件移除，生成中折叠走 200ms tween；
- 手动点击 `autoCollapseHold = false` → `manualOverride = true`；
- 顶部 AUTO_COLLAPSE_DELAY_MS 文档注释重写为两时机模型；删除未用的 DisposableEffect import。

`:app:compileDebugKotlin` BUILD SUCCESSFUL（22s，无新 warning）。真机回归：待验收
（第 6 节清单 + 第 7 节会话切换矩阵）。
