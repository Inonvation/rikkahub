# 上游同步日志

本日志记录每次上游同步的分类清单与落地结果。同步流程见 `AGENTS.md`「上游同步流程（禁止直接 merge）」：不直接 merge，按「无需合并 / 可放心合并 / 需本地化手动同步」三分类逐条落地。

## 2026-08-31 — 第四次同步（16 个新提交，2.4.16 批次，上游待同步清零）

### A. 无需合并（2 个）

| 提交 | 内容 | 结论 |
|---|---|---|
| `4309fdfe` | bump 2.4.16 | 版本号独立演进 |
| `41353567` | 支持关闭自动重试 | 本地 `aiRequestMaxRetries`（0..10，0=不重试）已含等价实现，UI 在能力管理页 |

### B. 可放心合并（7 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `5c217d2e` | HTML/SVG 内嵌预览默认关闭 | HighlightCodeBlock `mutableStateOf(canInlinePreview)` → `false` 1 行 |
| `1b9dd092` | 移除硅基流动余额查询 | DefaultProviders 删 SiliconFlow 的 balanceOption 块 |
| `0651cad9` | 气泡透明度 roundToInt | 本地该滑条在「界面偏好→气泡」页，`toInt()` → `roundToInt()` + import |
| `1231b8af` | 加密 reasoning 请求断言修正 | 测试改名 + 断言改断言 `encrypted_content` 存在且无 `content`；顺带补齐发送端门槛：已携带 encrypted_content 时不再重放明文 content（ResponseAPI 1 处，本地此前缺失） |
| `ec20570b` | TTS 播放倍速 5 语言补全 | locale-tui `set` 逐语言落地 ja/ko-rKR/ru/zh-rTW/zh（values 源文件已有两 key） |
| `d3a53e0a` | 图片选择改 PickVisualMedia | UIAvatar/BackgroundPicker 单图 `GetContent`→`PickVisualMedia`；ChatPage/GroupDiscussionPage/ImgGenPage 多图 `GetMultipleContents`→`PickMultipleVisualMedia`（本地无 ChatAttachmentPicker 组件，等价调用点直改），launch 统一 `PickVisualMediaRequest(ImageOnly)` |
| `f6a5330f` | trace-cli 支持 Google Interactions API | 5 个 trace-cli 文件照抄；trace 目录 `google/`→`google-generateContent/`（git mv 纯改名 + gemini-tool/gemini-image 的 expected.json 更新），StreamTraceReplayTest 注册新路径；本地 `trimBlankLines` 语义下两 fixture 的 text 尾随空行按本地 pipeline 校准 |

### C. 需本地化手动同步（2 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `883bde7d` | TTS 默认播放倍速设置从语音页移到偏好 | 本地落点 =「界面偏好设置→TTS 播放」分组（SettingDisplayGroupPage，与上游 GeneralPage 的 TTS 分区对应）：状态提升页面级、滑条 onValueChangeFinished 落库、0.5x-2.0x/14 档与上游一致；删除 SpeechPage 卡片 + 3 个无用 import |
| `f7869e35` | Gemini 混合 server/client tool 修复 | 手工融合本地 ai 定制层（HttpException/jsonXxxOrNull/懒创建 Text part）：tools 条件收紧为 Search/UrlContext 才发数组，函数工具+内置工具混用时加 `toolConfig.includeServerSideToolInvocations=true`；`parseMessageParts` 按 toolCallId 归并 ServerTool（补名/补参/状态推进/metadata 合并）；functionCall part 回传 id；text/reasoning part 携带 thoughtSignature；Decoder/Provider 新增 `toolCall`/`toolResponse` 分支（本地 jsonObjectOrNull hardening 风格）；StreamChunk TextStart/TextDelta 加 metadata 字段（本地懒创建语义下 Start 元数据存 pending map，TextEnd/Finish 清理）；MessageMetadata 增加 `GOOGLE_GENERATE_CONTENT` 协议；新增 `GoogleToolCombinationTest` + gemini-tool expected.json 更新 |

### D. 部分吸收（1 个）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `9365c297` | 快速模型思考级别；移除单独标题/建议模型配置 | 只吸收思考级别（本地保留标题/建议模型配置：辅助模型链 + ManagementTools 引用）：`Settings.fastModelReasoningLevel`（AUTO）→ PreferencesStore 新 key 读写 + SettingsSyncCodec ALLOWLIST 加入；SettingModelPage 通用分组「快速模型」行下新增 ReasoningButton 行（复用 `setting_provider_page_reasoning`，不新增字符串）；ChatService 新增 `backgroundReasoningLevel(settings, model)` 辅助（解析到快速模型才应用级别），标题/建议/记忆整理 3 处传入 |

