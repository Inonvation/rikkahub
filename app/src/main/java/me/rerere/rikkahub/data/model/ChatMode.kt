package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
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
    /** 极简：只注入用户自定义提示词，保留本地工具、联网搜索与附件解析（不注入 MCP/外部工具声明）。 */
    MINIMAL,

    /** 标准：功能完整，遵循助手设置中的工具（含已绑定 MCP 服务器的 mcp__* 使用），默认不注入 use_skill，可在设置中按需开启；不注入工作区/信任文件夹工具与 AGENTS 说明，不含 skill 与 MCP 的管理面（skill_admin_*、mcp_admin_*）。 */
    STANDARD,

    /** PTC（UI 显示「工作区模式」）：包含标准全部能力，并启用信任文件夹与工作区的所有工具能力（未配置时自动降级）。 */
    PTC,

    /** CREATIVE（UI 显示「管理模式」）：包含工作区全部能力，并支持 skill/MCP/提供商/助手/全局设置/搜索服务管理、环境与日志读取、新模式写入（写操作需审批）。 */
    CREATIVE;

    fun policy(): ChatModePolicy = when (this) {
        MINIMAL -> ChatModePolicy.MINIMAL
        STANDARD -> ChatModePolicy.STANDARD
        PTC -> ChatModePolicy(
            capabilities = ChatModePolicy.STANDARD.capabilities +
                Capability.SKILL_USE + Capability.WORKSPACE + Capability.TRUSTED_FOLDER
        )
        CREATIVE -> ChatModePolicy(
            capabilities = ChatModePolicy.STANDARD.capabilities +
                Capability.SKILL_USE +
                Capability.WORKSPACE + Capability.TRUSTED_FOLDER +
                Capability.SKILL_ADMIN + Capability.MCP_ADMIN + Capability.CREATIVE_TOOLS +
                Capability.DEVICE_TOOLS +
                Capability.PROVIDER_ADMIN + Capability.ASSISTANT_ADMIN +
                Capability.SETTINGS_ADMIN + Capability.DATA_ADMIN
        )
    }
}

/** 内置模式生效策略：用户覆盖优先，否则使用出厂默认。 */
fun ChatMode.effectivePolicy(settings: Settings): ChatModePolicy =
    settings.builtinModeOverrides[this] ?: policy()

/**
 * 模式能力中因当前助手或全局设置限制而实际不可用的项（设置页展示用近似视图）。
 *
 * 生成链路请使用 [withAvailability]，以运行时真实事实（技能安装情况、信任文件夹绑定解析）裁决。
 */
fun ChatModePolicy.restrictedCapabilities(settings: Settings): Set<Capability> {
    val assistant = settings.getCurrentAssistant()
    val effective = withAvailability(
        assistant = assistant,
        settings = settings,
        skillsInstalled = true,
        trustedFolderBound = assistant.trustedFolderProjectId != null,
    )
    return capabilities - effective.capabilities
}

/**
 * 可用性裁决（单一出口）：从策略能力清单中扣除「因助手配置 / 全局设置 / 运行时事实而不可用」的能力。
 *
 * 门控公式：effective = policy.allows(family) && settings.globalEnabled(family)
 *           && assistant.optIn(family) && runtime.ready(family)。
 * 这里折叠前三层；L4 运行时就绪中「工具工厂能自行判空」的部分（MCP 连接状态、Shizuku、
 * 工作区 rootfs）仍由各工具工厂兜底，此处只裁决声明级可用性。
 *
 * 「能用」与「能管理」分离：MCP_USE 只看助手是否绑定了服务器（optIn），
 * 全局 [Settings.enableMcpManager] 仅授权 mcp_admin_*（MCP_ADMIN）——关掉管理开关
 * 不会静默禁用已配置服务器的使用。
 */
