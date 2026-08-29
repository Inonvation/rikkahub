# 能力声明与配置隔离框架（capability-isolation-plan）

> 2026-08-29 创建 · 2026-08-29 修订（合入主流 agent 调研与三项决策）· 2026-08-29 实施落地（见 §9）。
> 背景：空配置助手（无系统提示词、无 MCP、无搜索、无 skill）在「跟随助手配置」下问「你有什么能力」，
> AI 自称能配置管理 MCP 服务器与技能；信任文件夹激活后对所有助手无差别生效。
> 落地约定：系统提示词 / 工具声明等提示词类内容直接写终稿，不带过程性注释。

## 1. 现状链路（谁在什么时候决定注入什么）

生成一次请求时，能力注入经过三层配置 + 一个运行时事实层：

```
Settings(全局) ─┐
Assistant(助手) ─┼─→ resolveConversationPolicy → ChatModePolicy(能力清单)
Conversation.mode ┘            │
TrustedFolder/MCP连接(运行时) ──┘
                               ↓
        ChatService.buildList { ... }   ← 每个工具族一段 ad-hoc 条件
                               ↓
        tools[] + GenerationHandler（system = 助手提示词 + 用户资料 + 工具systemPrompt + 行为层）
```

### 1.1 三层配置的语义（现状实际语义，非设计语义）

| 层 | 载体 | 实际管的事 |
|---|---|---|
| 全局设置 | `Settings` | 能力总开关（enableMcpManager/enableTodoList/enableSubAgent/enableAgentBehaviorPrompt）、默认模式（defaultMode）、内置模式覆盖（builtinModeOverrides）、自定义模式（customModes）、用户资料 |
| 助手配置 | `Assistant` | 每能力的「使用意图」：mcpServers / enabledSkills / enableWebSearch / localTools / workspaceId / knowledgeBaseIds / enableMemory / enableRecentChatsReference / enabledStudyTools / defaultMode 等 |
| 会话模式 | `Conversation.mode` → `ChatModePolicy` | 能力清单白名单（MINIMAL/STANDARD/PTC/CREATIVE/自定义），null 或 follow_assistant → `UNRESTRICTED`（全部非 managementOnly 能力） |
| 运行时事实 | McpManager 连接状态、信任文件夹激活、Shizuku 就绪 | 决定「声明了但实际不可用」的工具是否出现 |

### 1.2 各工具族的实际门控条件（ChatService 1837–2040 逐条核对）

| 工具族 | 门控条件（全部为真才注入） | 缺口 |
|---|---|---|
| todo | policy.allowTodo && settings.enableTodoList | 无助手级开关 |
| subagent | policy.allowSubAgent && settings.enableSubAgent | 无助手级开关 |
| study | policy.allowStudy && assistant.enabledStudyTools 非空 | — |
| search | policy.allowSearch && assistant.enableWebSearch | —（模型内置 BuiltInTools.Search 走 params，独立通道） |
| local | policy.allowLocalTools && assistant.localTools | — |
| device | policy.allowDeviceTools（内部 Shizuku.isReady） | — |
| history | policy.allowHistory && assistant.enableRecentChatsReference | — |
| workspace | policy.allowWorkspace + createWorkspaceToolsIfReady(workspaceId) | 助手级隔离 OK |
| **trusted_folder** | policy.allowTrustedFolder && 全局 activeProjectId != null | **无助手级绑定，激活即全局生效** |
| **skills** | policy.(use\|admin) && 设备上装了任意 skill | **admin 工具与助手 enabledSkills 无关** |
| mcp use | policy.allowMcpUse && assistant.mcpServers 过滤 && 已连接 | — |
| **mcp admin** | policy.allowMcpAdmin && settings.enableMcpManager(默认 true) | **无助手级门控** |
| creative/provider/assistant/settings/data admin | 仅 policy（CREATIVE） | — |
| memory | policy.allowMemory && assistant.enableMemory | — |
| knowledge | policy.allowKnowledge && assistant.knowledgeBaseIds 非空 | — |
| 文档/提醒/注入 transformers | 仅 policy | — |