### 验证

- `:ai:testDebugUnitTest` 全量 BUILD SUCCESSFUL（235 例，含新 GoogleToolCombinationTest、trace 回放、加密 reasoning 断言）
- `:app:compileDebugKotlin` BUILD SUCCESSFUL
- `:app:testDebugUnitTest --tests "*SettingsSyncCodec*" "*ChatService*"` 通过（11 + 10 例）
- 6 语言 strings.xml well-formed
- 备注：并行会话在改 `WorkspaceShellBoundTest.kt`（未跟踪、API 未解析）阻断了 app 单测编译，临时移出验证后已原样还原，未改动其内容

## 2026-08-30 — 第三次同步（4 个提交，2.4.15 后 8/29 批次，上游待同步清零）

| 提交 | 内容 | 分类 | 落地方式 |
|---|---|---|---|
| `8ea375a9` | Cloudflare MCP 无法匹配 OAuth | 可放心合并 | `needsAuthorization` 改为 `looksUnauthorized(error) && oauth.enabled` 才短路返回 true，其余场景落到受保护资源探测兜底（本地文件与上游父提交逐字节一致，直接套用） |
| `ef94834a` | MCP SDK 仅用 client sdk | 可放心合并 | `libs.versions.toml` 单行 `kotlin-sdk` → `kotlin-sdk-client`；已核对本地 import 面仅 client/shared/types，无 server 引用 |
| `5403bc96` `2f05019b` | TTS 合成自动重试 + 可重试判定 | 需本地化手动同步 | 新增 `TTSProviderException`（408/429/5xx 判可重试）；10 个 provider 的 HTTP/SSE 错误改抛带状态码异常；`TtsController` 移植 3 次指数退避重试（500ms base）、失败 Deferred 逐出、`isRetryableSynthesisError`（IOException 或可重试异常才重试）、预取窗口 4→2、移除 `lastPrefetchedIndex` 机制；本地磁盘发音缓存（命中跳过合成、成功落盘）完整保留 |

### 验证

- `:speech:compileDebugKotlin` `:app:compileDebugKotlin` BUILD SUCCESSFUL
- 单测：`:app:testDebugUnitTest --tests "*Mcp*"`（4 类 26 例）全部通过
- `prefetchFrom`/`lastPrefetchedIndex` 残留引用清零

## 2026-08-28 — 第二次同步（10 个提交，范围 2.4.14 → 2.4.15）

### A. 无需合并（3 个）

| 提交 | 内容 | 结论 |
|---|---|---|
| `321443d8` | tool-only 消息操作可见 | 本地 `isEmptyUIMessage` 已有 `Tool/ServerTool -> false` 等价实现 |
| `7aa909b8` | 上游回滚输入栏折叠功能 | 本地从未吸收 `ca31612d`，无需动作 |
| `5662945c` | bump 2.4.15 | 版本号独立演进 |

### B. 可放心合并（4 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `5b890d22` | HY4 移除 vision | 删 ModelRegistry `visionInput()` 1 行 |
| `ecc6d910` | claude-api skill 精简 | 删 SKILL.md 触发器 2 行 |
| `9687f97a` | daily-build 加 17:00 触发 | cron 改 `0 9,18 * * *` |
| `eba2e96c` | 依赖升级 | cameraCore 1.6.2 / material3 alpha27 / nav3Core 1.1.7 / okhttp 5.5.0 / baselineprofile rc02；nav2 条目本地零引用一并删除；sqlite-vector 本地无此条目跳过 |

### C. 需本地化手动同步（3 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `9851d037` | Gemini 服务端/客户端工具共存 | 本地已实现合并 tools 数组（含 trim 定制），仅把外层条件收紧为 `useFunctionTools \|\| model.tools.isNotEmpty()`，避免发出空 `tools: []` |
| `7da69770` | 文件清理按时间范围 bottom sheet | FilesManager 加 `deleteOlderThan`；SettingFilesPage 清理对话框改 ModalBottomSheet + CleanRange 单选（本地批量选择/来源筛选功能保留）；3 个新字符串 + `setting_page_chat_storage` 改名，6 语言经 locale-tui 落地 |
| `b6df5f04` | OAuth 重构（新 oauth 模块 + loopback 回调） | `git checkout` 上游 oauth/ 模块、McpOAuthCoordinator、McpOAuthDiscoveryClient；删除 McpOAuthClient/McpOAuthCallback/McpOAuthCallbackActivity 及 Manifest 声明；McpConfig 加 `redirectUri`；McpManager/McpSessionRegistry 本地定制（InvalidConfig、aiModifiedServers、configError）全部保留；本地 ProviderConfigure 的设备码流程复用旧 `launchOAuthAuthorization`，改用新模块 `CustomTabsOAuthAuthorizationLauncher.launch` 等价替换 |

