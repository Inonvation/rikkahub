# Agent 配置统一格式 + AI 可读取 · 只读 MVP 设计

> 状态：**设计定稿，核心代码已实现**（编译验证进行中） · 适用范围：配置格式统一 + 管理模式 AI 只读通道
> 关联代码：`PreferencesStore.kt`（Settings 聚合/DataStore）、`ManagementTools.kt`（管理模式工具）、`SkillManager.kt` / `FileFolders.kt`（Skill 文件化）、`SettingsSyncCodec.kt`（JSON 白名单/密钥剔除先例）、`WebDavSync.kt` / `S3Sync.kt`（备份）
> 决策：路线甲（文件为源）+ 首期只读 MVP（用户已确认）
> 实施：`data/config/`（模型/路径/导出/门面）+ `data/ai/tools/AgentConfigTools.kt` + DI/装配，见文末「实施记录」

---

## 〇、目标

把 App 的配置（Provider / MCP / Assistant / 策略 / Skill / 工作区）统一成**一套声明式格式**，让软件内置 AI（管理模式）可以：

1. **读取**：看到一致结构的配置视图（而不是散落的 Kotlin 对象/数量摘要）；
2. **（后续）配置**：通过统一仓库读写，设置页、AI 管理模式、导入功能走同一套逻辑。

MVP 只做**读取通道 + 格式先行**：文件尚不是唯一来源（权威源仍是 DataStore），但格式就是未来文件为源的规范格式，先让 AI 用真实数据验证 schema 是否好用，再决定是否推进存储迁移。

## 一、现状盘点（证据）

| 事实 | 位置 |
|---|---|
| 全部配置在 DataStore `settings`（`data_version=7`，V1–V7 迁移链） | `PreferencesStore.kt` |
| `Settings` 聚合含 providers / assistants / mcpServers / searchServices / ttsProviders / asrProviders / modeInjections / lorebooks / quickMessages / customModes / skillOrder / 同步 / WebServer / 子代理等 | `PreferencesStore.kt:851` 起 |
| `ProviderSetting` / `McpServerConfig` / `Assistant` 均 `@Serializable`，可整包 JSON | `ai/.../ProviderSetting.kt`、`McpConfig.kt`、`Assistant.kt` |
| 云同步已有 JSON 白名单 + 密钥剔除（`ALLOWLIST` + `SECRET_KEYS`） | `SettingsSyncCodec.kt` |
| 管理模式工具已存在（`admin_inventory` 等约 35 个，含 mcp/skill/workspace admin 工具），但 `admin_inventory` 只输出数量摘要 | `ManagementTools.kt:1306` |
| 管理模式已有审计与回滚（`ManagementAuditStore` / `ManagementRollbackStore`），写工具可复用 | `app/.../data/management/` |
| Skill 已文件化：`filesDir/skills/<name>/SKILL.md`，workspace 挂载 `/skills` 只读 | `FileFolders.kt`、`SkillManager.kt`、`RepositoryModule.kt:70` |
| 工作区沙盒 `filesDir/workspaces/<id>` + `.agent/`（AGENTS.md/MEMORY.md/INDEX.md，应用注入、AI 可编辑） | `WorkspaceRepository.kt` |
| 密钥（apiKey / oauth token / codexCredentials）明文在 DataStore JSON；云同步剔除，**本地备份 zip 全量含密钥** | `WebDavSync.kt:239`、`S3Sync.kt` |
| 仓库无任何 Keystore / EncryptedSharedPreferences 用法 | grep 零命中 |

## 二、统一格式草案（schema v0 → v1）

**格式约定**：对齐当前主流 AI 客户端/Agent 配置惯例——
- **JSON 用 camelCase**（与 Claude Code `settings.json`、MCP 配置、Cherry Studio 导出、OpenAI/Anthropic SDK 参数一致），`kotlinx.serialization` 直接编解码，schema 可校验；
- **策略/技能用 Markdown**（AGENTS.md / CLAUDE.md / SKILL.md 惯例）；
- 每个配置文件自带 `schemaVersion`，`manifest.json` 记录全局版本与导出状态（迁移钩子，参照 DataStore V1–V7 迁移链）。