### 1.3 系统提示词的实际堆叠顺序（GenerationHandler.generateInternal + inputTransformers）

```
1. 会话级 system（allowConversationSystemPrompt 时）否则 assistant.systemPrompt   ← 可为空
2. 用户资料（useUserProfile，全局稳定段）
3. 工具 systemPrompt（buildToolSystemPrompts 去重注入）                            ← 能力叙述在这里
4. 行为层（mode section + Plan&Act + Tool Groups + Ask User + SubAgent）
5. 环境块（transformer 依序追加）：<workspace> → <trusted_folder> → <knowledge_base>
6. 模式/lorebook 注入（BEFORE/AFTER 包裹）→ 占位符展开
7. <memories>（追加在最后一条 USER，不进 system）
```

## 2. 根因分析（「你好」自称会管 MCP/技能）

三个独立缺陷叠加，缺一不会出现该现象：

### 根因 A：UNRESTRICTED 能力集包含了 SKILL_ADMIN / MCP_ADMIN

`ChatMode.kt:267`：`UNRESTRICTED_CAPABILITIES = Capability.entries.filterNot { it.managementOnly }`，
而 `managementOnly = true` 的只有 CREATIVE_TOOLS / PROVIDER_ADMIN / ASSISTANT_ADMIN / SETTINGS_ADMIN /
DATA_ADMIN 五个。`SKILL_ADMIN`、`MCP_ADMIN` 没有标记，于是「跟随助手配置」永远携带管理声明。
又因 `settings.defaultMode`、`assistant.defaultMode` 默认均为 null（ChatMode.kt:308-310），
**新会话默认就走 UNRESTRICTED**——默认体验即泄露体验。

### 根因 B：管理工具的注入不看助手配置，且自带「能力自述」systemPrompt

- `mcp_admin_*`（McpManagerTools.kt:67）：唯一门槛是全局 `settings.enableMcpManager`（默认 true），
  与 `assistant.mcpServers` 是否为空无关；`mcp_admin_list` 挂的 systemPrompt 写着
  “**MCP Server Management** You can manage MCP server configurations…”。
- `skill_admin_*`（SkillsTools.kt:130-178）：门槛是「设备上装了任意 skill」（全局事实），
  与 `assistant.enabledSkills` 无关；systemPrompt 写着 “**Skills** … enable or disable them
  for the current assistant with `skill_admin_set_enabled`”（且此处文本提到 use_skill，
  但助手未启用任何技能时 use_skill 并未注册——文本与实际工具集漂移）。
- 助手系统提示词为空时，这两段自述 + 行为层的 Tool Groups（会把 mcp_admin_*、skill_admin_*
  列进工具地图）几乎构成 system 全文 → 模型据此回答「我能配置管理 MCP 服务器、管理技能」。
  **这不是模型幻觉，是声明按字面为真。**

### 根因 C：可用性裁决逻辑（restrictedCapabilities）只活在设置页 UI

`ChatModePolicy.restrictedCapabilities()`（ChatMode.kt:56-80）已经算出「哪些能力因助手/全局
配置实际不可用」，但只在 `SettingModePage.kt:361,429` 用于展示。生成链路从未消费它；
ChatService 里每个工具族各自重写一遍近似逻辑（且口径互有出入：MCP use 查连接状态、
admin 只查全局开关）。裁决逻辑没有单一出口 → 每加一个能力都要手写一遍组合条件，漂移必然发生。

### 伴生问题 D：信任文件夹是全局单激活，无助手级绑定