fun ChatModePolicy.withAvailability(
    assistant: Assistant,
    settings: Settings,
    /** 设备上是否安装了任意 skill（skillManager.listSkills() 非空） */
    skillsInstalled: Boolean,
    /** 当前助手绑定的信任文件夹项目是否存在（未绑定或项目已删除 = false） */
    trustedFolderBound: Boolean,
    /** 当前助手绑定的知识库是否至少一个真实存在（库已全部删除 = false） */
    knowledgeReady: Boolean = true,
): ChatModePolicy {
    val restricted = buildSet {
        val builtInSearchEnabled = settings.getCurrentChatModel()?.tools?.contains(BuiltInTools.Search) == true
        if (Capability.SEARCH in capabilities && !assistant.enableWebSearch && !builtInSearchEnabled) {
            add(Capability.SEARCH)
        }
        if (Capability.WORKSPACE in capabilities && assistant.workspaceId == null) add(Capability.WORKSPACE)
        if (Capability.MCP_USE in capabilities && assistant.mcpServers.isEmpty()) {
            add(Capability.MCP_USE)
        }
        if (Capability.MCP_ADMIN in capabilities && !settings.enableMcpManager) add(Capability.MCP_ADMIN)
        if (Capability.SKILL_USE in capabilities && (!skillsInstalled || assistant.enabledSkills.isEmpty())) {
            add(Capability.SKILL_USE)
        }
        if (Capability.SKILL_ADMIN in capabilities && !skillsInstalled) add(Capability.SKILL_ADMIN)
        if (Capability.MEMORY in capabilities && !assistant.enableMemory) add(Capability.MEMORY)
        if (Capability.TODO in capabilities && !settings.enableTodoList) add(Capability.TODO)
        if (Capability.SUBAGENT in capabilities && !settings.enableSubAgent) add(Capability.SUBAGENT)
        if (Capability.STUDY in capabilities && assistant.enabledStudyTools.isEmpty()) add(Capability.STUDY)
        if (Capability.HISTORY in capabilities && !assistant.enableRecentChatsReference) {
            add(Capability.HISTORY)
        }
        if (Capability.KNOWLEDGE in capabilities &&
            (assistant.knowledgeBaseIds.isEmpty() || !knowledgeReady)
        ) {
            add(Capability.KNOWLEDGE)
        }
        if (Capability.TRUSTED_FOLDER in capabilities && !trustedFolderBound) add(Capability.TRUSTED_FOLDER)
    }
    return if (restricted.isEmpty()) this else copy(capabilities = capabilities - restricted)
}

/**
 * 能力项：一个工具族或提示词片段的门控单元。模式即一份能力清单（[ChatModePolicy.capabilities]），
 * 清单里列出该模式允许注入的能力，与 dsh 的 agent preset（agent.cordis.yml 组装行）同思路：
 * preset 决定可见性，注册表/沙箱/审批/持久化等宿主层不动。
 */
@Serializable
enum class Capability(val managementOnly: Boolean = false) {
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

    /** skill_admin_*（感知与配置 skill，管理模式专属） */
    SKILL_ADMIN(managementOnly = true),

    /** 外部 MCP 工具 mcp__* */
    MCP_USE,

    /** mcp_admin_*（感知与配置 MCP，管理模式专属） */
    MCP_ADMIN(managementOnly = true),

    /** 记忆工具与记忆提示词 */
    MEMORY,

    /** todo 工具 */
    TODO,

    /** 子代理工具 */
    SUBAGENT,

    /** 学习工具（生词/笔记/错题/知识卡/测验） */
    STUDY,

    /** 设备工具族（诊断/存储/冻结，依赖 Shizuku） */
    DEVICE_TOOLS,

    /** 历史对话引用/会话搜索 */
    HISTORY,

    /** 知识库检索 */
    KNOWLEDGE,

    /** 模式注入/lorebook 提示词注入 */
    PROMPT_INJECTION,

    /** 时间提醒/todo 提醒 */
    REMINDERS,

    /** tool.systemPrompt 循环 */
    TOOL_SYSTEM_PROMPT,

    /** agent behavior 行为层提示词（独立于 tool.systemPrompt） */
    AGENT_BEHAVIOR_PROMPT,

