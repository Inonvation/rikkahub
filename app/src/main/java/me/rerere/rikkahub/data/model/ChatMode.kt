package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * 助手能力模式：决定生成时注入哪些工具族、系统提示词片段与环境说明。
 *
 * 与既有「模式注入」（[PromptInjection.ModeInjection]，提示词注入类别）语义隔离，互不干扰。
 *
 * 模式为可组合结构：内置模式对应一组 [Capability] 清单，管理模式可通过
 * [CustomModeConfig] 声明一份能力清单生成新模式（写操作需用户审批）。
 */
@Serializable
enum class ChatMode {
    /** 标准：功能完整，遵循助手设置中的工具，但不注入工作区/信任文件夹工具与 AGENTS 说明，不支持 skill/MCP 感知与配置。 */
    STANDARD,

    /** PTC（UI 显示「工作区模式」）：包含标准全部能力，并启用信任文件夹与工作区的所有工具能力（未配置时自动降级）。 */
    PTC,

    /** 极简：只注入用户自定义提示词，保留本地工具、联网搜索与附件解析（不注入 MCP/外部工具声明）。 */
    MINIMAL,

    /** CREATIVE（UI 显示「管理模式」）：包含工作区全部能力，并支持 skill/MCP 感知配置、环境与日志读取、提供商与新模式写入（写操作需审批）。 */
    CREATIVE;

    fun policy(): ChatModePolicy = when (this) {
        STANDARD -> ChatModePolicy.STANDARD
        PTC -> ChatModePolicy(capabilities = ChatModePolicy.STANDARD.capabilities + Capability.WORKSPACE + Capability.TRUSTED_FOLDER)
        MINIMAL -> ChatModePolicy.MINIMAL
        CREATIVE -> ChatModePolicy(
            capabilities = ChatModePolicy.STANDARD.capabilities +
                Capability.WORKSPACE + Capability.TRUSTED_FOLDER +
                Capability.SKILL_ADMIN + Capability.MCP_ADMIN + Capability.CREATIVE_TOOLS
        )
    }
}

/**
 * 能力项：一个工具族或提示词片段的门控单元。模式即一份能力清单（[ChatModePolicy.capabilities]），
 * 清单里列出该模式允许注入的能力，与 dsh 的 agent preset（agent.cordis.yml 组装行）同思路：
 * preset 决定可见性，注册表/沙箱/审批/持久化等宿主层不动。
 */
@Serializable
enum class Capability {
    /** 本地工具族（时间/剪贴板/JS 等） */
    LOCAL_TOOLS,

    /** 联网搜索工具族 */
    SEARCH,

    /** 附件文档解析与 OCR 注入 */
    DOCUMENT,

    /** workspace 工具族 + AGENTS.md/工作区环境说明注入 */
    WORKSPACE,

    /** 信任文件夹工具族 + 环境说明注入 */
    TRUSTED_FOLDER,

    /** use_skill（已启用 skill 的使用） */
    SKILL_USE,

    /** skill_admin_*（感知与配置 skill） */
    SKILL_ADMIN,

    /** 外部 MCP 工具 mcp__* */
    MCP_USE,

    /** mcp_admin_*（感知与配置 MCP） */
    MCP_ADMIN,

    /** 记忆工具与记忆提示词 */
    MEMORY,

    /** todo 工具 */
    TODO,

    /** 子代理工具 */
    SUBAGENT,

    /** 学习工具（生词/笔记/错题/知识卡/测验） */
    STUDY,

    /** 历史对话引用/会话搜索 */
    HISTORY,

    /** 知识库检索 */
    KNOWLEDGE,

    /** 模式注入/lorebook 提示词注入 */
    PROMPT_INJECTION,

    /** 时间提醒/todo 提醒 */
    REMINDERS,

    /** tool.systemPrompt 循环 + agent behavior 提示词 */
    TOOL_SYSTEM_PROMPT,