`TrustedFolderSettings.activeProjectId` 全局唯一（TrustedFolderStore.kt:21）。workspace 有
`assistant.workspaceId` 绑定（未绑定不注入），信任文件夹没有对应物——扩展管理里激活一个项目后，
所有处于含 TRUSTED_FOLDER 能力模式（PTC/CREATIVE/跟随助手配置）的助手一律获得 trusted_folder_*
工具 + `<trusted_folder>` 环境块。另外 `resolveModeRef` / `resolveConversationPolicy` 的
`trustedFolderActive` 参数是 `@Suppress("UNUSED_PARAMETER")` 死参——「信任文件夹激活联动模式」
的规划从未接线。

### 伴生问题 E：空 system 无身份兜底

system 的第 1 段（助手提示词）与第 2 段（用户资料）都可为空，此时 system 以管理工具自述开头，
模型对「我是谁」的唯一依据就是泄露进来的工具声明。

## 3. 主流 agent 方案调研

### 3.1 Claude Code：组件级条件拼装（2026-04 源码泄露，dbreunig 分析）

系统提示词不是静态串，而是几十个带注入条件的组件按序拼装：

- **身份 → 地面规则（工具/权限/注入如何运作）→ 编码哲学 → 工具使用策略 → 沟通规则**为常驻前缀；
- **每段工具引导严格跟随工具是否存在**：AskUserQuestion 引导仅在该工具可用时注入；子代理引导
  仅在 Agent 工具启用时注入；`/skill-name` 引导仅在「skills 可用且 Skill 工具启用」时注入；
- **缓存边界显式化**：`__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__` 分隔全局可缓存前缀与会话特定后缀；
- **环境信息（工作目录/平台/模型/日期）置尾**，git 状态快照最后追加；
- **MCP 服务器 instructions 每轮重算、不进缓存**（动态内容隔离在末尾）；
- **完全没有「配置管理工具」**：MCP 配置（`.mcp.json` / `claude mcp add`）、技能启停（settings）
  全部是用户侧操作，模型只拿「使用面」工具。

### 3.2 Codex CLI：声明 / 审批 / 沙箱三轴分离

`config.toml` 三组独立配置：`tools`（哪些工具存在）、`approval_policy`（哪些操作要确认）、
`sandbox_mode`（read-only / workspace-write / danger-full-access，技术可达范围）。
声明与审批互不纠缠——工具可以存在但默认只读，越界操作走审批而非隐藏工具。

### 3.3 Anthropic Agent Skills：渐进式披露

启动时只把每个技能的元数据（name + description，约 100 token）预载进 system prompt，
正文在模型调用后才加载。三层：元数据 → SKILL.md 正文 → 附带资源文件。

### 3.4 对照本项目：已对齐 / 需调整

| 主流做法 | 本项目现状 | 结论 |
|---|---|---|
| 提示词段与工具存在性同生共死 | 工具 systemPrompt 无条件注入（根因 B） | 需改：注册表统一裁决 |
| 渐进式披露（技能元数据先行） | `<enabled_skills>` 只列 name+description，正文按 use_skill 加载 | 已对齐，保留 |
| 配置管理在用户侧，模型无管理面 | 管理工具泛滥进普通模式（根因 A/B） | 需改：管理收归 CREATIVE/自定义模式 |
| 声明与审批分离（Codex） | needsApproval 独立于声明；信任文件夹按操作类型审批 | 已对齐，保持正交 |
| 缓存边界显式化 | 隐式约定（稳定前缀 + 记忆进尾部 USER） | 已对齐，P1 固化成文档约定 |
| 环境信息置尾 | `<workspace>`/`<trusted_folder>`/`<knowledge_base>` 追加在 system 后段 | 已对齐，保留顺序 |
| 空 system 有默认身份行 | 无兜底（伴生问题 E） | 需改：一行兜底 |

## 4. 框架提案（修订版）：四层裁决 + 单一出口

核心原则：**声明（tools + systemPrompt + 环境块）是门控的结果；门控是一个纯函数，只有一个出口；
提示词文本与工具存在性同生共死（Claude Code 原则）。**

### 4.1 四层裁决模型

