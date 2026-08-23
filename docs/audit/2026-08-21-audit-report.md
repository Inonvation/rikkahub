# RikkaHub 深度审计报告（2026-08-21）

## 1. 审计概况

审计范围覆盖聊天主链路、数据持久化与同步、workspace 沙箱、内置 Web 服务、MCP、知识库、搜索、语音、文档解析与构建依赖。本报告只列问题与修复建议，未修改任何业务代码。未提交的 4 个文件（`ChatPage.kt`、`ChatInput.kt`、`WorkspaceFooterBar.kt`、`ImeAutoScroller.kt`）作为批 1 重点核对，内容未改动。

基线：

- `./gradlew :app:compileDebugKotlin` 通过。
- `./gradlew test` 失败 4 项，集中在 `workspace` 模块：`commandRunsInsideWorkspaceFilesDirectory`、`commandReceivesStdin`、`rootfsInstallerDownloadsAndExtractsTarGz`、`commandOutputIsTruncatedAtLimit`，疑似依赖本机 rootfs 下载与 proot 环境，需要单独复现确认。
- `./gradlew lint` 报 343 error、403 warning、16 hint。误差别集中在 `MissingTranslation`（285 条）和 Compose 状态/资源读取类问题，详见第 4 节。

严重级别定义：P0 崩溃或数据丢失；P1 明显行为错误；P2 体验、边界或安全硬化缺陷；P3 维护性、性能与风格问题。

## 2. P1 问题

### A1 停止生成时取消信号被吞，排队引导可能继续执行

位置：`ChatService.kt:1127`、`ChatService.kt:1434`、`ChatService.kt:1500`、`ChatService.kt:1542`、`ChatService.kt:1881`、`ChatService.kt:1285`、`ChatService.kt:2903`。

触发路径：AI 生成中发送引导，引导进入 `steeringQueue`，`drainSteeringQueue` 逐条注入续答；此时用户点击停止按钮。

根因：`handleMessageComplete` 用 `runCatching` 包裹生成流，协程取消时抛出的 `CancellationException` 被 `runCatching` 捕获，`.onFailure` 分支只判断类型但不重抛；`sendMessage`、`regenerateAtMessage`、`handleToolApproval` 的 `catch (e: Exception)` 同样不重抛。结果是被取消的 Job 以正常完成收尾，`job.isCancelled` 为 false，`drainSteeringQueue` 在 `job.join()` 后无法识别“用户已停止”，继续取队列下一条并启动新生成。

修复建议：所有生成入口统一遵守“捕获 `CancellationException` 必须重抛”的约定；`handleMessageComplete` 改为 `try/catch`，或让 `runCatching` 的 `onFailure` 对 `CancellationException` 执行 `throw it`。`stopGeneration` 应在取消 Job 后显式清空 `steeringQueue` 与 `pendingSendQueue`，不依赖 `isCancelled` 判断。

测试建议：新增 JVM 测试覆盖 `drainSteeringQueue` 在 Job 被取消后不再消费剩余队列；补一条“生成中排队引导 + 停止”的回归场景。

## 3. P2 问题

### B1 ChatPage 存在双层 `imePadding`，键盘弹起后可能出现列表底部间隙

位置：`ChatPage.kt:555`（外层 Column `.imePadding()`）、`ChatPage.kt:711-715`（底部输入栏 `.imePadding().navigationBarsPadding()` 且用 `onSizeChanged` 测量输入栏总高）。

风险：外层 Column 已把列表视口按键盘高度收缩，底部栏自身又叠加 `imePadding`，`inputBarHeight` 因此把键盘高度算进 `ChatList` 底部 content padding，可能让最后一条消息与输入栏之间多出一段与键盘等高的空白，或与 `ImeAutoScroller` 叠加产生双重位移。

修复建议：先在真机上复现键盘弹起/收起两种状态，确认后二选一：外层只保留系统栏避让，底部栏负责 `imePadding`；或反过来。`ChatList` 的底部 padding 只应包含输入栏真实内容高度，不应包含键盘高度。

测试建议：补 Compose UI 仪器测试，模拟键盘 insets 变化后断言最后一条消息与输入栏间距稳定；按仓库规则，instrumentation 测试需你明确允许后才执行。

### B2 IME 滚动策略改为“始终跟随”，阅读历史时弹键盘会跳位

位置：`ImeAutoScroller.kt:15-29`。

风险：旧逻辑只在列表钉在底部时随键盘滚动；新逻辑只要 `isScrollInProgress` 为 false 就按键盘高度差值 `scrollBy`。用户在阅读历史消息时弹出或收起键盘，视口会整体下移/上移，打断阅读位置。该文件与 `ChatPage` 的 `imePadding` 改动叠加后，跳位会更明显。

修复建议：恢复“仅在底部钉住时跟随”的判定，或改为在键盘出现时补偿“视口缩小导致底部内容被遮”的最小滚动量，键盘收起时不要反向滚动。

测试建议：`ChatListScrollStabilityTest` 增加“阅读历史时改变底部 inset，首屏消息不变”的用例。

### B3 Compose 中多处非可观察资源/状态读取

