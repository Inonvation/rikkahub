package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.tool.EmbeddingConfig
import me.rerere.knowledge.tool.KnowledgeSearchTool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createDocumentReadTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 子代理工具装配器：把 [SubAgentDefinition.capabilities] 解析成具体 [Tool] 列表。
 *
 * 与定义分离，保证定义可序列化，装配逻辑留在代码层。
 * 各能力复用 app 既有工具工厂（搜索/MCP/知识库/workspace/文档），避免复制 ChatService 装配逻辑。
 *
 * 能力 → 工具映射：
 * - SEARCH/SCRAPE → createSearchTools 的每源独立工具（母代理子代理共用）
 * - MCP → mcp__{server}__{tool}
 * - KNOWLEDGE_BASE → KnowledgeSearchTool 的 kb_search / kb_list
 * - DOCUMENT → document_read（本地文档解析）
 * - WORKSPACE → createWorkspaceTools（沙盒 shell/文件，装配时强制免审批——子代理是可信委派执行器）
 */
class SubAgentToolAssembler(
    private val mcpManager: McpManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val knowledgeManager: KnowledgeManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun assemble(
        def: SubAgentDefinition,
        settings: Settings,
        parentConversation: Conversation,
    ): List<Tool> {
        if (def.capabilities.isEmpty() || def.capabilities == setOf(SubAgentCapability.NONE)) {
            return emptyList()
        }
        val parentAssistant = settings.getAssistantById(parentConversation.assistantId)

        return buildList {
            // 联网搜索 + 抓取：复用母代理的多选服务商工具集。
            // concise=true：子代理不面向用户做引用渲染，去掉 citation/images/示例等冗余说明，省 token
            if (SubAgentCapability.SEARCH in def.capabilities || SubAgentCapability.SCRAPE in def.capabilities) {
                addAll(createSearchTools(settings, concise = true))
            }

            // MCP 工具：复用 ChatService 的封装模式（mcp__{server}__{tool}）
            if (SubAgentCapability.MCP in def.capabilities) {
                val mcpAssistant = parentAssistant ?: settings.getCurrentAssistant()
                mcpManager.getAllAvailableTools(mcpAssistant).forEach { (serverId, serverName, tool) ->
                    // 校验服务器名，非法名跳过（与 ChatService 一致），避免 mcp____tool 畸形工具名
                    if (serverName.isEmpty() || !serverName.all {
                            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
                        }
                    ) return@forEach
                    add(
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
                    )
                }
            }

            // 知识库检索：复用 KnowledgeSearchTool（最小版，跳过 rewrite/hyde/multiQuery）
            if (SubAgentCapability.KNOWLEDGE_BASE in def.capabilities) {
                val kbIds = parentAssistant?.knowledgeBaseIds.orEmpty()
                if (kbIds.isNotEmpty()) {
                    val allowedIds = kbIds.map { it.toString() }.toSet()
                    val rerankModelId = kbIds.firstOrNull()?.let { kbId ->
                        runCatching {
                            knowledgeManager.baseRepository.getById(kbId.toString())?.rerankModelId
                        }.getOrNull()
                    } ?: settings.rerankModelId?.toString()
                    val tool = KnowledgeSearchTool(
                        knowledgeManager = knowledgeManager,
                        getAllowedKnowledgeBaseIds = { allowedIds },
                        getEmbeddingForBase = { baseId ->
                            resolveEmbeddingConfig(baseId, settings)
                        },
                        getReranker = { resolveReranker(settings, rerankModelId) },
                    )
                    add(tool.create())
                    add(tool.createListTool())
                }
            }

            // 文档分析：document_read 解析本地文档
            if (SubAgentCapability.DOCUMENT in def.capabilities) {
                add(createDocumentReadTool())
            }

            // 沙盒执行：workspace 工具（forceNoApproval 跳过审批门，保留路径越界检查）
            if (SubAgentCapability.WORKSPACE in def.capabilities) {
                val workspaceId = parentAssistant?.workspaceId?.toString()
                if (workspaceId != null) {
                    val workspace = workspaceRepository.getById(workspaceId)
                    if (workspace?.shellStatus == WorkspaceShellStatus.READY.name) {
                        addAll(
                            createWorkspaceTools(
                                workspaceId = workspaceId,
                                workspaceRepository = workspaceRepository,
                                cwd = parentConversation.workspaceCwd,
                                forceNoApproval = true,
                            )
                        )
                    }
                }
            }
        }
    }

    /** 解析单个知识库的 embedding 配置（与 ChatService.resolveEmbeddingConfig 逻辑一致） */
    private suspend fun resolveEmbeddingConfig(baseId: String, settings: Settings): EmbeddingConfig? {
        val base = knowledgeManager.baseRepository.getById(baseId) ?: return null
        val modelId = base.embeddingModelId?.let { id ->
            runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull() ?: return null
        } ?: settings.embeddingModelId ?: return null
        val model = settings.findModelById(modelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        if (providerSetting !is ProviderSetting.OpenAI) return null
        @Suppress("UNCHECKED_CAST")
        val provider = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting.OpenAI>
        return EmbeddingConfig(provider = provider, providerSetting = providerSetting, model = model)
    }

    /** 解析 rerank 模型（与 ChatService.resolveReranker 逻辑一致） */
    private fun resolveReranker(settings: Settings, rerankModelId: String?): Reranker? {
        if (rerankModelId == null) return null
        return runCatching {
            val model = settings.findModelById(kotlin.uuid.Uuid.parse(rerankModelId)) ?: return null
            val providerSetting = model.findProvider(settings.providers) ?: return null
            if (providerSetting !is ProviderSetting.OpenAI) return null
            @Suppress("UNCHECKED_CAST")
            val provider = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting.OpenAI>
            Reranker(provider, providerSetting, model)
        }.getOrNull()
    }
}