    /** env_inspect/app_logs/provider_add/mode_create/mode_update/mode_delete */
    CREATIVE_TOOLS(managementOnly = true),

    /** provider_list/provider_get/provider_update/provider_delete/provider_test */
    PROVIDER_ADMIN(managementOnly = true),

    /** assistant_list/assistant_get/assistant_create/assistant_update/assistant_duplicate/assistant_delete */
    ASSISTANT_ADMIN(managementOnly = true),

    /** settings_admin_list/settings_admin_get/settings_admin_set */
    SETTINGS_ADMIN(managementOnly = true),

    /** search_admin_* 与 admin_inventory */
    DATA_ADMIN(managementOnly = true),
}

/** 行为风格：由能力清单派生的执行准则，决定 agent behavior 提示词注入哪一段模式指导。 */
@Serializable
enum class AgentBehaviorProfile {
    /** 通用：工具齐全，但按需使用，不主动扩大任务范围。 */
    STANDARD,

    /** 工作区：以项目文件为中心，连续推进多步任务并验证结果。 */
    WORKSPACE,

    /** 管理：以只读感知为前提，写操作说明影响并等待审批。 */
    MANAGEMENT,

    /** 极简：默认不调用工具，只在用户明确要求时使用。 */
    MINIMAL,

    /** 无模式版本行为：不注入模式引导，保留决策、工具分组与子代理说明。 */
    LEGACY,
}

/**
 * 模式策略：生成时的工具族/提示词片段/环境说明门控清单。
 * 序列化存 [capabilities]，布尔字段为派生视图，注入链路读取不受影响。
 */
@Serializable
data class ChatModePolicy(
    /** 该模式允许注入的能力清单 */
    val capabilities: Set<Capability> = DEFAULT_CAPABILITIES,
    /** 显式行为风格；null = 按能力清单自动推导 */
    val behaviorProfileOverride: AgentBehaviorProfile? = null,
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
    val allowDeviceTools: Boolean get() = Capability.DEVICE_TOOLS in capabilities
    val allowHistory: Boolean get() = Capability.HISTORY in capabilities
    val allowKnowledge: Boolean get() = Capability.KNOWLEDGE in capabilities
    val includePromptInjection: Boolean get() = Capability.PROMPT_INJECTION in capabilities
    val includeReminders: Boolean get() = Capability.REMINDERS in capabilities
    val includeToolSystemPrompt: Boolean get() = Capability.TOOL_SYSTEM_PROMPT in capabilities
    val allowCreativeTools: Boolean get() = Capability.CREATIVE_TOOLS in capabilities
    val allowProviderAdmin: Boolean get() = Capability.PROVIDER_ADMIN in capabilities
    val allowAssistantAdmin: Boolean get() = Capability.ASSISTANT_ADMIN in capabilities
    val allowSettingsAdmin: Boolean get() = Capability.SETTINGS_ADMIN in capabilities
    val allowDataAdmin: Boolean get() = Capability.DATA_ADMIN in capabilities
    val allowLocalTools: Boolean get() = Capability.LOCAL_TOOLS in capabilities
    val allowSearch: Boolean get() = Capability.SEARCH in capabilities
    val allowDocument: Boolean get() = Capability.DOCUMENT in capabilities
    val behaviorProfile: AgentBehaviorProfile
        get() = behaviorProfileOverride ?: when {
            allowCreativeTools || allowProviderAdmin || allowAssistantAdmin ||
                allowSettingsAdmin || allowDataAdmin || allowSkillAdmin || allowMcpAdmin ->
                AgentBehaviorProfile.MANAGEMENT
            allowWorkspace || allowTrustedFolder -> AgentBehaviorProfile.WORKSPACE
            capabilities == MINIMAL_CAPABILITIES -> AgentBehaviorProfile.MINIMAL
            else -> AgentBehaviorProfile.STANDARD
        }

    /** 是否注入 agent behavior 提示词：独立能力开关，极简模式默认保留一段行为准则。 */
    val includeAgentBehaviorPrompt: Boolean
        get() = Capability.AGENT_BEHAVIOR_PROMPT in capabilities ||
            behaviorProfile == AgentBehaviorProfile.MINIMAL

    companion object {
        /** 默认能力（标准模式基础）：本地工具/搜索/附件解析/MCP/记忆/扩展工具/提示词注入/提醒/工具提示词/行为层提示词 */
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
            Capability.AGENT_BEHAVIOR_PROMPT,
        )

        /** 标准模式策略：默认能力，不注入 use_skill */
        val STANDARD = ChatModePolicy(capabilities = DEFAULT_CAPABILITIES)

        /** 极简模式能力清单：本地工具/搜索/附件解析 */
        val MINIMAL_CAPABILITIES: Set<Capability> =
            setOf(Capability.LOCAL_TOOLS, Capability.SEARCH, Capability.DOCUMENT)

        /** 极简模式策略：仅声明本地/搜索/文档工具（无工具 systemPrompt 说明），保留一段「默认不主动调用工具」的行为准则 */
        val MINIMAL = ChatModePolicy(
            capabilities = MINIMAL_CAPABILITIES,
            behaviorProfileOverride = AgentBehaviorProfile.MINIMAL,
        )

        /** 跟随助手配置能力集合：标准模式基础 + 工作区/信任文件夹/skill 使用（管理模式专属工具一律排除）。 */
        val UNRESTRICTED_CAPABILITIES: Set<Capability> =
            DEFAULT_CAPABILITIES +
                setOf(Capability.WORKSPACE, Capability.TRUSTED_FOLDER, Capability.SKILL_USE)

        /** 跟随助手配置策略：无模式门控，行为提示词还原无模式版本。 */
        val UNRESTRICTED = ChatModePolicy(
            capabilities = UNRESTRICTED_CAPABILITIES,
            behaviorProfileOverride = AgentBehaviorProfile.LEGACY,
        )
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

    /** 「跟随助手配置」伪条目引用，仅用于 mode_list 展示与防御性解析，不落库为会话 mode。 */
    const val FOLLOW_ASSISTANT = "follow_assistant"

    fun builtin(mode: ChatMode): String = mode.name

    fun custom(id: String): String = "$CUSTOM_PREFIX$id"

    fun parseBuiltin(value: String?): ChatMode? =
        value?.let { runCatching { ChatMode.valueOf(it) }.getOrNull() }
}