位置：`ChatMessageTranslation.kt:92`、`ManagementPage.kt:67`、`StatsPage.kt:294`、`TranslatorPage.kt:371`（`NonObservableLocale`）；`ChatDrawer.kt` 多处与 `AssistantImporter.kt:91,117` 等 46 处 `LocalContextGetResourceValueCall`；`ChatPage.kt:478,484` 与 `ChatMessageEditedFiles.kt:954`（`FlowOperatorInvokedInComposition`）。

风险：`Locale.getDefault()`、`context.getString`、`tasksFlow.map{...}` 等在组合中直接调用，配置变更或重组时可能读到过期值，或反复重建 flow 链。多数是“潜在错误”而非必然崩溃。

修复建议：资源读取改用 `stringResource` 或 `remember(configuration)`；flow 链用 `remember` 固定；`Locale` 读取订阅 configuration 变化。

### B4 API key、WebDAV/S3 口令、Web 访问密码明文存 DataStore

位置：`PreferencesStore.kt:569-572` 及 `PROVIDERS`、`WEBDAV_CONFIG`、`S3_CONFIG` 等 key。

风险：个人设备上属可接受，但备份、root 或本地文件被读取时全部凭据明文暴露。

修复建议：至少对 `apiKey`、`password`、`secret` 字段用 Android Keystore 加密后再落盘；若短期不动，在设置页加明文存储提示。

### B5 Web 服务 JWT 开关关闭时暴露能力

位置：`WebApiModule.kt:69-135`、`PreferencesStore.kt:899-901`。

风险：默认 `webServerJwtEnabled=true`、`localhostOnly=false`。若用户关闭 JWT 且允许局域网访问，任意局域网客户端可调上传/删除/对话相关 API。

修复建议：JWT 关闭时强制仅绑定回环地址，或在设置页给出明确警告；默认保持开启。

### B6 聊天列表用 `LocalContext as Activity` 获取按键监听

位置：`ChatList.kt:254`。

风险：lint 提示应使用 `LocalActivity`。当前写法在非 `RouteActivity` 宿主时静默失效，音量键滚动功能会无声消失。

修复建议：改用 `LocalActivity` 获取 `RouteActivity`，无法获取时明确降级为不可用。

## 4. P3 问题

- 未提交代码中仍有 `todolist!!`（`ChatPage.kt:719-722`）和 `editingMessage!!`（`ChatPage.kt:797,829`）等非空断言，建议改为局部解包，避免未来重构引入 NPE。
- `ChatVM.toggleMessageFavorite` 先查库再写收藏，快速连点可能竞态翻转状态，建议用单次点击锁或直接更新状态再异步落库。
- `ConversationRepository.deleteConversation` 里 tombstone 写入被 `runCatching` 吞掉，失败时云端旧副本可能在下一次同步“复活”，建议失败时向同步管理器报告错误。
- `document` 模块多个解析器捕获异常后返回“Error parsing...”文本，用户无法区分解析失败与正常内容，建议改为结构化失败结果并在 UI 提示。
- `gradle/libs.versions.toml:62` 使用 `sqlite-android:-SNAPSHOT`，构建结果依赖远端快照状态，建议固定版本或改为正式版本。
- Web JWT 有效期 30 天（`WebApiModule.kt:46`），可接受，但建议记录签发时间并提供主动失效入口。
- lint 大量 `MissingTranslation`（285）、`UnusedResources`（161）、`TypographyEllipsis`（111）、`TypographyDashes`（41）、`PluralsCandidate`（21）属于清理项，不阻塞功能。
- `HapticFeedback.kt` 的 `InlinedApi` 警告已确认有 `SDK_INT < R` 守卫，属于误报，不需要修。

## 5. 分批复盘

批 1 聊天链路：会话引用计数、Job 身份校验、子代理去重与超时接管设计较完整；主要风险集中在取消传播（A1）和 IME/滚动交互（B1、B2）。

批 2 数据层：Room 版本链 1→45 完整，手动迁移与 AutoMigration 无缺口，未启用破坏性迁移；同步合并用事务包裹并有消息级 LWW，设计合理。主要问题是敏感配置明文（B4）与 tombstone 吞错（P3）。

批 3 安全与工具链：workspace 路径解析、trusted folders 相对路径校验、Web 文件路由防穿越均做了 canonical path 校验；Web JWT 默认开启。主要问题是 JWT 关闭时的局域网暴露（B5）与 lint 的 Compose 状态问题（B3、B6）。

## 6. 建议修复顺序

1. A1：取消传播与队列清空，补 `drainSteeringQueue` 单测。
2. B1、B2：真机确认 IME 行为后修一处布局与滚动策略，补滚动稳定性 UI 测试。
3. B3、B6：批量修 Compose 资源/状态读取与 Activity 获取。
4. B4、B5：凭据加密与 Web 安全开关。
5. P3 项按性价比逐步清理。

## 7. 限制与待确认

- 未运行 `connectedDebugAndroidTest`，避免测试流程触发卸载导致数据丢失；B1、B2 的最终结论需要你在真机上确认。
- `workspace` 的 4 个失败测试需要确认是否为 Windows 本机环境限制（rootfs 下载与 proot 命令）。
- lint 对 `local.properties` 自动修正了路径转义（改为正斜杠），该文件在 `.gitignore` 中，不影响仓库。