```
effective(family) = policy.allows(family)        // L3 模式：白名单上限（只做裁剪，不判可用）
                 && settings.globalEnabled(family) // L1 全局：总开关（全局关 → 任何助手不可用）
                 && assistant.optIn(family)        // L2 助手：使用意图（绑定/启用/开关）
                 && runtime.ready(family)          // L4 运行时：连接状态/绑定解析/Shizuku
```

- **L3 模式只回答「这个模式最多允许什么」**，不再混入可用性判断；`restrictedCapabilities`
  升级为「effective 相对 policy 的差集 + 原因」，设置页与生成链路共用同一结果。
- **L2 助手层补齐缺口**：管理能力不设助手级授权开关（见 §5.1 决策），管理归 CREATIVE/自定义
  模式；助手层只保留「使用意图」字段（现有 mcpServers/enabledSkills/workspaceId +
  新增 trustedFolderProjectId）。
- **L4 运行时就绪归位**：MCP 连接状态、信任文件夹绑定解析、Shizuku 就绪统一进 ready()，
  「声明了但没连上」的工具不再出现。

### 4.2 能力注册表：文字与门控同源

把每个 Capability 从「枚举值」升级为「描述符」（单一数据源）：

```kotlin
data class CapabilityDescriptor(
    val capability: Capability,
    val tools: (deps) -> List<Tool>,            // ChatService buildList 各分支迁入
    val systemPrompt: (...) -> String,          // 现散落在各 Tool.systemPrompt 的自述迁入
    val envBlock: (...) -> String? = { null },  // <workspace>/<trusted_folder>/<knowledge_base>
    val reminder: InputMessageTransformer? = null,
)
```

生成链路变成：`effectiveCapabilities.map { descriptor[it] }.flatMap { 注入 }`。
收益：

- 文本与门控不可能漂移（根因 B 的「提到 use_skill 但未注册」这类问题整类消失）；
- ChatService 1837-2040 的 200 行 buildList 收敛为一张表；
- 子代理（SubAgentToolAssembler 白名单）、群聊（DiscussionToolAssembler）两条并行装配路径
  可复用同一注册表，消除第三套门控词汇；
- 行为层 Tool Groups、PromptMetrics 工具族统计已共享 classifyToolFamily，注册表与其对齐。

### 4.3 系统提示词层级规范化（对齐 Claude Code 拼装序）

目标顺序（在 GenerationPrompts.kt 顶部 KDoc 固化，改动需同步 PROMPT_REVISION）：

```
[1 身份层]   助手提示词（或会话重写）；身份层与用户层全空时注入一行身份兜底（终稿见 §7.4）
[2 用户层]   用户资料（稳定段）
[3 能力层]   仅 effective 能力的自述（来自注册表；禁止描述未注入的工具）
[4 行为层]   mode section + Plan&Act + Tool Groups + Ask User
[5 环境层]   <workspace> → <trusted_folder> → <knowledge_base>（仅 effective）
[6 注入层]   模式注入/lorebook BEFORE/AFTER → 占位符
[7 记忆层]   尾部 USER 消息 <memories>（维持现状，护缓存）
```

关键规则：**第 3 层只能出现 effective 集合内的能力**。管理类叙述（“你可以管理 MCP 服务器”）
本质是能力层内容，必须与 mcp_admin_* 工具同生共死。

## 5. 决策记录与影响

### 5.1 管理授权：不引入助手级开关，管理归模式

原方案的 `allowMcpManagement` / `allowSkillManagement` 助手级授权开关**不再引入**。理由：

- 主流 agent（Claude Code / Codex）均无「模型自管理配置」面：MCP 配置、技能启停全是用户侧
  操作；本项目的 CREATIVE 管理模式 + 自定义模式已经承担「AI 代管配置」场景；
- 开关方案要在「授权默认值」上二选一（默认关牺牲对话内自举，默认开保不住隔离），而模式方案
  没有这个问题：普通/跟随模式永远干净，需要代管时切管理模式，一次切换表达完整授权意图。