### 验证

- `:oauth:compileDebugKotlin` `:app:compileDebugKotlin` BUILD SUCCESSFUL
- 单测：`:oauth:testDebugUnitTest`（2 类）、`:ai:testDebugUnitTest` 全量、`:app:testDebugUnitTest --tests "*Mcp*"`（8 例）全部通过
- 6 语言 strings.xml well-formed 校验通过
- `:app:assembleDebug` BUILD SUCCESSFUL（manifest 合并 + 资源链接验证）

## 2026-08-27 — 首次同步（落后 29 个提交，范围 2.4.12 → 2.4.14）

### A. 无需合并（16 个）

| 提交 | 内容 | 结论 |
|---|---|---|
| `0c056d08` `170a612e` `da5fd77b` | 上游版本号 bump 2.4.12/13/14 | 本地版本独立（2.9.3），跳过 |
| `2dc50126` | CONTRIBUTING.md | 上游贡献者文档，fork 不适用 |
| `96fbe7e3` | 初始化 videogen api 层 | 本地树与上游完全一致 |
| `c62f1eb1` | 前台服务防后台断开 | 本地文件逐字节一致，ChatService/Manifest 已接入 |
| `942d0d28` | 合成消息不参与消息模版 | 本地 `isSynthetic` 已存在且覆盖更广 |
| `c16fe44f` | 支持自动重试 | 本地自研更完整（RetryPolicy + ai-request-retry-plan Phase 1-3） |
| `8f4f1286` | extract translation handler | 纯重构无功能增量，本地内联实现保留 |
| `b62d29d1` | workspace stdin EOF | 本地 HEAD 已含等价实现（三文件与上游一致），cherry-pick 无净变更 |
| `6c6a8458` | fork 会话继承 folderId/cwd | 本地 createForkConversation 已含两字段 |
| `fa0305ba` `daae3749` | 模型搜索/输入栏 IME 修复 | 本地 imeAnimationTarget、ModelListSheet 结构均已落地 |
| `03534d14` `e9b98a4b` | Qwen audio 3.0 TTS 适配 + 音色修复 | 本地 provider/配置页/TTSController 已全部覆盖（含 require 校验、SpeechSynthesizer URL） |
| `bce78766` | 终端关闭确认 | 本地同路径已实现，strings key 齐全 |
| `4fcb590a` | 供应商测试连接本地化 | 本地已用 stringResource，5 个 key 齐全 |
| `e8293d35` | 空 tool schema 规范化 | 本地已含 `?: InputSchema.Obj(emptyMap())` 兜底 |
| `86c85236` `0826a3b9` | HY4 / Qwen 3.8 注册 | 本地已有 |

### B. 可放心合并（4 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `aab5026e` | Live Update 通知图标 | `git checkout` 上游 5 张 png + NotificationUtil 1 行（本地 HEAD 本已含相同内容） |
| `979cd169` | kimi icon 调整 | `git checkout` 覆盖 svg |
| `5b58c957` | GLM 5.3 / 5.3-flash 注册 | 手动补 ModelRegistry 两个条目（列表 + 注册） |
| `6e26affe` | mimo TTS 快速音色 | 手动：Voice 输入框改 SelectTextField + 9 个预设音色 |

### C. 需本地化手动同步（3 个，已落地）

| 提交 | 内容 | 落地方式 |
|---|---|---|
| `0533acde` | 移除默认 RikkaHub 提供商 | 删 DefaultProviders.kt 中 RikkaHub 块 + 3 个无用 import；保留 DEFAULT_AUTO_MODEL_ID（PreferencesStore 引用） |
| `53dc38b8` | 移除无用 mcp transport | 确认本地两文件为注释掉 package 的死代码、无引用后 `git rm` |

### 未合并（2 个，按用户指示跳过）

| 提交 | 内容 | 原因 |
|---|---|---|
| `7714f2bc` | Chatbox v2 备份导入 | 用户指示不合并（本地未来需要时再手动移植） |
| `8a9f4f0f` | 设置页抖音群信息 | 用户指示不合并 |
| `ca31612d` | 输入栏折叠动画重构 | 参考借鉴后不吸收：纯结构重组无 bug 增量，本地有独立改版计划 docs/chat-input-simplify-plan.html |

### 验证

`:app:compileDebugKotlin` BUILD SUCCESSFUL（唯一警告为既有 navigation3 opt-in 提示）。