    /** env_inspect/app_logs/provider_add/mode_create/mode_update/mode_delete */
    CREATIVE_TOOLS,
}

/** 行为风格：由能力清单派生的执行准则，决定 agent behavior 提示词注入哪一段模式指导。 */
enum class AgentBehaviorProfile {
    /** 通用：工具齐全，但按需使用，不主动扩大任务范围。 */
    STANDARD,

    /** 工作区：以项目文件为中心，连续推进多步任务并验证结果。 */
    WORKSPACE,

    /** 管理：以只读感知为前提，写操作说明影响并等待审批。 */
    MANAGEMENT,

    /** 极简：默认不调用工具，只在用户明确要求时使用。 */
    MINIMAL,
}

/**
 * 模式策略：生成时的工具族/提示词片段/环境说明门控清单。
 * 序列化存 [capabilities]，布尔字段为派生视图，注入链路读取不受影响。
 */
@Serializable
data class ChatModePolicy(
    /** 该模式允许注入的能力清单 */
    val capabilities: Set<Capability> = DEFAULT_CAPABILITIES,
) {
    val allowWorkspace: Boolean get() = Capability.WORKSPACE in capabilities
    val allowTrustedFolder: Boolean get() = Capability.TRUSTED_FOLDER in capabilities
    val allowSkillUse: Boolean get() = Capability.SKILL_USE in capabilities
    val allowSkillAdmin: Boolean get() = Capability.SKILL_ADMIN in capabilities
    val allowMcpUse: Boolean get() = Capability.MCP_USE in capabilities
    val allowMcpAdmin: Boolean get() = Capability.MCP_ADMIN in capabilities
    val allowMemory: Boolean get() = Capability.MEMORY in capabilities
    val allowTodo: Boolean get() = Capability.TODO in capabilities
    val allowSubAgent: Boolean get() = Capability.SUBAGENT in capabilities
    val allowStudy: Boolean get() = Capability.STUDY in capabilities
    val allowHistory: Boolean get() = Capability.HISTORY in capabilities
    val allowKnowledge: Boolean get() = Capability.KNOWLEDGE in capabilities
    val includePromptInjection: Boolean get() = Capability.PROMPT_INJECTION in capabilities
    val includeReminders: Boolean get() = Capability.REMINDERS in capabilities
    val includeToolSystemPrompt: Boolean get() = Capability.TOOL_SYSTEM_PROMPT in capabilities
    val allowCreativeTools: Boolean get() = Capability.CREATIVE_TOOLS in capabilities
    val allowLocalTools: Boolean get() = Capability.LOCAL_TOOLS in capabilities
    val allowSearch: Boolean get() = Capability.SEARCH in capabilities
    val allowDocument: Boolean get() = Capability.DOCUMENT in capabilities
    val behaviorProfile: AgentBehaviorProfile
        get() = when {
            allowCreativeTools || allowSkillAdmin || allowMcpAdmin -> AgentBehaviorProfile.MANAGEMENT
            allowWorkspace || allowTrustedFolder -> AgentBehaviorProfile.WORKSPACE
            capabilities == MINIMAL_CAPABILITIES -> AgentBehaviorProfile.MINIMAL
            else -> AgentBehaviorProfile.STANDARD
        }

    /** 是否注入 agent behavior 提示词：工具提示词开启时随主链路注入，极简模式单独保留一段行为准则。 */
    val includeAgentBehaviorPrompt: Boolean
        get() = includeToolSystemPrompt || behaviorProfile == AgentBehaviorProfile.MINIMAL

    companion object {
        /** 默认能力（标准模式基础）：本地工具/搜索/附件解析/MCP/记忆/扩展工具/提示词注入/提醒/工具提示词 */
        val DEFAULT_CAPABILITIES: Set<Capability> = setOf(
            Capability.LOCAL_TOOLS,
            Capability.SEARCH,
            Capability.DOCUMENT,
            Capability.MCP_USE,
            Capability.MEMORY,
            Capability.TODO,
            Capability.SUBAGENT,
            Capability.STUDY,
            Capability.HISTORY,
            Capability.KNOWLEDGE,
            Capability.PROMPT_INJECTION,
            Capability.REMINDERS,
            Capability.TOOL_SYSTEM_PROMPT,
        )

        /** 标准模式策略：默认能力 + 已启用 skill 的使用 */
        val STANDARD = ChatModePolicy(capabilities = DEFAULT_CAPABILITIES + Capability.SKILL_USE)

        /** 极简模式能力清单：本地工具/搜索/附件解析 */
        val MINIMAL_CAPABILITIES: Set<Capability> =
            setOf(Capability.LOCAL_TOOLS, Capability.SEARCH, Capability.DOCUMENT)

        /** 极简模式策略：不注入工具声明，但仍保留一段「默认不主动调用工具」的行为准则 */
        val MINIMAL = ChatModePolicy(capabilities = MINIMAL_CAPABILITIES)
    }
}