```
filesDir/agent/
├── manifest.json                # schemaVersion / 来源 / 导出时间 / settingsDataVersion
├── config/
│   ├── providers.json           # 全局 Provider（密钥 = 引用占位，绝不落明文）
│   ├── mcp.json                 # 全局 MCP Server（headers/oauth 剔除，仅保留启用状态）
│   └── assistants/
│       └── <assistant-id>.json  # 每助手一文件（模型引用 providerId:modelId）
├── policies/
│   ├── global.md                # 全局策略（MVP 只导出占位，P3 生效）
│   └── management.md            # 管理模式行为策略（P3 生效）
└── state/                       # 后续阶段
    ├── enabled-skills.json
    └── revisions.json
```

MVP 范围：`manifest.json` + `config/providers.json` + `config/mcp.json` + `config/assistants/<id>.json`。Skill 已是文件格式（`SKILL.md`），本轮不动；`search/modes/lorebooks` 视需要追加，原则是**同类配置同 schema 演进，不重复造格式**。

### manifest.json

```json
{
  "schemaVersion": 1,
  "source": "datastore",
  "settingsDataVersion": 7,
  "exportedAt": "2026-01-01T00:00:00Z",
  "files": {
    "config/providers.json": "ok",
    "config/mcp.json": "ok",
    "config/assistants/...json": "ok"
  }
}
```

`schemaVersion` 供未来 schema 迁移（照抄 DataStore V1–V7 迁移链 + `SettingsJsonMigrator` 先例）；`settingsDataVersion` 记录导出时 DataStore 数据版本。

### config/providers.json（脱敏视图 · 覆盖余额/模型/高级设置）

```json
{
  "schemaVersion": 1,
  "providers": [
    {
      "id": "3f0e...",
      "type": "openai",
      "name": "OpenAI",
      "enabled": true,
      "builtIn": true,
      "baseUrl": "https://api.openai.com/v1",
      "authType": "api_key",
      "apiKeyRef": "keystore:provider:3f0e...:apiKey",
      "balance": { "enabled": true, "apiPath": "/credits", "resultPath": "data.total_usage" },
      "modelCount": 1,
      "models": [
        {
          "id": "2a11...",
          "modelId": "gpt-4o",
          "displayName": "GPT-4o",
          "type": "chat",
          "inputModalities": ["image", "text"],
          "outputModalities": ["text"],
          "abilities": ["reasoning", "tool"],
          "builtInTools": ["search"],
          "customHeadersRef": "keystore:model:2a11...:headers",
          "providerOverwrite": { "type": "claude", "name": "nested-claude", "apiKeyRef": "keystore:provider:...:secret" }
        }
      ],
      "useResponseApi": true,
      "includeHistoryReasoning": true
    }
  ]
}
```

覆盖度说明（对应设置页可见项）：
- **Provider 级**：type/name/enabled/builtIn/baseUrl/authType/`apiKeyRef`/`balance`（余额开关、apiPath、resultPath）/ OpenAI 路径与 Response API（chatCompletionsPath/embeddingsPath/rerankPath/useResponseApi/includeHistoryReasoning）/ Claude 缓存（promptCaching/promptCacheTtl）/ Vertex（vertexAI/useServiceAccount/location/projectId）。
- **Model 级**：modelId/displayName/type、inputModalities/outputModalities/abilities/builtInTools、customHeadersRef/customBodiesRef、`providerOverwrite`（嵌套 provider 覆盖，递归脱敏、不展开 models 防膨胀）。
- 密钥类字段（apiKey/privateKey/serviceAccountEmail/codexCredentials/customHeaders 值/customBodies 值）一律只导出 `*Ref` 引用占位。

**密钥规则（硬性）**：导出时复用/抽取 `SettingsSyncCodec` 的 `SECRET_KEYS` 过滤逻辑，AI 可见视图**永不包含明文密钥**；密钥字段位置放 `*Ref` 引用占位（schema 预留，写回阶段启用，MVP 阶段占位即可）。`SECRET_KEYS` 集合需扩展：`apiKey / privateKey / serviceAccountEmail / customHeaders / headers / oauth / accessToken / refreshToken / clientSecret / client_id / client_secret / codexCredentials`。

### config/mcp.json

```json
{
  "schemaVersion": 1,
  "servers": [
    {
      "id": "b1c2...",
      "type": "streamable_http",
      "url": "https://mcp.example.com/mcp",
      "enable": true,
      "toolCount": 3,
      "oauthEnabled": true,
      "headersRef": "keystore:mcp:b1c2...:headers"
    }
  ]
}
```

