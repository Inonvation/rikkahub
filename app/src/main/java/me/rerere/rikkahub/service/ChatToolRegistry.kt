package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.StudyToolPermissions
import me.rerere.rikkahub.data.ai.tools.StudyTools
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.ai.tools.createAssistantAdminTools
import me.rerere.rikkahub.data.ai.tools.createAuditTools
import me.rerere.rikkahub.data.ai.tools.createAgentConfigTools
import me.rerere.rikkahub.data.ai.tools.createConversationAdminTools
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createCreativeTools
import me.rerere.rikkahub.data.ai.tools.createDataAdminTools
import me.rerere.rikkahub.data.ai.tools.createKnowledgeAdminTools
import me.rerere.rikkahub.data.ai.tools.createMcpManagerTools
import me.rerere.rikkahub.data.ai.tools.createProviderAdminTools
import me.rerere.rikkahub.data.ai.tools.createRollbackTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSettingsAdminTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createSubAgentTools
import me.rerere.rikkahub.data.ai.tools.createTodoTool
import me.rerere.rikkahub.data.ai.tools.createTrustedFolderAdminTools
import me.rerere.rikkahub.data.ai.tools.createTrustedFolderTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceAdminTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.device.DeviceTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.management.ManagementRollbackStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.knowledge.KnowledgeManager
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolRegistry"

/** MCP 服务器名非法（空或含非字母数字字符）时抛出；由调用方转为用户可见错误并中止本轮生成。 */
class McpServerNameInvalidException(val invalidNames: List<String>) : Exception()

/**
 * 知识库工具工厂接口：知识库装配依赖检索增强管线（查询改写/HyDE/MultiQuery/rerank），
 * 该管线留在 ChatService（复用其后台重试与文本生成设施），注册表只依赖此接缝。
 */
fun interface KnowledgeToolFactory {
    suspend fun create(assistant: Assistant, conversation: Conversation, settings: Settings): List<Tool>
}

/**
 * 主聊工具注册表：Capability → 工具工厂的单一映射点（收编原 ChatService.handleMessage 内的
 * buildList 分支）。子代理/群聊装配路径各自维持显式白名单，暂不经过此表（见能力隔离方案 §9）。
 *
 * 三条约定（改动前先读懂）：
 * 1. 下游只看 effective 策略——模式白名单 ∩ 助手/全局/运行时可用性已在
 *    [ChatModePolicy.withAvailability] 折叠，本表只按能力集合过滤条目，不做二次门控；
 * 2. 各工厂内部的就绪检查（MCP 连接状态/Shizuku/rootfs/绑定项目存在性）保留为 L4 运行时兜底；
 * 3. 条目书写顺序不影响 provider 收到的 tools 数组——GenerationHandler 发送前统一 canonicalToolOrder()，
 *    排列只由能力集合决定。
 *
 * 一个条目可声明多个能力：任一能力在 effective 集合中即装配，条目内部再按策略细分
 * （如 skill 条目按 SKILL_USE/SKILL_ADMIN 拆分 use_skill 与 skill_admin_*）。
 */