**影响面**：跟随助手配置/标准/极简/工作区模式下，AI 不再能列出、增删、启停 MCP 服务器或技能；
对话内首次配置 MCP 必须切管理模式或走设置页。MCP 工具使用（mcp__*）与 use_skill 不受影响。

### 5.2 信任文件夹：助手级绑定（已拍板）

- 语义：**绑定优先 + 未绑定不注入**。`Assistant.trustedFolderProjectId: String?` 对齐
  `workspaceId` 模式；不同助手可绑定并激活不同文件夹。
- 职责划分：设置页（扩展管理）只负责**文件夹库管理**（添加/改名/删除/审批开关等项目级配置）；
  **激活**收敛到助手设置——绑定即激活，解绑即停用。
- 依赖调整：`TrustedFolderRepository` 增加「按助手解析当前项目」入口；工具执行体、
  `<trusted_folder>` 环境块、审批判定均改读助手绑定；`TrustedFolderSettings.activeProjectId`
  全局激活字段退役。
- 迁移：升级后不做自动迁移（不把旧全局激活复制给所有助手，避免复现「全员生效」）；当前全局
  激活项目提示用户在助手设置中重新绑定。AgentConfig 导入导出同步新字段。

### 5.3 UNRESTRICTED 收紧：执行，影响仅 DEVICE_TOOLS

「跟随助手配置」从「全部非 managementOnly」收紧为 `DEFAULT_CAPABILITIES + WORKSPACE +
TRUSTED_FOLDER + SKILL_USE`。在 SKILL_ADMIN/MCP_ADMIN 标记 managementOnly（P0）之后，
与维持现状相比**唯一差异是 DEVICE_TOOLS**：设备诊断/存储清理/冻结应用不再出现在跟随模式下，
需要时走管理模式或含 DEVICE_TOOLS 的自定义模式。语义上「设备级侵入操作应经显式模式选择」，
采纳收紧。

## 6. 分期落地建议（按风险排序）

### P0 止血（小 diff，直接消除用户可见症状）

1. `SKILL_ADMIN`、`MCP_ADMIN` 标记 `managementOnly = true` → UNRESTRICTED 及所有普通模式
   不再携带管理声明；管理模式与显式包含它们的自定义模式不受影响。
2. UNRESTRICTED 按 §5.3 收紧（同文件同 diff）。
3. 拆分 SkillsTools 的 systemPrompt：`use_skill` 挂「使用面」提示词（含 `<enabled_skills>`），
   `skill_admin_*` 挂「管理面」提示词；未注册 use_skill 时管理面文本不再提及它
   （终稿见 §7.1/§7.2）。

### P1 单一出口重构

4. 落地 §4.1 effective 解析函数 + §4.2 注册表，ChatService/FilesPicker 全部改走单一出口；
   `restrictedCapabilities` 改为 effective 差集的展示视图。
5. 空 system 身份兜底（终稿见 §7.4）。
6. 系统提示词层级 KDoc 固化 + PROMPT_REVISION 升级。

### P2 作用域对齐

7. 信任文件夹助手级绑定（§5.2）：Assistant 字段、助手详情/选择面板、Repository 按助手解析、
   全局 activeProjectId 退役、AgentConfig 导入导出同步。
8. 行为层 Tool Groups 与注册表同源；子代理/群聊装配路径迁移复用。

### 回归锚点

- 「空助手 + 跟随助手配置 + 问『你有什么能力』」的 system 快照测试（固定应答面：不得出现
  MCP 管理/技能管理叙述）；
- 各模式 × 各助手配置矩阵的 tools 快照（能力集合 → canonicalToolOrder 后的 names 列表）；
- PROMPT_REVISION 指纹测试同步升级。

## 7. 提示词终稿（落地时直接使用，不带过程注释）

### 7.1 use_skill（SKILL_USE，仅在助手有已启用技能时注册）

