# RikkaHub 管理控制台（Management Console）优化方案

> 目标对象：对话输入框下方 `WorkspaceFooterBar` 中的 `ServerStack01` 图标入口（`Screen.ManagementDashboard`）所打开的 `ManagementPage` 控制台。

## 一、现状盘点

### 1.1 入口与页面映射

| 角色 | 文件 | 说明 |
|---|---|---|
| Footer 入口 | `app/.../ui/components/ai/WorkspaceFooterBar.kt` | 输入框下方单行栏；`ServerStack01` 图标 → `navigate(Screen.ManagementDashboard)`，`minimal` 极简模式下隐藏右侧指标与图标 |
| 控制台页面 | `app/.../ui/pages/setting/ManagementPage.kt` | 控制台主体（`SettingListScaffold`，三个 `IosGroup` 区块） |
| 模式页入口 | `app/.../ui/pages/setting/SettingModePage.kt`（214–249 行） | 同卡片式入口，文案 `setting_page_management_console` |
| 审计数据源 | `app/.../data/management/ManagementAuditStore.kt` | 内存环形审计（默认 200 条），`ManagementAuditEntry(tool/target/result/detail/timestamp)` |
| 审计全量页 | `app/.../ui/pages/setting/SettingDeviceAuditPage.kt` | 已有"设备操作记录"列表，是更完整的审计列表雏形 |
| 能力/模式模型 | `app/.../data/model/ChatMode.kt` | `Capability` / `ChatModePolicy` / `AgentBehaviorProfile` |
| 管理工具清单 | `app/.../data/ai/tools/ManagementTools.kt` | `admin_inventory`、`provider_*`、`assistant_*`、`settings_admin_*`、`search_admin_*`、`workspace_admin_*`、`trusted_folder_admin_*`、`knowledge_admin_*`、`conversation_admin_*`、`audit_list` 等 |

### 1.2 当前已展示的数据

`ManagementPage` 目前只有三组：

- **运行状态**：提供商数 + 模型数、助手数、MCP（已连/异常）、搜索服务数、已启用技能数、知识库绑定数、工作区绑定数、信任文件夹项目数、会话历史数。
- **当前模式权限**：默认模式名、管理能力清单、未生效（受限）能力。
- **管理审计**：最近 8 条 `tool → target · result`，无详情、无筛选、无分页。

### 1.3 明显短板

1. **数据偏"计数"，缺"状态与健康"**：只有 `providers.size`，没有"可用/不可用"；MCP 只给 `x/y 已连接`，不展示每个 server 的失败原因；workspace 只有"绑定数"，没有 shell 状态；审计只有最近 8 条，无详情、无筛选。
2. **缺"会话用量"**：footer 已经算出 `cacheHitRate` 与 `totalCost`（含子代理用量），但控制台看不到这些"钱/缓存"维度的信息。
3. **信息是平铺列表**：三个 `IosGroup` 上下罗列，一眼看不到重点；没有状态色、没有分层。
4. **没有深链/跳转**：看到"3 个 MCP 有异常"不能点进去处理。
5. **没有空态/刷新/体检**：无数据、加载中、数据过期都无区分。
6. **审计未复用已有页面能力**：`SettingDeviceAuditPage` 已能展示 `detail` 与结果色，却与 `ManagementPage` 割裂。

---

## 二、参考的主流 agent / 插件项目做法

- **camtrik/agent-trail**：一个本地 dashboard，聚合 Claude Code / Codex / OpenCode / OpenClaw / Qoder 的 **session history、token usage、cost tracking、tool calls、subagent replay**。核心启发：把"会话历史 + 用量 + 工具调用"做成**可检索、可回放**的中心，而不只是一串计数。
- **Open WebUI Administration / Analytics**：管理面板聚合 **user/model 维度用量统计**，用"仪表盘 + 分组 + 可导出"呈现，而不是单条平铺。
- **LibreChat `token_usage`**：**逐请求记录 token → 成本**，并把成本归因到模型/会话，便于追溯。
- **Javis603/token-monitor、658jjh/claude-usage-tracker**：跨工具的**统一用量/成本聚合**视图，强调"一屏看向所有本地工具的钱花在哪"。
- **Cline / Roo Code**：会话内 Tools/Auths 面板，**按调用列出每次工具结果 + 每请求成本**，支持展开查看输入/输出；对"审计/工具调用可视化"很有参考价值。

**提炼可复用模式**（均为只读呈现，不引入写流程）：

