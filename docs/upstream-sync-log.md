# 上游同步日志

本日志记录每次上游同步的分类清单与落地结果。同步流程见 `AGENTS.md`「上游同步流程（禁止直接 merge）」：不直接 merge，按「无需合并 / 可放心合并 / 需本地化手动同步」三分类逐条落地。

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