```text
**Skills**
The following skills are enabled for the current assistant. Load one with `use_skill` when the user's request matches:
<enabled_skills>
  <skill>
    <name>{skill.name}</name>
    <description>{skill.description}</description>
  </skill>
</enabled_skills>
```

### 7.2 skill_admin_list（SKILL_ADMIN，管理模式）

```text
**Skill Management**
You can inspect all installed skills with `skill_admin_list` and enable or disable them for the current assistant with `skill_admin_set_enabled`. Changes take effect from the next message.
```

（`skill_admin_set_enabled` 不再挂 systemPrompt，避免与 list 重复注入后又被去重掩盖语义归属。）

### 7.3 mcp_admin_list（MCP_ADMIN，管理模式）

```text
**MCP Server Management**
You can manage MCP (Model Context Protocol) server configurations using the `mcp_admin_*` tools. Call `mcp_admin_list` first to see all configured MCP servers with their id, name, transport, url, status, and whether each is enabled for the current assistant; then operate by server id with `mcp_admin_get`, `mcp_admin_update`, `mcp_admin_delete`, or `mcp_admin_assistant_set_enabled`, and use `mcp_admin_add` / `mcp_admin_test` to add or test a server.
Only servers enabled for the current assistant expose their tools directly to the model (as `mcp__*` function tools). Call `mcp_admin_list` to discover and configure the rest.
```

（维持现文不变，收录于此作为终稿基线。）

### 7.4 空身份兜底（P1，仅当助手提示词与会话重写均空白且用户资料未启用时注入）

```text
You are RikkaHub, a personal AI assistant running on the user's device. Help with the user's requests and use the available tools when they add value.
```

## 8. 涉及文件索引

| 文件 | 角色 |
|---|---|
| `app/.../data/model/ChatMode.kt` | 模式枚举/能力清单/UNRESTRICTED/策略解析（根因 A、§5.3） |
| `app/.../service/ChatService.kt:1771-2040` | 模式解析 + 工具装配 ad-hoc 门控（根因 B/C 现场） |
| `app/.../data/ai/GenerationHandler.kt:590-679` | system 堆叠（§4.3 顺序的实现） |
| `app/.../data/ai/GenerationPrompts.kt:172-179` | buildToolSystemPrompts 去重注入 |
| `app/.../data/ai/tools/McpManagerTools.kt` | mcp_admin_* + 管理自述（根因 B，终稿 §7.3） |
| `app/.../data/ai/tools/SkillsTools.kt` | skill_admin_* + 管理自述（根因 B，终稿 §7.1/§7.2） |
| `app/.../data/ai/prompts/AgentBehaviorPrompt.kt` | 行为层 + Tool Groups |
| `app/.../data/trustedfolders/TrustedFolderStore.kt` | 全局 activeProjectId（§5.2 退役对象） |
| `app/.../ui/pages/setting/SettingModePage.kt:361,429` | restrictedCapabilities 唯一消费点（根因 C） |
| `app/.../data/model/Assistant.kt` | 助手级配置载体（L2 扩展点：trustedFolderProjectId） |

### 参考资料

- How Claude Code Builds a System Prompt（dbreunig, 2026-04-04）
  https://www.dbreunig.com/2026/04/04/how-claude-code-builds-a-system-prompt.html
- Equipping agents for the real world with Agent Skills（Anthropic Engineering）
  https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
- Agent Skills Specification（progressive disclosure）
  https://agentskills.io/specification
- Codex CLI Configuration Reference（sandbox / approval / tools）
  https://learn.chatgpt.com/docs/config-file/config-reference

## 9. 实施记录（2026-08-29）

P0/P1/P2 已全部落地，`:app:compileDebugKotlin` 与 `:app:testDebugUnitTest`（全量）通过。

### 已实施