`type` 取值 `sse` / `streamable_http`，对齐 MCP 配置规范与 Claude Desktop `claude_desktop_config.json`（社区同义字段：`transport`）。`McpOAuthState` 只保留 `enabled` 状态，token/secret 一律剔除。

### config/assistants/<id>.json

`Assistant` 可序列化字段直接映射（剔除敏感项），模型引用归一为 `providerId:modelId`：

```json
{
  "id": "a9f1...",
  "name": "默认助手",
  "chatModelRef": "3f0e...:2a11...",
  "systemPrompt": "...",
  "temperature": null,
  "mcpServerIds": ["b1c2..."],
  "workspaceId": null,
  "enabledSkills": ["locale-tui-localization"],
  "knowledgeBaseIds": [],
  "enableMemory": false,
  "avatar": "emoji:🤖",
  "useAssistantAvatar": true,
  "background": null,
  "contextMessageLimit": 0,
  "contextTokenLimit": 128000,
  "messageTemplate": "{{ message }}",
  "reasoningLevel": "auto",
  "regexes": [
    { "id": "...", "name": "cleanup", "enabled": true,
      "findRegex": "\\s+", "replaceString": " ",
      "affectingScope": ["user"], "visualOnly": false }
  ],
  "presetMessages": [ { "role": "user", "text": "..." } ],
  "localTools": ["calendar", "time_info"],
  "customHeadersRef": "keystore:assistant:a9f1...:headers",
  "enableTimeReminder": false,
  "allowConversationSystemPrompt": false,
  "enableKnowledgeQueryRewrite": true,
  "enabledStudyTools": ["save_note"],
  "studySubject": "english"
}
```

覆盖度说明：外观（avatar/useAssistantAvatar/background/backgroundOpacity/useGradientBackground）、上下文与生成（contextMessageLimit/contextTokenLimit/messageTemplate/reasoningLevel/regexes/presetMessages）、工具与能力（localTools/quickMessageIds/customHeadersRef/customBodiesRef/enableTimeReminder/allowConversationSystemPrompt/allowConversationPromptInjection/enableKnowledgeQueryRewrite/enabledStudyTools/studySubject/defaultWorkspaceCwd）。密钥类（customHeaders/customBodies 值）只导出引用。

## 三、装配与来源语义（MVP）

- **MVP 阶段**：DataStore 仍是唯一权威源；`AgentConfigExporter` 把当前 `Settings` 导出为 `agent/config/`（脱敏）。
- 导出时机（幂等、后台 IO）：
  1. 应用启动后（`RouteActivity` 之后、低优先级协程）；
  2. 管理模式 AI 调用 `config_refresh` 时（拿到最新值）；
  3. 设置变更后可选节流触发（MVP 可先不做，靠 refresh 兜底）。
- **导出校验**：每份 JSON 反序列化回 DTO（`JsonInstant.decodeFromString`），失败则不落盘、记日志、manifest 对应项标 `error`。
- 后续阶段（P1+）：`ConfigRepository` 门面 = 读（文件 + schema 校验）+ 写（原子写 + 单写者锁 + 快照回退），`settingsFlow` 改成装配流（DataStore 偏好 → 文件配置 → 会话覆盖），所有 UI 页面与管理工具统一走门面，Settings 聚合模型与 Flow 语义保持不变。

## 四、AI 读取通道（管理模式新增工具）

在 `ManagementTools.kt` 基础上新增 3 个只读工具（与现有 `admin_inventory` 并存，不破坏已有行为）：

| 工具 | 作用 | 输出 |
|---|---|---|
| `config_view` | 配置地图：目录结构 + 各文件 schemaVersion + 摘要（provider/mcp/assistant 计数、策略文件列表） | 文本摘要 + manifest 内容 |
| `config_read` | 按白名单路径读取某配置文件（如 `config/providers.json`） | 文件 JSON 文本（已脱敏） |
| `config_refresh` | 触发从 DataStore 重新导出 + 校验 | 校验结果（ok / 各文件错误） |

管理模式 AI 配置模型的**写入通道**（`createProviderAdminTools`，均需审批 + 审计 + 可回滚）：

| 工具 | 作用 |
|---|---|
| `provider_create` / `provider_update` | 创建/更新 Provider（基础字段 + `useResponseApi` 等） |
| `model_add` | 给 Provider 添加模型，**支持完整基本设置**：`type`（chat/image/embedding/reranking）、`abilities`（tool/reasoning）、`inputModalities`/`outputModalities`（text/image）、`builtInTools`（search/url_context/image_generation）、可选 `customHeaders` |
| `model_update` | 更新已有模型的上述基本设置（省略字段保留现值） |
| `provider_get` | 详情输出已增强：逐模型显示 abilities / input/output modalities / builtInTools / customHeaders 数量，AI 可据此感知现状 |

