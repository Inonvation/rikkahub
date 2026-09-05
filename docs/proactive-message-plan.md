# AI 主动消息（Proactive Message）设计与实现计划

> 状态：待拍板（规划稿）
> 需求来源：个人定制需求 + 上游 rikkahub#826「定时任务功能」
> 关联：`docs/upstream-sync-log.md`（无直接关联，上游未实现）

## 1. 需求与目标

1. **AI 主动发消息**：每个助手可单独设定是否支持；软件处于后台时，AI 依据用户预设的指令/时机主动生成消息，以**系统通知**形式告知用户。
2. **进程被杀也能触发**：软件不在后台（进程已死、清后台、重启后）时，仍能按设定时间主动发消息。
3. **不重复造轮子**：参考已实现过同类功能的项目（调研见 §2）。

非目标：不做服务器推送（FCM 等）、不做跨设备定时同步、不做 AI 主动语音/电话。

## 2. 先行项目调研（借鉴来源）

| 项目 | 形态 | 借鉴点 |
| --- | --- | --- |
| [Ephemera1117/android-ai-push-tutorial](https://github.com/Ephemera1117/android-ai-push-tutorial) | **教程：作者正是在 RikkaHub 二改客户端（渊海）实现了本功能** | ★ 最核心参考。完整架构六步 + 全部线上踩坑（见 §7，本方案的可靠性规范直接采纳） |
| [LastChat](https://github.com/Cocolalilal/LastChat) | RikkaHub fork（同源代码结构） | 1.3.8 "Brought back and improved **Spontaneous Messages**"，1.4.1 增加 "spontaneous message modes"。实现可直接 clone 对照阅读 |
| [rikkahub#826](https://github.com/rikkahub/rikkahub/issues/826) | 上游 feature request | 需求形态确认：定时 + 预设 prompt → 后台自动发起 → 通知推送结果 |
| [astrbot_plugin_proactive_chat](https://github.com/DBJD-CR/astrbot_plugin_proactive_chat) | AstrBot 插件 | 触发器设计：**会话不活跃期 + 随机延迟**、上下文感知、人格一致、免打扰时段、独立配置面板 |
| Nomi.ai（商业） | 陪伴类 App | 主动消息**非严格定时**（更自然）、频率档位设置、**免打扰 22:00–8:00**、消息始终落会话（通知可选）、未回复则下次等待时间翻倍 |
| ChatGPT Tasks（商业） | 定时任务 | 自然语言创建定时任务 + 到点运行 prompt + 推送结果，交互范式参照 |
| [awesome-ai-companion](https://github.com/DasterProkio/awesome-ai-companion) | 项目清单 | dylan-heartbeat（周期心跳唤醒 + AI 决定是否开口）、jiwen/积温（情绪阈值触发）、revive-companion（Poisson 过程 + 贝叶斯用户状态推断决定"何时打扰"）——P2 思路来源 |
| orangechat（橘瓣） | RikkaHub fork | 同为带主动消息的 RikkaHub 二改，可对照 |

**关键结论**：Android 纯本地方案已有人完整走通（渊海），技术底座 = `AlarmManager.setExactAndAllowWhileIdle` + 前台服务 + BootReceiver + 台账补做；难点不在"跑起来"而在"每天都可靠地跑"，可靠性规范照搬其血泪清单即可避开 90% 的坑。

## 3. 总体设计

### 3.1 触发器模型（每个助手独立配置）

`Assistant` 新增 `proactive: ProactiveConfig?`（null = 未开启，向后兼容 DataStore JSON）：

```kotlin
@Serializable
data class ProactiveConfig(
    val triggers: List<ProactiveTrigger> = emptyList(),
    val quietHours: QuietHours? = QuietHours(startHour = 22, startMinute = 0, endHour = 8, endMinute = 0),
    val dailyLimit: Int = 3,            // 每日主动消息上限（成本护栏）
    val cooldownMinutes: Int = 120,     // 两次主动消息最小间隔
    val privacy: NotificationPrivacy = NotificationPrivacy.PREVIEW, // PREVIEW=显示前80字 / PLACEHOLDER=固定占位文案
    val placeholderText: String = "",   // PLACEHOLDER 模式的文案
)

@Serializable
sealed interface ProactiveTrigger {
    val id: Uuid
    val prompt: String?   // 可选：本次触发的自定义意图，如"提醒我看天气"，null=纯自主发挥
    val enabled: Boolean

    /** P0：每日时刻（支持 weekday 过滤）——"每天 08:00 早安" */
    data class Daily(override val id: Uuid, val hour: Int, val minute: Int, val weekdays: Set<Int> = (1..7).toSet(), ...)
    /** P1：固定间隔——"每 4 小时" */
    data class Interval(override val id: Uuid, val intervalHours: Int, ...)
    /** P1：会话静默期——"超过 6 小时没聊就主动开口" */
    data class Inactivity(override val id: Uuid, val thresholdHours: Int, ...)
    /** P2：AI 自主决策心跳——周期性低频唤醒，模型自行决定说不说、说什么 */
    data class Heartbeat(override val id: Uuid, val intervalHours: Int, ...)
}
```

| 触发器 | 调度机制 | 分期 |
| --- | --- | --- |
| Daily | AlarmManager 精确闹钟 | P0 |
| Interval | AlarmManager 精确闹钟 | P1 |
| Inactivity | 无闹钟：App ON_START + 每次生成完成时钩子检查（数据源为会话最后消息时间） | P1 |
| Heartbeat | 低频精确闹钟 + headless check-in 生成（输出 `{speak, text}` JSON，参照 MemoryConsolidation 的辅助调用范式） | P2 |

### 3.2 两级生命周期，单一执行路径

**所有触发统一走一条路径**（教程最大教训：多路径并存 = 重复消息的根源）：

```
AlarmManager.setExactAndAllowWhileIdle (RTC_WAKEUP)
    → ProactiveAlarmReceiver (onReceive 只做启动服务)
    → ProactiveGenerationService (前台服务 dataSync)
        1. startForeground（5 秒内）
        2. ★ 先续订下一次闹钟（一次性闹钟，不续订就永久失效；且必须先于生成）
        3. 防重检查（§7 三层）
        4. ProactiveExecutor：构造触发 prompt → ChatService.sendMessage
        5. 生成完成 → 回复落库 → 发通知 / 失败 → 发"生成失败: 原因"通知
        6. 台账销账 → stopSelf
```

- **进程是否存活无关紧要**：进程活着时闹钟广播照常送达（复用进程），死了则系统拉起进程投递（Application.onCreate 跑完后投递）。前台/后台/被杀三种状态行为一致，需求 1、2 一并满足。
- **进程死亡重启恢复**：`ProactiveBootReceiver`（`BOOT_COMPLETED`）覆盖式重排所有闹钟（不取消）。
- **兜底对账（P1）**：每日 WorkManager Worker 对账台账，发现"已排待兑现且已过时刻"→ 补做；覆盖 exact alarm 被 ROM 清掉的极端场景。WorkManager 不用于精确触发（会延迟 15min+），只做对账兜底。

### 3.3 消息语义

- **触发指令不落库**：ProactiveExecutor 向 ChatService 注入一条隐形 user 触发消息（含时间上下文 + 触发意图），生成完成后只落库 assistant 回复，并在消息 meta 标记 `source=proactive`（会话内显示小徽章，如「⏰ 主动消息」）。
- **触发 prompt 模板**（教程实践：与日常聊天 prompt 分开，必须注入时间上下文）：

  ```
  [触发注入 user 消息，不落库]
  [系统：主动消息触发。当前时间 {yyyy-MM-dd HH:mm 周X}；距上次互动 {N 小时}；
   触发类型：{每日定时 08:00 / 静默 6h / 自定义意图}。{自定义 prompt?}]
   请以助手身份主动给用户发一条消息，结合最近对话语境自然开口。
  ```

- **会话定位**（待拍板 §9.1）：默认发到该助手**最近一条会话**（`ConversationRepository.getRecentConversationsOfAssistant`，`ConversationRepository.kt:186` 已有现成查询）；若助手无任何会话则新建（`Conversation.assistantId`，`Conversation.kt:23`）。
- **工具策略**（待拍板 §9.4）：P0 主动消息生成**禁用全部工具**（尤其 ask_user——没有用户在场无法回答；workspace 写类工具——会绕过防重，教程实证过）。P1 评估只读白名单。

## 4. 架构落点（复用现有基建，改动集中 app 模块）

### 4.1 直接复用（无需新造）

| 能力 | 现成实现 | 位置 |
| --- | --- | --- |
| 无 UI 生成入口 | `ChatService.sendMessage(conversationId, content, answer)` / `sendMessageQueued`；ChatService 是 Koin 单例（appScope 持有会话表），不依赖 Activity | `service/ChatService.kt:1173` / `:1112` |
| 后台生成保活 | ChatService 生成时自动 acquire FGS（`ChatService.kt:1035`），主动消息走同一入口自动获得前台保护 | `service/ChatGenerationForegroundService.kt` |
| 通知→会话跳转 | PendingIntent 携带 `conversationId` extra，RouteActivity 已处理 | `ChatGenerationForegroundService.kt`（getConversationPendingIntent）→ `RouteActivity.kt:268` |
| 通知渠道基建 | 渠道集中创建（现有 chat_completed HIGH / chat_live_update LOW / web_server LOW） | `RikkaHubApp.kt:268`（:51-53 渠道 ID 常量） |
| Worker 范式 | CoroutineWorker + KoinComponent + `enqueueUniquePeriodicWork(UPDATE)` | `data/sync/SyncWorker.kt`、`RikkaHubApp.kt:210` |
| 助手级开关模式 | `enableXxx` 字段家族（enableMemory/enableWebSearch/enableTimeReminder…） | `data/model/Assistant.kt:17` |
| headless 辅助 LLM 调用先例 | 记忆固化管线（构建 prompt → 直接调 provider → 解析 JSON） | `data/ai/MemoryConsolidation.kt` |
| 助手持久化 | PreferencesStore（DataStore JSON + 版本迁移机制，新字段带默认值即兼容） | `data/datastore/PreferencesStore.kt` |

### 4.2 新增组件（新包 `data/proactive/` + `service/proactive/`）

```
data/proactive/
  ProactiveConfig.kt          // ProactiveConfig / ProactiveTrigger / QuietHours（@Serializable，挂到 Assistant）
  ProactiveScheduleMath.kt    // 纯函数：下次触发时间计算（allowToday 语义）、免打扰判断、上限/冷却判断 —— 全部可 JVM 单测
  ProactiveLedgerStore.kt     // DataStore 台账："已排待兑现"（key=yyyy-MM-dd@HH:mm@triggerId）+ 每槽最后执行时间戳 + 每日计数
  ProactiveScheduler.kt       // ensureScheduled()覆盖式不取消 / rescheduleAll()仅用户保存时（先查权限再取消）
  ProactiveAlarmReceiver.kt   // 收闹钟 → 启动 FGS（不做任何耗时操作）
  ProactiveBootReceiver.kt    // BOOT_COMPLETED → ensureScheduled 覆盖式重排
service/proactive/
  ProactiveGenerationService.kt  // FGS(dataSync)：startForeground → 先续订 → 防重 → 执行 → 销账 → stopSelf
  ProactiveExecutor.kt           // 触发 prompt 构造 + ChatService.sendMessage + 结果落库/通知
  ProactiveNotifier.kt           // 渠道 proactive_message（IMPORTANCE_HIGH）；点击/快捷回复/稍后提醒 action
ui/pages/assistant/detail/
  AssistantProactivePage.kt      // 触发器编辑（P0：每日时刻列表）+ 权限自检卡
```

Manifest 新增：`SCHEDULE_EXACT_ALARM`（12+，运行时引导用户授权「闹钟和提醒」）、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`；receiver×2 注册。现有权限已覆盖 `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE_DATA_SYNC`（`AndroidManifest.xml:13-22`）。

## 5. 通知交互

- **渠道**：`proactive_message`，IMPORTANCE_HIGH（横幅+声音，对齐 chat_completed 渠道做法）。
- **内容**：标题「{助手名} 给你发来消息」；正文 PREVIEW=前 80 字 / PLACEHOLDER=固定文案（隐私场景，锁屏不可见内容）。
- **动作**：
  - P0：点击 → 打开对应会话（复用 conversationId extra 跳转）。
  - P1：`RemoteInput` 快捷回复（→ ChatService.sendMessage）、「稍后再说」（+30min 重排一次）、长按关闭该触发器。
- **失败可见**：生成失败（网络/额度/空回复）→ 发「主动消息生成失败：{原因}」通知，同时台账**销账**（失败已告知，不自动重试，避免重复烧钱）；**被取消（CancellationException）≠ 失败**：不通知、台账**保留**，等下次对账补做。

## 6. 设置 UI 与权限引导

- **助手详情页**新分区「主动消息」：总开关 + 触发器列表（P0 仅每日时刻，支持多条）+ 免打扰时段 + 每日上限/冷却 + 通知隐私选项。
- **权限自检卡**（缺哪个亮哪个，点击跳系统设置）：
  1. 通知权限（13+ POST_NOTIFICATIONS）
  2. 精确闹钟权限（12+ 「闹钟和提醒」）
  3. 电池优化白名单（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导）
  4. 国产 ROM 自启动/后台运行（按品牌给路径说明文案）
- 全局通知设置页（`ui/pages/setting/SettingPreferencesNotificationPage.kt`）加入口说明。

## 7. 可靠性规范（逐条采纳渊海踩坑清单，实现时作为验收项）

| # | 规范 | 原因（踩坑实例） |
| --- | --- | --- |
| 1 | `setExactAndAllowWhileIdle` 是**一次性**闹钟，FGS 里**生成之前**先续订下一次 | 只在开机/保存时排 = 「用一天多就不推了」 |
| 2 | 进程启动补排**只能覆盖式排，绝不先 cancel**；禁用 `pendingIntent.cancel()` | Application.onCreate 先取消会作废在途广播 = 闹钟唤醒进程后进程亲手杀掉唤醒自己的闹钟，仅冷启动复现，极难查 |
| 3 | **单一路径**：只有 Alarm→Receiver→FGS→执行；事件总线/其他入口不得触发真实推送 | 双路径 = 每次收两条 |
| 4 | 三层防重：进程内互斥（重复的直接 return，**不要** `stopSelf(startId)`）→ 时间窗 30s → 台账持久化校验（生成前查 + 事务内写前查） | `stopSelf(最新startId)` 会停掉整个服务、连坐取消正在跑的生成 |
| 5 | 2 分钟安全边距**只用于用户手动保存**那一次；补排/续订用 `allowToday=true` 不留边距 | 边距用在补排路径 = 推送前 2 分钟开过 App 今天这次就没了 |
| 6 | 「已排待兑现」台账：排闹钟记一笔（记闹钟真实落点那天）、成功落库销一笔；补做只补**最近错过的那一笔**、仅当天有效 | 判据用「已过点且无记录」会让新存的过去时刻立刻补发 |
| 7 | `CancellationException` 单独 catch 原样抛，不计失败不通知不重试 | 宽泛 catch 把"进程被杀"伪装成"模型出错"，带偏排查 |
| 8 | 真失败发**带原因的可见通知**并销账 | 「扣了钱但什么都没显示」且与「闹钟没响」无法区分 |
| 9 | 取消闹钟前先查权限，查不到**整个跳过别动** | 先取消再排被权限挡住 = 闹钟清空且不补回，彻底死掉 |
| 10 | 触发生成**禁用写类工具**（防重判据字段对所有写入路径可见） | AI 用工具写消息不带防重标记，三层防重全部隐形穿透 |
| 11 | 补做挂 `ProcessLifecycleOwner.ON_START`，不挂 `Application.onCreate` | 12+ 进程后台态 `startForegroundService` 直接抛异常 |
| 12 | FGS 常驻通知即**诊断探针**：用户报"没收到"先问这条通知出现没有 | 没出现=调度层问题；出现了=生成/通知层问题，砍一半排查面 |

局限性（如实告知用户，写进设置页说明）：关机无推送（开机后台账补做当天窗口内错过的一次）、断网生成失败、Doze 下秒级~几十秒延迟、国产 ROM 玄学只能靠权限引导。

## 8. 分期实施

### P0 — MVP（Daily 触发 + 全链路可靠）
1. `ProactiveConfig` 模型 + Assistant 字段（默认 null 兼容）
2. `ProactiveScheduleMath` 纯逻辑 + JVM 单测（触发时间/免打扰/上限/冷却/防重判据）
3. `ProactiveLedgerStore` + `ProactiveScheduler`（ensureScheduled/rescheduleAll）
4. Receiver + FGS + `ProactiveExecutor`（走 ChatService.sendMessage，禁工具）
5. 通知渠道 + 点击进会话 + 失败通知
6. BootReceiver 重排 + 台账补做（ON_START）
7. 助手详情页设置 UI + 权限自检卡
8. 验证：`./gradlew :app:compileDebugKotlin` + 单测 + 人工矩阵（通知关/闹钟权限关/电池优化/重启/清后台/免打扰）

### P1 — 触发器扩展与体验
Interval / Inactivity 触发器、RemoteInput 快捷回复、「稍后再说」、WorkManager 每日对账兜底 Worker、会话内「主动消息」徽章、通知隐私占位文案、每触发器独立 prompt 编辑。

### P2 — 智能化
Heartbeat 自主决策（低频唤醒 + `{speak, text}` 结构化输出 + 小模型/辅助模型档位）、事件触发（回到前台间隔 N 天、充电状态）、自然语言创建触发器（"每天早上叫我带伞看天气"→ 结构化 Daily + prompt）、revive-companion 式智能时机（Poisson/贝叶斯，仅参考思路）。

## 9. 待拍板决策点

1. **会话定位**：主动消息发到助手的最近会话（推荐）？还是每次新建会话？还是让用户按触发器指定会话？
2. **触发注入消息落库与否**：推荐不落库（回复落库 + `source=proactive` meta + 徽章）；若要"可解释"，可落库为灰色系统气泡。
3. **工具策略**：P0 全禁（推荐，防重+成本最稳）vs 只读降级白名单。
4. **全局总开关**：需求为助手级；建议另加一个全局总开关放通知设置页（一键全关），默认开。
5. **P0 是否包含 WorkManager 对账兜底**（可后移 P1，MVP 用 ON_START 补做即可）。
6. **通知隐私默认值**：PREVIEW（显示前 80 字，体验好）vs PLACEHOLDER（隐私安全）。陪伴类场景用户可能更在意锁屏隐私。