| 模式 | 参考来源 | 本项目落点 |
|---|---|---|
| 顶部状态胶囊（颜色编码） | Open WebUI / Cline | 一行 `pill` 汇总健康：提供商、MCP、工作区、信任文件夹、会话 |
| 分组计数卡 + 关键状态 | agent-trail | "运行状态"由平铺计数改为分组卡，带状态色与深链 |
| 用量/成本聚合卡 | LibreChat / token-monitor | 复用 footer 的 `cacheHitRate`/`totalCost`，上移到控制台 |
| 事件流可筛选、可展开 | Cline / agent-trail | 审计区支持状态筛选 + 展开 `detail` + 跳全量日志 |
| 一键体检（只读健康） | Open WebUI Admin | 提供"刷新/诊断"动作，只读 status，不触发写 |

> 注意对齐：本项目 **agent preset / 能力门控** 思路与 dsh 的 preset 一致——管理台是"**委派前的只读检查台 + 审计追溯 + 深链**"，而不是重型监控平台。避免照搬 agent-trail 那种重型 dashboard，保持 on-device、离线优先、轻量。

---

## 三、定位与目标

**定位**：RikkaHub 是 on-device 个人 agent；管理控制台应是"**看一眼就能决定要不要让 agent 动手**"的只读入口。

三条主线：

1. **看得全**：数据覆盖面对齐 `admin_inventory`（provider/model/assistant/custom_mode/builtin_override/mcp/skill/knowledge/workspace/trusted/conversation）+ 会话用量（token/成本/缓存命中/子代理）。
2. **看得清**：从"三段平铺列表"升级为"状态摘要条 + 分组卡片 + 芯片/等宽数字"，状态用颜色区分。
3. **进得去**：每个数据点可**深链**到对应设置页；审计可**筛选/展开/跳全量**。

**约束/取舍**：

- 保持只读展示，写操作仍走现有审批链路（`ChatService` 审批 + `ManagementAuditStore`）。
- 极简 `AgentBehaviorProfile.MINIMAL` 下 footer 保持只显示模式 chip（现有逻辑不动），控制台仍可从模式设置页进入。
- 初期**不引入图表库**（用 `LinearProgressIndicator` / 文字 / 进度条即可）；本地方案，避免网络依赖。
- 新增文案一律进 `strings.xml`，用 `setting_page_console_` 前缀，支持多语言（en 默认 + zh/ja/zh-rTW/ko/ru）。

---

## 四、优化方案

### 4.1 新版信息架构（5 块卡片 + 顶部摘要条）

```
┌──────────────────────────────────────────────┐
│ 顶部状态摘要条：提供商·MCP·工作区·信任文件夹·会话   │  ← 状态 pill 行
├──────────────────────────────────────────────┤
│ [1] 运行状态       分组计数 + 关键状态 + 深链     │
│ [2] 会话用量       token / 成本 / 缓存命中/子代理 │
│ [3] 模式与权限     能力 chips + 未生效项          │
│ [4] 管理审计       可筛选事件流 + 展开 + 跳全量    │
│ [5] 快速跳转       深链一组                      │
└──────────────────────────────────────────────┘
```

### 4.2 改动一：数据层（"看得全"）

新增一个轻量聚合 VM（建议 `ManagementConsoleVM`，或扩展 `SettingVM`），**只读**，组合现有 flow，避免重复查询：

| 数据 | 来源（已有） | 建议呈现 |
|---|---|---|
| providers / models / assistants / custom_modes / builtin_mode_overrides / mcp_servers / skills / knowledge / workspaces / trusted / conversations | 复刻 `ManagementTools.admin_inventory` 的统计逻辑 | 运行状态分组卡 |
| mcp 连接健康 | `mcpManager.syncingStatus`（`McpStatus.Connected/Error`） | 每个 server 一行 + 错误提示 + 深链 `SettingMcp` |
| workspace shell 状态 | `WorkspaceRepository.listFlow()` | workspace 列表 + 深链工作区 |
| trusted active | `TrustedFolderRepository.settingsFlow` | 当前活跃项目、项目数 |
| 会话用量 | footer 已算出的 `cacheHitRate`/`totalCost`（加上 `subAgentUsages`） | 会话用量卡 |
| 各模式会话数 | `ConversationRepository.countConversationsByMode(modeRef)` | （可选）模式占比 |

**说明**：这些都是**现成 flow / 一次性查询**，不需要新表；仅在"会话用量"从 footer 抽到共享状态时需要把 `WorkspaceFooterBar` 里的 `CostCalculator` 计算逻辑上移（或复用同一工具函数）。

### 4.3 改动二：排版 / UI（"看得清"）

