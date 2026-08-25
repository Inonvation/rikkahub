package me.rerere.rikkahub.data.config

import kotlinx.serialization.Serializable

/**
 * Agent 统一配置格式（schema v0 → v1）的 DTO 层。
 *
 * 格式约定（对齐当前主流 AI 客户端/Agent 配置惯例）：
 * - JSON 用 camelCase（与 Claude Code `settings.json`、MCP 配置、Cherry Studio 导出、
 *   OpenAI/Anthropic SDK 参数一致）；策略/技能用 Markdown（AGENTS.md / CLAUDE.md / SKILL.md 惯例）。
 * - 只承载**非敏感**字段；密钥一律以 `*Ref` 引用占位（如
 *   `keystore:provider:<id>:secret`），明文永不写入 agent/ 目录。
 * - schemaVersion 为迁移留钩子（参照 DataStore V1–V7 迁移链先例）。
 * - 只读 MVP 阶段：文件由 [AgentConfigExporter] 从 DataStore 导出，是
 *   AI 的统一视图，尚不是唯一来源；设置页继续直写 DataStore，互不影响。
 */
const val AGENT_CONFIG_SCHEMA_VERSION = 1

/** agent/manifest.json —— 目录版本与导出元信息。 */
@Serializable
data class AgentManifest(
    val schemaVersion: Int = AGENT_CONFIG_SCHEMA_VERSION,
    /** 配置来源，MVP 固定 "datastore"；文件为源后改为 "file" */
    val source: String = "datastore",
    /** 导出时 DataStore 的数据版本（SettingsStore.CURRENT_DATA_VERSION） */
    val settingsDataVersion: Int = 0,
    /** 导出时间（epoch millis） */
    val exportedAt: Long = 0L,
    /** 相对路径 -> 导出校验状态（"ok" / "error: ..."） */
    val files: Map<String, String> = emptyMap(),
)

/** config/providers.json */
@Serializable
data class ProviderConfigFile(
    val schemaVersion: Int = AGENT_CONFIG_SCHEMA_VERSION,
    val providers: List<ProviderConfigDto> = emptyList(),
)

@Serializable
data class ProviderConfigDto(
    val id: String,
    val type: String, // openai / google / claude
    val name: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val baseUrl: String? = null,
    val authType: String? = null, // OpenAI: api_key / chatgpt_subscription
    /** 存在密钥时的引用占位；MVP 阶段为占位符，P2（Keystore）阶段启用 */
    val apiKeyRef: String? = null,
    /** 余额获取配置（BalanceOption 的非敏感映射） */
    val balance: ProviderBalanceDto? = null,
    val modelCount: Int = 0,
    val models: List<ModelConfigDto> = emptyList(),
    // ---- OpenAI 兼容高级设置 ----
    val chatCompletionsPath: String? = null,
    val embeddingsPath: String? = null,
    val rerankPath: String? = null,
    val useResponseApi: Boolean? = null,
    val includeHistoryReasoning: Boolean? = null,
    // ---- Claude 高级设置 ----
    val promptCaching: Boolean? = null,
    val promptCacheTtl: String? = null, // "5m" / "1h" / null
    // ---- Google / Vertex 高级设置 ----
    val vertexAI: Boolean? = null,
    val useServiceAccount: Boolean? = null,
    val location: String? = null,
    val projectId: String? = null,
)

/** 余额获取配置（非敏感）。 */
@Serializable
data class ProviderBalanceDto(
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",
)

@Serializable
data class ModelConfigDto(
    val id: String,
    val modelId: String,
    val displayName: String,
    val type: String, // chat / image / embedding / reranking
    // ---- 模型基本设置 ----
    val inputModalities: List<String> = emptyList(),   // text / image
    val outputModalities: List<String> = emptyList(),  // text / image
    val abilities: List<String> = emptyList(),         // tool / reasoning
    val builtInTools: List<String> = emptyList(),      // search / url_context / image_generation
    // ---- 模型高级设置（敏感内容只给引用） ----
    val customHeadersRef: String? = null,
    val customBodiesRef: String? = null,
    /** 模型级 provider 覆盖（嵌套脱敏，不重复展开 models） */
    val providerOverwrite: ProviderConfigDto? = null,
)

/** config/mcp.json */
@Serializable
data class McpConfigFile(
    val schemaVersion: Int = AGENT_CONFIG_SCHEMA_VERSION,
    val servers: List<McpServerConfigDto> = emptyList(),
)

@Serializable
data class McpServerConfigDto(
    val id: String,
    /** 服务器显示名（非敏感，导出/导入闭环）。旧文件缺省为空串。 */
    val name: String = "",
    /** 传输类型，对齐 MCP 配置规范 / Claude Desktop：sse / streamable_http */
    val type: String,
    val url: String? = null,
    val enable: Boolean,
    val toolCount: Int = 0,
    val oauthEnabled: Boolean = false,
    /** headers/oauth 存在敏感信息时置为引用占位，明文不导出 */
    val headersRef: String? = null,
)