class ChatToolRegistry(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val subAgentRunner: SubAgentRunner,
    private val studyTools: StudyTools,
    private val localTools: LocalTools,
    private val deviceTools: DeviceTools,
    private val conversationRepo: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val trustedFolderRepository: TrustedFolderRepository,
    private val todoStorage: TodoStorage,
    private val providerManager: ProviderManager,
    private val managementAuditStore: ManagementAuditStore,
    private val managementRollbackStore: ManagementRollbackStore,
    private val agentConfigRepository: AgentConfigRepository,
    private val knowledgeManager: KnowledgeManager,
    private val knowledgeToolFactory: KnowledgeToolFactory,
) {
    /** 单轮装配请求：effective 策略 + 会话上下文快照。 */
    class Request(
        val policy: ChatModePolicy,
        val assistant: Assistant,
        val conversation: Conversation,
        val settings: Settings,
    )

    private class Entry(
        val capabilities: Set<Capability>,
        val assemble: suspend (Request) -> List<Tool>,
    )

    private val entries: List<Entry> = listOf(
        Entry(setOf(Capability.TODO)) { req ->
            listOf(createTodoTool(req.conversation.id.toString(), todoStorage))
        },
        Entry(setOf(Capability.SUBAGENT)) { req ->
            createSubAgentTools(subAgentRunner, req.conversation.id)
        },
        Entry(setOf(Capability.STUDY)) { req ->
            studyTools.getTools(
                enabledTools = req.assistant.enabledStudyTools,
                conversationId = req.conversation.id.toString(),
                assistantId = req.assistant.id.toString(),
                studySubject = req.assistant.studySubject,
                permissions = StudyToolPermissions.fromSettings(req.settings),
            )
        },
        Entry(setOf(Capability.SEARCH)) { req ->
            createSearchTools(req.settings).toList()
        },
        Entry(setOf(Capability.LOCAL_TOOLS)) { req ->
            localTools.getTools(req.assistant.localTools)
        },
        Entry(setOf(Capability.DEVICE_TOOLS)) { _ ->
            // Shizuku 未就绪时工厂返回空列表（L4 兜底）
            deviceTools.getAllTools()
        },
        Entry(setOf(Capability.HISTORY)) { req ->
            createConversationTools(conversationRepo, req.assistant.id)
        },
        Entry(setOf(Capability.WORKSPACE)) { req ->
            createWorkspaceToolsIfReady(req.assistant.workspaceId?.toString(), req.conversation.workspaceCwd)
        },
        Entry(setOf(Capability.TRUSTED_FOLDER)) { req ->
            // effective 保证绑定存在；let 仅绕过 custom getter 的不可空推断
            req.assistant.trustedFolderProjectId?.let { pid ->
                createTrustedFolderTools(trustedFolderRepository, pid)
            } ?: emptyList()
        },
        Entry(setOf(Capability.SKILL_USE, Capability.SKILL_ADMIN)) { req ->
            // 快照仅用于决定工具 schema 组成与 <enabled_skills> 系统提示（前缀缓存稳定）；
            // skill_admin_* 的执行体通过 provider 实时读盘，同轮内新装的技能立即可查。
            val allSkills = skillManager.listSkills()
            if (allSkills.isEmpty()) {
                emptyList()
            } else {
                createSkillTools(
                    enabledSkills = req.assistant.enabledSkills,
                    listAllSkills = { skillManager.listSkills() },
                    setEnabledSkills = { skills ->
                        settingsStore.updateAssistantSkills(req.assistant.id, skills)
                    },
                ).let { filterSkillToolsByMode(it, req.policy) }
            }
        },
        Entry(setOf(Capability.MCP_ADMIN)) { req ->
            createMcpManagerTools(
                mcpManager = mcpManager,
                settingsStore = settingsStore,
                assistant = req.assistant,
                isEnabled = req.settings.enableMcpManager,
            )
        },
        Entry(setOf(Capability.MCP_USE)) { req ->
            // 与上游一致：每个 MCP 工具注册独立 function schema（mcp__{server}__{tool}）
            createMcpUseTools(req.assistant)
        },
        // 管理模式专属工具：环境/日志只读感知 + 提供商/新模式写入（需审批）
        Entry(setOf(Capability.CREATIVE_TOOLS)) { req ->
            createCreativeTools(
                context = context,
                settingsStore = settingsStore,
                assistant = req.assistant,
                conversationRepository = conversationRepo,
            )
        },
        Entry(setOf(Capability.PROVIDER_ADMIN)) { _ ->
            createProviderAdminTools(
                settingsStore = settingsStore,
                providerManager = providerManager,
                auditStore = managementAuditStore,
                rollbackStore = managementRollbackStore,
            )
        },
        Entry(setOf(Capability.ASSISTANT_ADMIN)) { _ ->
            createAssistantAdminTools(
                settingsStore = settingsStore,
                auditStore = managementAuditStore,
                rollbackStore = managementRollbackStore,
            )
        },
        Entry(setOf(Capability.SETTINGS_ADMIN)) { _ ->
            createSettingsAdminTools(
                settingsStore = settingsStore,
                auditStore = managementAuditStore,
                rollbackStore = managementRollbackStore,
            )
        },
        Entry(setOf(Capability.DATA_ADMIN)) { req ->
            createDataAdminTools(
                settingsStore = settingsStore,
                auditStore = managementAuditStore,
                conversationRepo = conversationRepo,
                trustedFolderRepository = trustedFolderRepository,
                rollbackStore = managementRollbackStore,
            ) +
                createWorkspaceAdminTools(
                    settingsStore = settingsStore,
                    workspaceRepository = workspaceRepository,
                    auditStore = managementAuditStore,
                    rollbackStore = managementRollbackStore,
                ) +
                createTrustedFolderAdminTools(
                    settingsStore = settingsStore,
                    trustedFolderRepository = trustedFolderRepository,
                    auditStore = managementAuditStore,
                ) +
                createKnowledgeAdminTools(
                    settingsStore = settingsStore,
                    knowledgeManager = knowledgeManager,
                    auditStore = managementAuditStore,
                    rollbackStore = managementRollbackStore,
                ) +
                createConversationAdminTools(
                    settingsStore = settingsStore,
                    conversationRepo = conversationRepo,
                    auditStore = managementAuditStore,
                    currentConversationId = req.conversation.id,
                )
        },
        // 任一管理能力在场即装配审计/回滚/统一配置读写
        Entry(
            setOf(
                Capability.CREATIVE_TOOLS,
                Capability.PROVIDER_ADMIN,
                Capability.ASSISTANT_ADMIN,
                Capability.SETTINGS_ADMIN,
                Capability.DATA_ADMIN,
                Capability.SKILL_ADMIN,
                Capability.MCP_ADMIN,
            ),
        ) { _ ->
            createAuditTools(managementAuditStore) +
                createRollbackTools(
                    rollbackStore = managementRollbackStore,
                    settingsStore = settingsStore,
                    auditStore = managementAuditStore,
                ) +
                // 统一配置读写（config_view / config_read / config_refresh / config_schema / config_validate / config_write）
                createAgentConfigTools(
                    agentConfigRepository,
                    settingsStore,
                    managementAuditStore,
                    managementRollbackStore,
                )
        },
        Entry(setOf(Capability.KNOWLEDGE)) { req ->
            knowledgeToolFactory.create(req.assistant, req.conversation, req.settings)
        },
    )

    /** 按 effective 策略过滤条目并装配全部工具；MCP 服务器名非法时抛 [McpServerNameInvalidException]。 */
    suspend fun assemble(request: Request): List<Tool> = buildList {
        entries.forEach { entry ->
            if (entry.capabilities.any { it in request.policy.capabilities }) {
                addAll(entry.assemble(request))
            }
        }
    }

    private fun createMcpUseTools(assistant: Assistant): List<Tool> {
        val available = mcpManager.getAllAvailableTools(assistant)
        val invalidNames = available
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) throw McpServerNameInvalidException(invalidNames)
        return available.map { (serverId, serverName, tool) ->
            Tool(
                name = "mcp__${serverName}__${tool.name}",
                description = tool.description?.takeIf { it.isNotBlank() }
                    ?: "Tool from MCP server \"$serverName\".",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = {
                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                },
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