- **顶部状态摘要条**：一行 `pill`，绿/黄/红/灰状态色（复用 `ManagementPage` 的 mcp 健康判断 + `SettingDeviceAuditPage.resultColor` 的思路）。
- **计数卡**：`Row` + `Modifier.weight(1f)` 两列 grid；数字用 `fontFeatureSettings = "tnum"`（footer 已有 `IndicatorText` 先例），保证等宽。
- **能力 chips**：`managementCapabilities`/`restricted` 由 `joinToString` 文本改为 **AssistChip / FilterChip 行**，颜色区分"已启用/未生效"。
- **审计事件行**：默认显示 `tool → target · result`，点击展开 `detail`（`AnimatedVisibility`，参考 `SettingModePage.ModeDetailCard` 的展开交互）；结果色沿用 `SettingDeviceAuditPage.resultColor`。
- **空态/骨架**：loading 用现有 `SettingListScaffold(loading=...)`；空数据给明确空态文案；MCP 异常给"异常原因"。
- **复用既有组件**：`IosGroup`、`Card`、`CustomColors.cardColorsOnSurfaceContainer`、`ModalBottomSheet`（详情弹层可选）。

### 4.4 改动三：交互（"进得去"）

- **审计区**：
  - 状态筛选 chips（全部 / 成功 / 拒绝 / 异常）。
  - 顶部搜索框（按 tool/target 过滤）。
  - "查看全部" → 跳 `SettingDeviceAuditPage`（或新建全量审计页）。
  - 行点击展开 detail。
- **一键体检（只读）**：一个"刷新/诊断"动作，聚合 mcp / provider(可选 `provider_test_all`) / workspace 健康状态并显示结果；**只读**，不触发管理写操作。
- **深链**：每个分组卡头部/行尾放小图标跳转：
  - `SettingProvider`（提供商）、`SettingModes`（模式）、`SettingSearch`（搜索服务）、`SettingMcp`（MCP）、`SettingFiles`（文件/工作区）、`SettingDeviceAudit`（设备操作）。

### 4.5 改动四：入口增强（可选）

- 给 footer 的 `ServerStack01` 加**状态角标**：当 `mcpErrorCount > 0` 或审计有未读异常时显示小红点/角标（`Badge`），让"需要去看一眼"更明显；minimal 模式仍隐藏。

---

## 五、分期落地（低风险优先）

### P0 — 快速增强（数据都能直接取到，UI 轻改）
1. 审计区升级：状态筛选 + 展开 `detail` + "查看全部"深链。
2. 顶部状态摘要条（一行 pill）。

### P1 — 信息架构重构
1. 新建 `ManagementConsoleVM` 聚合数据（对齐 `admin_inventory` + 会话用量）。
2. 运行状态改为分组计数卡；模式与权限 chips 化。
3. 会话用量卡上移 footer 的 `cacheHitRate`/`totalCost`。
4. 各卡片深链。

### P2 — 数据 / 分析（按需）
1. 一键体检 / 刷新诊断。
2. 模式占比、会话历史用量趋势（若需要）。
3. 子代理用量明细/mcp 健康详情弹层。

---

## 六、涉及文件

- `app/.../ui/pages/setting/ManagementPage.kt`（主改造）
- `app/.../ui/pages/setting/SettingDeviceAuditPage.kt`（复用/扩展为全量审计）
- `app/.../ui/components/ai/WorkspaceFooterBar.kt`（上移用量计算/入口角标）
- `app/.../ui/pages/setting/SettingModePage.kt`（入口文案一致性）
- `app/.../data/model/ChatMode.kt`（能力 chips 展示辅助，可选）
- `app/.../data/management/ManagementAuditStore.kt`（如需带筛选的缓存视图可按需扩展）
- `app/.../res/values*/strings.xml`（新增 `setting_page_console_*` 文案，走 locale-tui）

---

## 七、验收标准

1. **数据覆盖**：控制台展示字段 ≥ `admin_inventory` 全部字段，并额外展示会话用量（token/成本/缓存命中/子代理）。
2. **排版**：窄屏（手机）与宽屏不溢出；`tnum` 等宽数字；状态色明确；minimal 模式仍只显示模式 chip。
3. **交互**：审计可按状态筛选、可搜索、可展开 `detail`、可跳全量日志。
4. **可追溯**：所有写操作来自 `ManagementAuditStore`，控制台只读；写操作仍走审批。
5. **i18n**：新增文本全部进入 `strings.xml`（en + zh/ja/zh-rTW/ko/ru），无硬编码中文。
6. **性能**：不引入图表库；数据用现成 flow，无重复 DB 扫描。

---

## 八、风险与取舍

- **不要做成重型监控平台**：agent-trail / Open WebUI 的完整分析值得借鉴，但 on-device 场景需克制——先做"一眼健康 + 审计 + 深链"，统计趋势按需再上。
- **体检动作保持只读**：`provider_test_all` 等是工具调用，若要在控制台直接触发，需说明是"只读测试"且不影响配置；不做成 agent 写操作。
- **审计内存性**：`ManagementAuditStore` 是进程内环形（200 条），若要持久化/跨重启需建表（本次方案默认不扩，保持轻量），仅在需要长期追溯时再引入。