/** config/assistants/<assistant-id>.json */
@Serializable
data class AssistantConfigFile(
    val schemaVersion: Int = AGENT_CONFIG_SCHEMA_VERSION,
    val assistant: AssistantConfigDto,
)

@Serializable
data class AssistantConfigDto(
    val id: String,
    val name: String,
    /** 模型引用，归一为 "providerId:modelId"；null = 跟随全局默认模型 */
    val chatModelRef: String? = null,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableWebSearch: Boolean = false,
    val workspaceId: String? = null,
    val mcpServerIds: List<String> = emptyList(),
    val enabledSkills: List<String> = emptyList(),
    val knowledgeBaseIds: List<String> = emptyList(),
    val modeInjectionIds: List<String> = emptyList(),
    val lorebookIds: List<String> = emptyList(),
    val defaultMode: String? = null,
    val tags: List<String> = emptyList(),
    // ---- 外观 ----
    /** "emoji:xxx" / "image:url" / null(=Dummy) */
    val avatar: String? = null,
    val useAssistantAvatar: Boolean = false,
    val background: String? = null,
    val backgroundOpacity: Float? = null,
    val useGradientBackground: Boolean = false,
    // ---- 上下文与生成 ----
    val contextMessageLimit: Int = 0,
    val contextTokenLimit: Int = 128_000,
    val messageTemplate: String = "{{ message }}",
    val reasoningLevel: String? = null, // off/auto/low/medium/high/xhigh
    val regexes: List<AssistantRegexConfigDto> = emptyList(),
    val presetMessages: List<PresetMessageConfigDto> = emptyList(),
    // ---- 工具与能力 ----
    val localTools: List<String> = emptyList(),
    val quickMessageIds: List<String> = emptyList(),
    val customHeadersRef: String? = null,
    val customBodiesRef: String? = null,
    val enableTimeReminder: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val allowConversationPromptInjection: Boolean = false,
    val enableKnowledgeQueryRewrite: Boolean = true,
    val enabledStudyTools: List<String> = emptyList(),
    val studySubject: String = "",
    val defaultWorkspaceCwd: String? = null,
)

/** 正则输出转换（非敏感；findRegex/replaceString 属用户配置，可导出）。 */
@Serializable
data class AssistantRegexConfigDto(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: List<String> = emptyList(), // user / assistant
    val visualOnly: Boolean = false,
)

/** 预设消息（只取 role + 文本，不含二进制/工具载荷）。 */
@Serializable
data class PresetMessageConfigDto(
    val role: String, // system / user / assistant
    val text: String,
)

/** agent/ 下配置文件的分类，管理控制台按分类分组展示。 */
@Serializable
enum class AgentConfigFileCategory {
    /** manifest.json —— 导出清单/元信息 */
    MANIFEST,

    /** config/providers.json */
    PROVIDERS,

    /** config/mcp.json */
    MCP,

    /** config/assistants/ 下的助手配置 */
    ASSISTANT,

    /** policies/ 目录下的策略文件 */
    POLICY,

    /** state/ 目录下的状态文件 */
    STATE,

    /** 其他未归类文件 */
    OTHER,
}

/** config_view 工具的返回模型（AgentConfigRepository 装配）。 */
@Serializable
data class AgentConfigView(
    val schemaVersion: Int = AGENT_CONFIG_SCHEMA_VERSION,
    val source: String? = null,
    val settingsDataVersion: Int? = null,
    val exportedAt: Long? = null,
    val files: List<AgentConfigFileInfo> = emptyList(),
    val providerCount: Int = 0,
    val mcpServerCount: Int = 0,
    val assistantCount: Int = 0,
)

@Serializable
data class AgentConfigFileInfo(
    val path: String,
    val bytes: Int,
    val status: String = "ok",
    /** 文件分类，管理控制台按此分组展示。 */
    val category: AgentConfigFileCategory = AgentConfigFileCategory.OTHER,
    /** 面向用户的展示名：助手配置文件为助手名称，其余为 null（回退为文件名）。 */
    val displayName: String? = null,
    /** 脏标记：文件是否在最近一次导出（manifest）之后被手动修改，未「应用到设置」。 */
    val dirty: Boolean = false,
)

/** backups/ 下的编辑快照信息（文件页「回退」用）。 */
@Serializable
data class AgentBackupInfo(
    /** 快照文件名（backups/ 下，如 providers_json_1234567890.bak） */
    val name: String,
    /** 快照时间（epoch millis） */
    val at: Long,
    /** 快照字节数 */
    val size: Int,
)

/** 一次导出的结果（工具展示用）。 */
data class AgentConfigExportResult(
    val exportedAt: Long,
    val files: Map<String, String>,
) {
    val ok: Boolean get() = files.values.all { it == "ok" }
}

/** state/revisions.json —— 文件编辑修订记录（写路径 P1 用）。 */
@Serializable
data class AgentConfigRevisions(
    val entries: List<AgentConfigRevision> = emptyList(),
)

@Serializable
data class AgentConfigRevision(
    val at: Long,
    val path: String,
    val size: Int,
)