约束：
- 路径白名单：只允许 `agent/` 目录下文件，canonical path 校验（照抄 `SkillPaths.resolveSkillDir` 的 `canonicalRoot` 防御），防目录穿越。
- 只读工具无需审批；未来写工具（P1）复用 `ManagementAuditStore` / `ManagementRollbackStore` 做审计与回滚。
- `AgentBehaviorPrompt` 的模式描述里补充一行：管理模式可通过 `config_view` / `config_read` 查看统一配置视图（P3 由 `policies/management.md` 文件接管这段提示词）。

## 五、安全与校验清单

1. 明文密钥永不进入 `agent/` 目录（导出过滤 + 单元测试断言）。
2. JSON 合法性：导出即 decode 校验，坏文件不落盘。
3. 路径穿越防御：白名单 + canonical 校验。
4. `manifest.json` 记录 schema 与数据版本，为迁移留钩子。
5. 并发：MVP 只有导出（写 `agent/` 由 exporter 独占 + 节流），无多写者；P1 引入单写者锁（`Mutex`）+ `AtomicFile`。

## 六、分阶段路线（更新版）

| 阶段 | 内容 | 交付验证 |
|---|---|---|
| **MVP（本次）** | schema v0 + `AgentConfigExporter`（DataStore→文件，脱敏）+ `config_view` / `config_read` / `config_refresh` + 校验 + manifest | 管理模式 AI 能描述配置结构、读取具体配置且**不含密钥** |
| P1 | 文件为源：`ConfigRepository` 写路径（原子写+锁+回退），UI/管理工具统一走门面；DataStore 只存偏好/运行时 | 设置页改动后文件同步更新，AI 读到最新 |
| P2 | 密钥上 Keystore（AES-GCM + 引用化）；备份/恢复语义调整（备份不含密钥，恢复后提示重新填写） | 备份 zip 无密钥；换机恢复流程可用 |
| P3 | 工作区层 providers/mcp 合并语义（全局兜底+工作区覆盖、不复制密钥）；Skill 迁 `agent/skills` 保留 `/skills` 兼容别名（只改宿主源目录、挂载点不变）；`policies/*.md` 生效 | 工作区 AI 与全局配置隔离正确 |

## 七、主要风险

1. **回归面**：`Settings` 被全 App 引用，存储替换必须保持模型与 `settingsFlow` 语义（含 `dummy(init=true)` 两阶段加载时序）→ MVP 不碰写路径，风险隔离。
2. **双源分叉**：DataStore 与文件并存 → MVP 明确 DataStore 为源、文件为导出物；P1 单向迁移，不做双向同步。
3. **备份语义变化**：密钥离开备份 → P2 改恢复 UX（缺密钥提示）。
4. **schema 演进**：AI 读到旧格式 → `schemaVersion` + `config_view` 显示版本，迁移逻辑照抄 DataStore 先例。

## 八、与开源生态对齐（借鉴清单）

配置格式不是自造的，而是对照主流开源/官方实现对齐，避免重复造轮子：