/**
 * 默认模式解析（单一数据源）：助手显式配置 > 全局显式配置。
 *
 * 未显式配置时返回 null，表示会话使用「跟随助手配置」。
 */
fun resolveModeRef(assistant: Assistant, settings: Settings): String? =
    assistant.defaultMode
        ?: settings.defaultMode

/** 把模式引用解析为策略；引用为空或指向不存在的模式时返回 null。 */
fun resolveModePolicy(ref: String?, settings: Settings): ChatModePolicy? {
    if (ref.isNullOrBlank()) return null
    if (ref.startsWith(ModeRefs.CUSTOM_PREFIX)) {
        val custom = settings.customModes.find { it.id == ref.removePrefix(ModeRefs.CUSTOM_PREFIX) }
        return custom?.policy
    }
    return ModeRefs.parseBuiltin(ref)?.effectivePolicy(settings)
}

/**
 * 会话级生效策略：mode 为 null 时使用「跟随助手配置」；显式模式按引用解析，
 * 非法或已删除的显式引用回退标准模式。
 *
 * 返回的是模式策略（能力上限）；生成链路还需经 [withAvailability] 折叠助手/全局/运行时可用性。
 */
fun resolveConversationPolicy(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
): ChatModePolicy {
    val modeStr = conversation.mode
    if (modeStr.isNullOrBlank() || modeStr == ModeRefs.FOLLOW_ASSISTANT) {
        return ChatModePolicy.UNRESTRICTED
    }
    return resolveModePolicy(ref = modeStr, settings = settings) ?: ChatMode.STANDARD.effectivePolicy(settings)
}