/** 管理模式生成的自定义模式配置，写入 [Settings.customModes]。 */
@Serializable
data class CustomModeConfig(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val description: String = "",
    val policy: ChatModePolicy = ChatModePolicy(),
)

/** 会话内模式引用的序列化：内置模式存枚举名，自定义模式存 `custom:<id>`。 */
object ModeRefs {
    const val CUSTOM_PREFIX = "custom:"

    fun builtin(mode: ChatMode): String = mode.name

    fun custom(id: String): String = "$CUSTOM_PREFIX$id"

    fun parseBuiltin(value: String?): ChatMode? =
        value?.let { runCatching { ChatMode.valueOf(it) }.getOrNull() }
}

/**
 * 默认模式解析（单一数据源）：助手显式配置 > 全局显式配置 > 兼容规则。
 *
 * 兼容规则：未显式配置时，绑定工作区或存在激活信任文件夹的助手落到 PTC，
 * 保持升级前行为不静默丢失能力；其余落到 STANDARD。
 */
fun resolveModeRef(assistant: Assistant, settings: Settings, trustedFolderActive: Boolean): String? =
    assistant.defaultMode
        ?: settings.defaultMode
        ?: when {
            assistant.workspaceId != null -> ModeRefs.builtin(ChatMode.PTC)
            trustedFolderActive -> ModeRefs.builtin(ChatMode.PTC)
            else -> ModeRefs.builtin(ChatMode.STANDARD)
        }

fun resolveMode(assistant: Assistant, settings: Settings, trustedFolderActive: Boolean): ChatMode =
    resolveModeRef(assistant, settings, trustedFolderActive)
        ?.let { ModeRefs.parseBuiltin(it) }
        ?: ChatMode.STANDARD

/** 把模式引用解析为策略；引用为空或指向不存在的模式时返回 null。 */
fun resolveModePolicy(ref: String?, settings: Settings): ChatModePolicy? {
    if (ref.isNullOrBlank()) return null
    if (ref.startsWith(ModeRefs.CUSTOM_PREFIX)) {
        val custom = settings.customModes.find { it.id == ref.removePrefix(ModeRefs.CUSTOM_PREFIX) }
        return custom?.policy
    }
    return ModeRefs.parseBuiltin(ref)?.policy()
}

/**
 * 会话级生效策略：会话自定义模式（含自定义模式）优先，否则回落默认解析。
 * 旧会话 mode 为 null 时按 [resolveModeRef] 现算，保证升级兼容。
 */
fun resolveConversationPolicy(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    trustedFolderActive: Boolean,
): ChatModePolicy {
    val modeStr = conversation.mode
    if (modeStr.isNullOrBlank()) {
        return resolveModePolicy(
            ref = resolveModeRef(assistant, settings, trustedFolderActive),
            settings = settings,
        ) ?: ChatMode.STANDARD.policy()
    }
    return resolveModePolicy(ref = modeStr, settings = settings) ?: ChatMode.STANDARD.policy()
}