| 本方案 | 借鉴来源 | 对齐内容 |
|---|---|---|
| `SKILL.md` + YAML frontmatter（name/description/compatibility/**allowed-tools**） | [Anthropic Agent Skills](https://claude.com/docs/skills/how-to#1) / [Agent Skills Metadata Schema](https://mintlify.wiki/anthropics/skills/spec/metadata-schema) | 现有 `SkillFrontmatterParser`（SnakeYAML SafeConstructor）即按此规范解析；本轮补上 `allowed-tools` 工具白名单字段 |
| `mcp.json` 的 `type: sse/streamable_http` + `url` + `headers` | [MCP 配置规范](https://github.com/cloudstreet-dev/MCP-Model-Context-Protocol/blob/main/src/12-configuration.md)、Claude Desktop `claude_desktop_config.json` | 字段名与社区一致（`type` 而非自定义 `transport`）；headers/oauth 引用化 |
| `providers.json` camelCase + `apiKeyRef`/`baseUrl`/`models[]` | [CherryHQ/cherry-studio](https://github.com/CherryHQ/cherry-studio) 的 provider 导出、Claude Code `settings.json`（[完整参考](https://www.morphllm.com/claude-code-settings-json)） | camelCase JSON；apiKey→引用占位（对应 Claude Code 的密钥外置）；`baseUrl` 与 OpenAI SDK 一致 |
| JSON Schema 校验文件 | Claude Code 官方 `settings.schema.json` | 内置 `app/src/main/assets/agent/schema/*.schema.json`（draft-07），编辑器/AI/CI 可直接校验 |
| 策略/规则用 Markdown | AGENTS.md / CLAUDE.md 惯例 | `policies/global.md`、`policies/management.md`（P3 生效） |

差异说明：Cherry Studio 用 `apiHost`，我们用 `baseUrl`（OpenAI SDK / Claude Code 同义，更通用）；MCP 仅支持远程（sse/streamable_http），暂无 stdio 本地进程 server（对应 `command/args` 形态），后续按需扩展。

## 九、验证方式

- 单元测试：`AgentConfigExporterTest`（导出→decode 成功；遍历 JSON 断言无 `apiKey/accessToken/refreshToken/clientSecret` 等密钥字段）、路径白名单测试、`SkillFrontmatterParserTest`（allowed-tools 解析）。
- 手动验证：管理模式对话"查看我的提供商配置/有哪些 MCP 服务器"，AI 调用 `config_view` + `config_read` 并正确总结，输出中无密钥。
- 打包验证：`./gradlew :app:assembleDebug` + 覆盖安装（`adb install -r`）。

---

## 十、实施记录（只读 MVP）

| 步骤 | 内容 | 状态 |
|---|---|---|
| P1 | schema v0 DTO + manifest（`data/config/AgentConfigModels.kt`） | ✅ |
| P2 | 目录常量 + 路径白名单（`data/config/AgentConfigPaths.kt`，canonical 防穿越） | ✅ |
| P3 | 导出器：DataStore → agent/config/*.json，显式 DTO 映射 + 原子写 + 逐文件校验（`data/config/AgentConfigExporter.kt`） | ✅ |
| P4 | 只读门面 `view()` / `readConfigFile()`（`data/config/AgentConfigRepository.kt`） | ✅ |
| P5 | 管理模式工具 `config_view` / `config_read` / `config_refresh`（`data/ai/tools/AgentConfigTools.kt`），装配进 ChatService 管理模式工具块 | ✅ |
| P6 | DI：`RepositoryModule` 注册 `AgentConfigRepository(filesDir/agent)`；`ChatService` 注入 | ✅ |
| P7 | 单元测试 `AgentConfigExporterTest`（格式/脱敏/manifest/模型引用/白名单） | ✅ |
| P8 | 编译验证（`:app:compileDebugUnitTestKotlin`，跳过 `:web:preBuild`） | ✅ BUILD SUCCESSFUL |
| P8b | 单元测试 `:app:testDebugUnitTest --tests me.rerere.rikkahub.data.config.AgentConfigExporterTest` | ✅ 5/5 通过（failures=0, errors=0, skipped=0） |
| P8c | **schema 覆盖扩展**：Provider 余额+高级设置、Model 基本/高级设置+嵌套覆盖、Assistant 可见配置项（外观/上下文/正则/预设消息/本地工具/学习工具）；测试升级为 decode 后断言 | ✅ 6/6 通过 |
| P8d | 重新构建 + 覆盖安装（`assembleDebug` + `adb install -r`） | ✅ 待设备验证 |
| P8e | **开源对齐**：JSON Schema 内置（`assets/agent/schema/*.schema.json`，借鉴 Claude Code settings.schema.json）；MCP 字段 `transport`→`type`（对齐 MCP 规范/Claude Desktop）；Skill 补 `allowed-tools`（Anthropic Agent Skills）+ `SkillFrontmatterParserTest` | ✅ |
| P9 | **模型级写入通道**：`model_add` / `model_update` 工具（type/abilities/modalities/builtInTools/customHeaders，审批+审计+回滚）；`provider_get` 详情输出模型能力；解析函数单测 | ✅ 16/16 测试通过 |
| P9b | **Provider 高级设置写入**：`provider_update` 支持 balance（余额）/ OpenAI 路径与 includeHistoryReasoning / Claude promptCaching+promptCacheTtl / Google vertexAI+useServiceAccount+location+projectId | ✅ 18/18 测试通过 |
| P10 | **管理控制台展示**：`ManagementPage` 资源组新增「Agent 配置」入口；新页面 `AgentConfigPage`（导出状态 + 刷新导出 + 文件列表 + 脱敏 JSON 只读查看），复用 `AgentConfigRepository` | ✅ 编译通过，APK 已构建（设备离线，待重连安装） |
| P11 | **终审健壮性修复**：导出加 `synchronized` 互斥（防页面/AI 并发写 .tmp）；写失败清理残留；Claude promptCacheTtl 归一导出 "5m"；config 工具 execute 包 `withContext(IO)`；VM/管理页文件 IO 移出主线程；文件 Dialog 加滚动 | ✅ 全量 470/470 测试通过 |
| P12 | **五项优化（用户确认全做）**：① `model_add` 智能默认（modelId 推断 type/abilities/modalities）；② `SecretStore` Keystore AES-GCM 加密层（`AndroidSecretStore` + `SecretRefs` 引用规范，exporter 统一引用）；③ `config_schema`/`config_validate` 工具 + `AgentConfigAutoSync` 设置变更 5s 节流自动导出；④ 写路径 `writeConfigFile`（原子+锁+快照+revisions）+ `AgentConfigImporter`（文件→DataStore 合并，密钥保留本地）+ 文件页「编辑/保存/应用到设置」；⑤ `AgentConfigArchive` zip 导出/导入（SAF，白名单防穿越）+ 管理控制台入口 | ✅ 全量测试通过 |
| P13 | **最终完整审核（逻辑链闭环）**：① Importer 补全助手字段反向映射（chatModelRef/avatar/useAssistantAvatar/tags/background/backgroundOpacity/useGradientBackground/reasoningLevel/regexes/presetMessages/localTools/quickMessageIds/defaultWorkspaceCwd，此前导出有字段但「应用到设置」不生效）；② 单文件「应用到设置」只应用当前助手（`applyAssistants(onlyAssistantId)`），避免整目录互相覆盖；③ MCP DTO 补 `name` 字段（导出/导入闭环）+ schema 同步；④ 助手合并改为**字段存在性判断**（文件缺失字段保留本地值，不再被 DTO 默认值误重置）；⑤ `Repository.view()` 排除 `backups/*.bak` 快照（AI `config_view` 与管理页不再出现噪音）；⑥ `config_validate` 增加 manifest 校验；⑦ 损坏 zip 返回 -1（魔数校验，不再误报"导入 0 个"成功）；⑧ 文件页保存前 JSON 语法校验（防坏文件）；⑨ 管理控制台布局优化（文件状态圆点 ok/error/untracked、组标题计数、助手按名排序、导出/导入并入状态组、字符串资源化） | ✅ 全量测试通过 |
| P14 | **配置闭环三项**：③ `config_write` AI 直写工具（写文件→快照+修订→校验→可选 applyToSettings 合并回 DataStore，全程审计+可回滚；核心逻辑拆为 `writeAgentConfigFile`/`applyFileToSettings` 纯函数便于单测）；④ 文件页「历史」弹窗（backups 快照一键回退 + revisions 修订记录，`Repository.listBackups/readBackup/revisions`，防穿越）；⑤ 脏标记（`AgentConfigFileInfo.dirty`：文件 mtime 严格晚于 manifest 即视为导出后被手动修改；文件页顶部横幅提示 + 管理页状态点警示色） | ✅ 全量测试通过 |
| P15 | **审批卡片目的说明**：`UIMessagePart.Tool` 新增 `description` 字段（默认空、旧数据向后兼容），`GenerationHandler` 生成工具调用时从 Tool 定义填充；审批等待态气泡标题下直接显示工具目的说明（`toolApprovalPurpose`：高频审批工具中文映射 + 参数摘要——config_write 显示路径与"同步到设置"、model_add 显示模型名；未注册工具回退英文描述）；详情 BottomSheet 同步展示 | ✅ 全量测试通过 |
| P9 | 真机/管理模式手动验证（对话里让 AI 查看配置） | 待做 |
| P10 | 设置页回归确认（导出为纯旁路，不触碰 DataStore 写路径） | 待做 |

**与用户自行设置的关系**：MVP 完全不改设置页与 DataStore 写路径——`AgentConfigExporter` 只读 `settingsFlow` 并导出脱敏副本，`config_refresh` 也仅重导；任何手动设置照常生效，AI 下次 refresh 即见新视图。