| 项 | 内容 | 位置 |
|---|---|---|
| 管理能力归模式 | `SKILL_ADMIN`/`MCP_ADMIN` 标记 `managementOnly = true` | ChatMode.kt |
| UNRESTRICTED 收紧 | 显式集合 = DEFAULT + WORKSPACE + TRUSTED_FOLDER + SKILL_USE（DEVICE_TOOLS 出局，仅管理模式/自定义模式可达） | ChatMode.kt |
| 单一出口 | `ChatModePolicy.withAvailability(assistant, settings, skillsInstalled, trustedFolderBound)` 折叠助手/全局/运行时声明级可用性；`restrictedCapabilities` 改为其差集的 UI 近似视图 | ChatMode.kt |
| ChatService 收敛 | 模式解析后即做 withAvailability，tools/transformers 门控全部只读 effective 策略，删除 buildList 内逐族重复条件（todo/subagent/study/search/history/knowledge）；工厂内就绪检查（MCP 连接/Shizuku/rootfs）保留为 L4 兜底 | ChatService.kt |
| Skills 提示词拆分 | use_skill 挂使用面（`<enabled_skills>`，不提管理工具）；skill_admin_list 挂管理面；skill_admin_set_enabled 不再挂提示词（§7.1/7.2 终稿） | SkillsTools.kt |
| 空身份兜底 | 身份层与用户层全空时注入 `BASE_IDENTITY_PROMPT`（§7.4 终稿）；层级约定固化进 GenerationPrompts KDoc；PROMPT_REVISION → 2026-08-29-v1 | GenerationPrompts.kt / GenerationHandler.kt |
| 信任文件夹助手级绑定 | `Assistant.trustedFolderProjectId`（绑定即激活）；Repository 全局激活退役、文件操作 projectId 必传、approvalNeeded 按项目；AI 工具/`<trusted_folder>` 环境块按助手绑定注入；管理工具 activate/deactivate → bind/unbind（对齐 workspace_admin 语义）；设置页降为纯库管理（去激活分区/按钮/确认弹窗）；助手工具页新增绑定 Select；AgentConfig 导入导出同步字段 | 全链路（§8 文件索引） |
| 死参清理 | `resolveModeRef` / `resolveConversationPolicy` 移除 `trustedFolderActive` | ChatMode.kt |
| 测试 | ChatModeTest：UNRESTRICTED 新语义 + 空助手回归锚点 + 绑定悬空扣除；SkillsToolsTest：提示词同源断言；PromptRevisionTest：指纹纳入 BASE_IDENTITY_PROMPT（5d9cbda526eb04be） | app/src/test |

### 与原方案的偏差

1. **§4.2 能力描述符注册表未在本轮实施**：单一出口由 `withAvailability` + ChatService 统一消费达成；文字同源对问题最重的 SkillsTools 先行落地。注册表（把 buildList 各分支迁入 `CapabilityDescriptor.tools`）留作后续重构，收益是新增能力时免改 ChatService。
2. **restrictedCapabilities 保持 UI 近似**：设置页无法拿到技能安装事实，按 `skillsInstalled = true`、`trustedFolderBound = 绑定非空` 计算；生成链路用真实事实。
3. **子代理/群聊装配路径未迁移**：SubAgentToolAssembler 白名单与 DiscussionToolAssembler 维持原状，注册表落地时一并迁移。
4. **TrustedFolderSelectSheet 删除**：无调用方的孤儿组件，激活语义已改为助手级绑定。
5. **旧全局激活不迁移**：DataStore 中旧 `active_project_id` key 休眠保留，用户在助手设置中重新绑定。

### 验证

- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` 通过
- `:app:testDebugUnitTest` 全量通过（含 ChatModeTest / SkillsToolsTest / PromptRevisionTest / ModeToolFilterTest / ToolDescriptionConventionTest / ManagementToolsTest / AgentConfig* / SettingsSyncCodecTest）
- 回归锚点：空助手 + 跟随助手配置的 effective 能力集仅含 LOCAL_TOOLS/DOCUMENT/TODO/TOOL_SYSTEM_PROMPT/AGENT_BEHAVIOR_PROMPT 等基础项，任何管理/使用面声明不再出现


### 第二轮实施（2026-08-29，审计修复 + P1/P2/P3 优化）

全链路审计（系统消息七层 + 工具声明 + 门控链）通过后落地的优化；`:app:testDebugUnitTest` 全量 609 用例通过。

| 项 | 内容 | 位置 |
|---|---|---|
| P1 审批缺口补齐 | `mcp_admin_add` / `mcp_admin_update` / `mcp_admin_assistant_set_enabled` 补 `needsApproval = { true }`，对齐 provider/search/settings 写操作审批面（add 自动连接任意 URL、update 可重定向既有 server，属管理面外延风险最高写操作） | McpManagerTools.kt |
| P2 注册表落地 | 新建 `ChatToolRegistry`：Capability → 工厂条目单一映射点，收编 ChatService.handleMessage 原 buildList 15 分支；条目可声明多能力（skill 条目按 SKILL_USE/SKILL_ADMIN 内部细分）；MCP 非法服务器名经 `McpServerNameInvalidException` 上抛、ChatService 捕获保持「报错并中止本轮」语义；知识库装配依赖检索增强管线（改写/HyDE/MultiQuery，复用 ChatService 后台生成设施），经 `KnowledgeToolFactory` 接缝注入，工厂本体不搬迁 | ChatToolRegistry.kt / ChatService.kt |
| P2 管理段动态化 | `managementSection(tools)` 按本轮实际注入点名 `admin_inventory` + 已注入 `*_list`，部分管理能力的自定义模式不再引用不存在的工具（偏差 3 消除）；`settings_admin_list`/`audit_list`/`mode_list` 一并纳入；PROMPT_REVISION → 2026-08-29-v2（与并行工作流的记忆块增强同批） | AgentBehaviorPrompt.kt |
| P2 knowledgeReady | `withAvailability` 新增 `knowledgeReady` 参数：绑定知识库全部被删（id 悬空）时扣除 KNOWLEDGE，不再注入指向空库的环境块；ChatService 按库存在性实测传入 | ChatMode.kt / ChatService.kt |
| P3 能用/能管理解耦 | `MCP_USE` 只看助手 `mcpServers` 绑定；全局 `enableMcpManager` 仅授权 `MCP_ADMIN`——关管理开关不再静默禁用已配置服务器的 mcp__* 使用；UI 语义跟随（助手摘要 MCP 计数去开关条件、绑定入口提示恒为「绑定外部 MCP 服务」） | ChatMode.kt / AssistantDetailPage.kt / AssistantToolsPage.kt |
| P2 体积护栏 | 新建 `PromptBudgetTest`：空助手+跟随模式基线 ≤1500 字符、能力词（mcp/skill/workspace/trusted folder/knowledge/management/admin/sub-agent）防泄漏断言、五行为档分档预算、管理段动态点名断言——与 PromptRevisionTest 指纹（变了要知情）互补（膨胀要拦截） | app/src/test |
| 测试 | ChatModeTest +2：`mcpUseDecoupledFromManagerSwitch`（开关关 + 已绑定 → 使用保留/管理扣除；未绑定 → 使用扣除）、`knowledgeRestrictedWhenNoBoundBaseExists`；AgentBehaviorPromptTest 空工具集断言更新（空集不点名 admin 工具）；PromptRevisionTest 指纹纳入四行为档 + MEMORY_TOOL_SYSTEM_PROMPT + MEMORY_CONTEXT_POLICY_LINES（e50a0e7b0d86f759，含并行工作流记忆块增强） | app/src/test |

剩余偏差：子代理（SubAgentToolAssembler）/群聊（DiscussionToolAssembler）装配路径仍为显式白名单，未迁移注册表（白名单结构上不可能产出管理工具，风险低，留待后续）。
