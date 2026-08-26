package me.rerere.rikkahub.data.ai.discussion

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.tool.EmbeddingConfig
import me.rerere.knowledge.tool.KnowledgeSearchTool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createMcpCallTool
import me.rerere.rikkahub.data.ai.tools.createMcpListTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 群组讨论工具装配器：按成员 Assistant 配置装配工具，统一免审批。
 *
 * 成员是用户已授权创建的助手，讨论中视为可信执行器——工具自动执行，不等用户审批
 * （同 SubAgentToolAssembler 里 createWorkspaceTools(forceNoApproval = true) 的先例）。
 *
 * P0 工具集：搜索（enableWebSearch）+ MCP（mcpServers）+ 知识库（knowledgeBaseIds）
 * + localTools（assistant.localTools）+ workspace（workspaceId）。
 * P0 不做：memory（跨成员记忆会串扰）、study、skills。
 */
class DiscussionToolAssembler(
    private val mcpManager: McpManager,
    private val knowledgeManager: KnowledgeManager,
    private val workspaceRepository: WorkspaceRepository,
    private val localTools: LocalTools,
    private val providerManager: ProviderManager,
) {
    suspend fun assembleForMember(
        assistant: Assistant,
        settings: Settings,
        conversation: Conversation,
    ): List<Tool> = buildList {
        // 联网搜索
        if (assistant.enableWebSearch) {
            addAll(createSearchTools(settings, concise = true))
        }

        // MCP 工具（动态调度：mcp_list + mcp_call，避免全量 schema 注入；讨论组可信执行器免审批）
        // 仅当成员已绑定 MCP 服务器时才暴露；空绑定无工具可发现，不注入避免空工具占 token。
        if (assistant.mcpServers.isNotEmpty()) {
            add(createMcpListTool(assistant, mcpManager))
            add(createMcpCallTool(assistant, mcpManager, forceNoApproval = true))
        }

        // 知识库检索
        if (assistant.knowledgeBaseIds.isNotEmpty()) {
            val allowedIds = assistant.knowledgeBaseIds.map { it.toString() }.toSet()
            val rerankModelId = assistant.knowledgeBaseIds.firstOrNull()?.let { kbId ->
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

        // 本地工具
        if (assistant.localTools.isNotEmpty()) {
            addAll(localTools.getTools(assistant.localTools))
        }

        // workspace 沙盒
        val workspaceId = assistant.workspaceId?.toString()
        if (workspaceId != null) {
            val workspace = workspaceRepository.getById(workspaceId)
            if (workspace?.shellStatus == WorkspaceShellStatus.READY.name) {
                addAll(
                    createWorkspaceTools(
                        workspaceId = workspaceId,
                        workspaceRepository = workspaceRepository,
                        cwd = conversation.workspaceCwd,
                        forceNoApproval = true,
                    )
                )
            }
        }
    }

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
