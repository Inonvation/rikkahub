package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.buildGuidanceInstruction
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_HYDE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MULTIQUERY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_QUERY_REWRITE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeDepth
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeTone
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
import me.rerere.rikkahub.data.ai.prompts.promptOptimizeSystemPrompt
import me.rerere.rikkahub.data.ai.prompts.toDisplayText
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.PendingSteering
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createCreativeTools
import me.rerere.rikkahub.data.ai.tools.createProviderAdminTools
import me.rerere.rikkahub.data.ai.tools.createAssistantAdminTools
import me.rerere.rikkahub.data.ai.tools.createSettingsAdminTools
import me.rerere.rikkahub.data.ai.tools.createDataAdminTools
import me.rerere.rikkahub.data.ai.tools.createAuditTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceAdminTools
import me.rerere.rikkahub.data.ai.tools.createTrustedFolderAdminTools
import me.rerere.rikkahub.data.ai.tools.createKnowledgeAdminTools
import me.rerere.rikkahub.data.ai.tools.createConversationAdminTools
import me.rerere.rikkahub.data.ai.tools.createRollbackTools
import me.rerere.rikkahub.data.ai.tools.createMcpManagerTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createSubAgentTools
import me.rerere.rikkahub.data.ai.tools.isSubAgentPlaceholder
import me.rerere.rikkahub.data.ai.tools.subAgentResultPayload
import me.rerere.rikkahub.data.ai.tools.createTodoTool
import me.rerere.rikkahub.data.ai.tools.SubAgentCommands
import me.rerere.rikkahub.data.ai.tools.TodoReminderTransformer
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.ai.tools.StudyToolPermissions
import me.rerere.rikkahub.data.ai.tools.StudyTools
import me.rerere.rikkahub.data.ai.tools.createTrustedFolderTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.KnowledgeBaseReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.TrustedFolderReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.management.ManagementRollbackStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.promptOptimizePromptForScene
import me.rerere.rikkahub.data.datastore.promptOptimizeThinkingBudgetForScene
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.CompressedHistory
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.resolveConversationPolicy
import me.rerere.rikkahub.data.model.resolveModeRef
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.tool.EmbeddingConfig
import me.rerere.knowledge.tool.KnowledgeSearchTool
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal fun filterSkillToolsByMode(tools: List<Tool>, policy: ChatModePolicy): List<Tool> =
    tools.filter { tool ->
        if (tool.name == "use_skill") policy.allowSkillUse else policy.allowSkillAdmin
    }

private const val TAG = "ChatService"

/**
 * 子代理完成时，若母代理正在生成，最多等它结束本回合的时间（毫秒）。
 * 某些模型派发子代理后 SSE 挂起（不返回内容也不结束），母代理 job 永不结束、
 * isGenerating 永远 true——若 resume 直接丢弃，子代理结果会永久丢失。超时后取消旧 job 接管续答。
 */
private const val RESUME_WAIT_MS = 10_000L

/** resume 续答 job 的最大时长（毫秒）：超过视为续答生成挂起，取消以释放串行锁、避免锁死后继子代理 */
private const val RESUME_JOB_TIMEOUT_MS = 60_000L

/** /init 指令改写后的基础指令：让母代理探索工作区, 在 AGENTS.md 自动生成区(AUTOGEN 区)之后补充项目概况, 并更新 .agent 索引 */
private const val WORKSPACE_INIT_INSTRUCTION =
    "Please initialize the current workspace:\n" +
        "- Explore /workspace with workspace_list_files; note each directory's purpose.\n" +
        "- Read /workspace/.agent/AGENTS.md. Must not edit its auto-generated section (between <!-- AUTOGEN-BEGIN --> and <!-- AUTOGEN-END -->) — it's app-refreshed.\n" +
        "- Append/update a short 'Workspace Overview' AFTER <!-- AUTOGEN-END -->: workspace purpose, top-level directory roles, relevant toolchain.\n" +
        "- Create/update /workspace/.agent/INDEX.md as a quick layout index.\n" +
        "- Update the Project section of /workspace/.agent/MEMORY.md with a one-line summary."

/** /init 指令改写后的最终消息: 基础指令 + 用户 /init 后的可选说明 */
private fun workspaceInitInstruction(task: String): String = buildString {
    append(WORKSPACE_INIT_INSTRUCTION)
    if (task.isNotBlank()) {
        append("\nUser's note for this workspace: ")
        append(task.take(500))
    }
}

/**
 * 说明：子代理唤醒指令已改为注入 system prompt 末尾（BUG3 修复），不再作为尾部消息。
 * 历史版本曾用固定 marker id 的消息做尾部唤醒，现保留一段注释说明。
 */
internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/** 生成器回写的消息包含压缩摘要等 provider 专用消息，这里映射回展示历史：按 id 更新，新助手消息才追加。 */
internal fun displayMessagesForChunk(
    displayMessages: List<UIMessage>,
    chunkMessages: List<UIMessage>,
): List<UIMessage> {
    val result = displayMessages.toMutableList()
    chunkMessages.forEach { message ->
        val index = result.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            result[index] = message
        } else if (message.role == MessageRole.ASSISTANT) {
            result.add(message)
        }
    }
    return result
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val trustedFolderRepository: TrustedFolderRepository,
    private val folderRepository: FolderRepository,
    private val knowledgeManager: KnowledgeManager,
    private val todoStorage: TodoStorage,
    private val studyTools: StudyTools,
    private val subAgentRunner: SubAgentRunner,
    private val discussionToolAssembler: me.rerere.rikkahub.data.ai.discussion.DiscussionToolAssembler,
    private val groupRepository: GroupRepository,
    private val json: kotlinx.serialization.json.Json,
    private val managementAuditStore: ManagementAuditStore,
    private val managementRollbackStore: ManagementRollbackStore,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val trustedFolderReminderTransformer = TrustedFolderReminderTransformer(trustedFolderRepository)

    // 知识库系统提示注入
    private val knowledgeBaseReminderTransformer = KnowledgeBaseReminderTransformer(knowledgeManager)

    // Todo 被动提醒注入
    private val todoReminderTransformer = TodoReminderTransformer(todoStorage)

    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
            todoReminderTransformer,
        )
    }
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    // ---- 群组讨论 ----

    private val discussionOrchestrator by lazy {
        me.rerere.rikkahub.data.ai.discussion.GroupDiscussionOrchestrator(
            settingsStore = settingsStore,
            providerManager = providerManager,
            toolAssembler = discussionToolAssembler,
            json = json,
            read = { conversationId -> getConversationFlow(conversationId).value },
            update = { conversationId, transform -> updateConversationState(conversationId, transform) },
            persist = { conversationId, conversation ->
                saveConversation(conversationId, conversation)
                // 群组有活动 → 让群组中心按活跃度排序
                conversation.groupId?.let { groupId ->
                    runCatching { groupRepository.touchUpdateAt(groupId) }
                }
            },
            readGroup = { groupId -> groupRepository.getGroupById(groupId) },
        )
    }

    fun getDiscussionStateFlow(conversationId: Uuid): kotlinx.coroutines.flow.StateFlow<me.rerere.rikkahub.data.ai.discussion.DiscussionState> {
        return discussionOrchestrator.state(conversationId)
    }

    /** 清理某会话的讨论内存态（会话/群组被删除时调用，防残留脏状态） */
    fun clearDiscussionState(conversationId: Uuid) {
        discussionOrchestrator.clearConversation(conversationId)
    }

    /**
     * 恢复/继续讨论。生成中调用仅设 hint（下一轮消费，不重复启动）。
     * 暂停/完成后调用会从 transcript 重新推导下一位续跑，不重复已完成的轮次。
     */
    fun resumeDiscussion(conversationId: Uuid, nextSpeakerId: kotlin.uuid.Uuid? = null) {
        discussionOrchestrator.setNextSpeakerHint(conversationId, nextSpeakerId)
        val session = sessions[conversationId] ?: return
        if (session.isGenerating) return
        val job = appScope.launch {
            runCatching { discussionOrchestrator.runDiscussion(conversationId) }
        }
        session.setJob(job)
    }

    /** 指定下一位发言者（讨论页控制条用） */
    fun setNextSpeaker(conversationId: Uuid, memberId: kotlin.uuid.Uuid) {
        resumeDiscussion(conversationId, memberId)
    }

    /**
     * 仅设置下一位发言人 hint，不触发启动（供"@成员名 发送"流程用：
     * 先设 hint，随后 sendMessage 追加用户消息并 runDiscussion 时消费该 hint，指定成员先发言）。
     */
    fun setNextSpeakerHintOnly(conversationId: Uuid, memberId: kotlin.uuid.Uuid?) {
        discussionOrchestrator.setNextSpeakerHint(conversationId, memberId)
    }

    /**
     * 新建群组：写 Group + 首个空会话，**不自动开始**。
     * 进入讨论页后输入主题发送才启动。返回 groupId（== 首个会话 id，供导航/编辑复用）。
     */
    suspend fun createGroup(
        title: String,
        config: me.rerere.rikkahub.data.model.DiscussionConfig,
    ): Uuid {
        val groupId = Uuid.random()
        val now = java.time.Instant.now()
        groupRepository.insert(
            me.rerere.rikkahub.data.model.Group(
                id = groupId,
                name = title,
                config = config,
                createAt = now,
                updateAt = now,
            )
        )
        // 首个会话：group_id = groupId（用 insertConversation，saveConversation 会跳过空新会话）
        val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
            id = groupId,
            assistantId = me.rerere.rikkahub.data.model.GROUP_DISCUSSION_ASSISTANT_ID,
        ).copy(
            groupId = groupId,
        )
        conversationRepo.insertConversation(conversation)
        val session = getOrCreateSession(groupId)
        updateConversation(groupId, conversation)
        return groupId
    }

    /** 在群组下新建一场空对话，返回会话 id */
    suspend fun createGroupConversation(groupId: Uuid): Uuid {
        val conversationId = Uuid.random()
        val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
            id = conversationId,
            assistantId = me.rerere.rikkahub.data.model.GROUP_DISCUSSION_ASSISTANT_ID,
        ).copy(
            groupId = groupId,
        )
        conversationRepo.insertConversation(conversation)
        val session = getOrCreateSession(conversationId)
        updateConversation(conversationId, conversation)
        return conversationId
    }

    /**
     * 更新群组配置（改名 / 成员 / 模式 / 轮数 / 风格等）。
     * 写 Group（全量覆盖 config_json），群下所有会话共享。
     * 调用方负责：若群下正在生成需先 stopGroupGeneration 暂停。
     */
    suspend fun updateGroupConfig(
        groupId: Uuid,
        title: String,
        config: me.rerere.rikkahub.data.model.DiscussionConfig,
    ) {
        val existing = groupRepository.getGroupById(groupId) ?: return
        groupRepository.update(
            existing.copy(
                name = title,
                config = config,
                updateAt = java.time.Instant.now(),
            )
        )
    }

    /** 群组流缓存：同一 groupId 复用同一 StateFlow。避免每次调用新建 stateIn(appScope)
     *  泄漏一条常驻 Room 收集协程（流式生成期间 getGroupFlow 被高频调用时尤其严重），
     *  并让讨论页/详情页/编辑页共享同一份数据。 */
    private val groupFlowCache = ConcurrentHashMap<Uuid, kotlinx.coroutines.flow.StateFlow<me.rerere.rikkahub.data.model.Group?>>()

    /** 群组流（Room 驱动，自动刷新） */
    fun getGroupFlow(groupId: Uuid): kotlinx.coroutines.flow.StateFlow<me.rerere.rikkahub.data.model.Group?> {
        return groupFlowCache.getOrPut(groupId) {
            groupRepository.getGroupFlow(groupId)
                .stateIn(appScope, SharingStarted.Eagerly, null)
        }
    }

    /** 停掉群组下所有正在进行的生成 */
    suspend fun stopGroupGeneration(groupId: Uuid) {
        conversationRepo.getConversationsOfGroupOnce(groupId).forEach { conv ->
            stopGeneration(conv.id)
        }
    }

    /** 删除群组：先停全部生成，再逐个删除会话（级联清理），最后删 Group */
    suspend fun deleteGroup(groupId: Uuid) {
        stopGroupGeneration(groupId)
        conversationRepo.getConversationsOfGroupOnce(groupId).forEach { conv ->
            discussionOrchestrator.clearConversation(conv.id)
            conversationRepo.deleteConversation(conv)
        }
        groupRepository.deleteGroup(groupId)
        // 清掉缓存的群组流，防止已删群组的 id 复用时拿到旧流
        groupFlowCache.remove(groupId)
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- 子代理占位回填 ----

    /**
     * 生成结束后，把仍处于占位（dispatched/queued）的 spawn_subagent Tool.output 回写成终态 JSON 落库。
     * 幂等：占位被回写成终态后 isSubAgentPlaceholder=false，重复调用跳过。
     * 为什么在生成后而非工具内：Conversation.updateCurrentMessages 会在每次 chunk 到达时用内存态
     * 覆盖会话，工具内直接写 repo 会被覆盖丢失。生成结束是唯一稳定时机。
     */
    private suspend fun backfillSubAgentPlaceholders(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var changed = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { msg ->
                    msg.copy(
                        parts = msg.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.toolName == "spawn_subagent") {
                                // taskId == spawn 的 toolCallId（__toolCallId 注入对齐）
                                val task = subAgentRunner.getTask(part.toolCallId)
                                if (task != null && task.status.isTerminal && isSubAgentPlaceholder(part.output)) {
                                    changed = true
                                    part.copy(
                                        output = listOf(
                                            UIMessagePart.Text(subAgentResultPayload(task).toString())
                                        )
                                    )
                                } else part
                            } else part
                        }
                    )
                }
            )
        }
        if (changed) {
            saveConversation(
                conversationId,
                conversation.copy(
                    messageNodes = updatedNodes,
                    compressedHistory = null,
                )
            )
        }
    }

    /**
     * 子代理完成：往派发它的 assistant 消息里、spawn 气泡之后追加完成气泡 part（落库）。
     *
     * 与 backfillSubAgentPlaceholders 同思路：按 taskId 定位派发消息（spawn toolCallId == taskId），
     * 在其 parts 末尾追加 spawn_subagent_completed tool part。渲染走现有工具气泡管线
     * （ToolUIRegistry → SubAgentCompletedToolUI，toolCallId == taskId 点击进详情页）。
     *
     * 关键时序：必须在 resume 的 handleMessageComplete 读会话快照**之前**落库，否则续答流式
     * updateCurrentMessages(chunk.messages) 会用不含气泡 part 的快照把它覆盖掉。
     * 幂等：该 taskId 的完成气泡已存在则跳过（重进会话/重复 resume 不重复插入）。
     */
    private suspend fun insertSubAgentCompletionPart(
        conversationId: Uuid,
        task: SubAgentTask,
    ) {
        val conversation = getConversationFlow(conversationId).value
        var changed = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { msg ->
                    val hasSpawn = msg.parts.any {
                        it is UIMessagePart.Tool && it.toolName == "spawn_subagent"
                    }
                    val alreadyInserted = msg.parts.any {
                        it is UIMessagePart.Tool &&
                            it.toolName == "spawn_subagent_completed" &&
                            // 新格式：input 里带 taskId；旧格式：toolCallId == taskId（兼容历史数据）
                            (it.inputAsJson().jsonObject["taskId"]?.jsonPrimitive?.contentOrNull == task.taskId ||
                                it.toolCallId == task.taskId)
                    }
                    if (hasSpawn && !alreadyInserted) {
                        changed = true
                        // 追加到 AI 消息末尾（spawn 派发 + AI 已输出文本之后）作收尾：
                        // 气泡排在消息后部，AI 收到结果后的续答文本自然继续跟在它后面，
                        // 阅读顺序即执行顺序（派发 → AI 继续输出 → 子代理完成 → 续答）。
                        // 注意：续答流式合并发生在落库之后，会追加在气泡后方，不影响本顺序。
                        // toolCallId 用独立随机 id：spawn 工具的 toolCallId 已被模型占用，
                        // 复用会导致同一条消息里两个工具共享同一 tool_call_id，OpenAI 兼容 API
                        // 校验重复报 "Duplicate value for 'tool_call_id'"。跳详情页改从 input.taskId 读取。
                        msg.copy(
                            parts = msg.parts + UIMessagePart.Tool(
                                toolCallId = Uuid.random().toString(),
                                toolName = "spawn_subagent_completed",
                                startedAt = task.startedAt,
                                finishedAt = task.finishedAt,
                                input = buildJsonObject {
                                    put("taskId", JsonPrimitive(task.taskId))
                                    put("agentId", task.agentId)
                                    put("status", task.status.name.lowercase())
                                    put("summary", task.resultSummary.orEmpty())
                                    // 子代理 token 用量与执行时长：落库随气泡持久化，完成气泡下方展示。
                                    // 终态时 task.usage 已跨步骤累加完整，durationMillis = finishedAt - startedAt。
                                    put("promptTokens", JsonPrimitive(task.usage?.promptTokens ?: 0))
                                    put("completionTokens", JsonPrimitive(task.usage?.completionTokens ?: 0))
                                    put("cachedTokens", JsonPrimitive(task.usage?.cachedTokens ?: 0))
                                    put("durationMs", JsonPrimitive(task.durationMillis ?: 0L))
                                }.toString(),
                                output = listOf(
                                    UIMessagePart.Text(subAgentResultPayload(task).toString())
                                ),
                                approvalState = ToolApprovalState.Approved,
                            )
                        )
                    } else msg
                }
            )
        }
        if (changed) {
            saveConversation(
                conversationId,
                conversation.copy(
                    messageNodes = updatedNodes,
                    compressedHistory = null,
                )
            )
        }
    }

    // ---- 子代理完成 → 异步唤醒母代理 ----

    /** 已触发过唤醒续答的 taskId 集合（去重：同一任务只唤醒一次） */
    private val resumedTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 正在执行续答的会话集合：同一会话同时只允许一个 resume 在跑，避免并发续答互相覆盖 */
    private val resumingConversations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

    init {
        // 订阅子代理完成事件：任一子代理终态（有结果）→ 唤醒其父会话的母代理续答。
        // 订阅放 ChatService 构造（DI createdAtStart），确保任何任务完成前已就绪。
        appScope.launch {
            subAgentRunner.taskCompletedFlow.collect { task ->
                runCatching {
                    resumeAfterSubAgent(task)
                }.onFailure { Log.w(TAG, "resumeAfterSubAgent failed", it) }
            }
        }
    }

    /**
     * 子代理完成 → 唤醒母代理续答。
     *
     * 母代理派发子代理后可自由结束回合；子代理完成后这里自动注入结果并唤醒续答，
     * 让母代理基于新结果补充/修正回答（子代理完成事件驱动，无需母代理 await）。
     *
     * 流程：
     * 1. 防重入：母代理正在生成（isGenerating）→ 跳过且**不标记已消费**（由回合结束兜底补唤醒）。
     * 2. 串行化：同一会话已有 resume 在跑 → 跳过（该 resume 回合结束后的兜底会补唤醒后续）。
     * 3. 去重：同一 taskId 已消费 → 跳过。
     * 4. 会话不存在/无子代理消息 → 丢弃。
     * 5. 注入唤醒提示（作为生成上下文，不落库、用户不可见）→ 调 handleMessageComplete 续答。
     * 6. 标记已消费 + 释放串行锁。
     */
    private suspend fun resumeAfterSubAgent(task: SubAgentTask) {
        val conversationId = task.parentConversationId
        Log.i(TAG, "resume enter: task=${task.taskId} status=${task.status} conv=$conversationId")

        // 防重入：母代理正在生成时，**有界等待**它结束本回合再续答，而不是直接丢弃靠兜底。
        // 兜底只在母代理回合正常结束（onSuccess）时触发；某些模型派发子代理后 SSE 挂起
        // （不返回内容也不结束），母代理 job 永不结束、isGenerating 永远 true，若此处 return，
        // 子代理结果会被永久挡在门外（"子代理空转、结果接不到"）。有界等待 + 超时取消旧 job
        // 强制接管，保证子代理结果必达。正常回合几秒内结束，等待不打断；挂起时超时接管。
        val session = sessions[conversationId]
        if (session?.isGenerating == true) {
            Log.w(TAG, "resume: parent generating, waiting <= ${RESUME_WAIT_MS}ms (task=${task.taskId})")
            val runningJob = session.getJob()
            val waitStartedAt = SystemClock.elapsedRealtime()
            withTimeoutOrNull(RESUME_WAIT_MS) { runningJob?.join() }
            if (session.isGenerating) {
                // 等待期间父回合有新的流式输出/工具结果落地：说明它在正常产出，
                // 只是较慢（超长生成/多轮工具/思考模型），不能掐断——否则用户看到回复被中断。
                val hadActivity = session.lastGenerationActivityAt > waitStartedAt
                if (hadActivity) {
                    Log.w(
                        TAG,
                        "resume: parent still producing output after wait, NOT cancelling (task=${task.taskId})"
                    )
                } else {
                    Log.w(
                        TAG,
                        "resume: parent hung (no output during wait), cancelling old job to take over (task=${task.taskId})"
                    )
                    // 母代理回合挂起（SSE 不返回内容也不结束）：取消旧 job 强制接管续答，结果不丢失
                    session.getJob()?.cancel()
                    runCatching { session.getJob()?.join() }
                }
            }
        }

        // 串行化：同一会话同时只跑一个 resume，避免多子代理相继完成时并发续答互相覆盖
        if (!resumingConversations.add(conversationId)) {
            Log.w(TAG, "resume SKIPPED: another resume in progress (conv=$conversationId task=${task.taskId})")
            return
        }

        try {
            if (!resumedTaskIds.add(task.taskId)) {
                Log.w(TAG, "resume SKIPPED: task already resumed (task=${task.taskId})")
                return  // 已消费过
            }

            // 会话不存在（已删除/从未打开）→ 丢弃
            val conversation = runCatching { getConversationFlow(conversationId).value }.getOrNull() ?: return

            // 该会话没有对应子代理消息（异常情况）→ 不续答
            val hasSpawn = conversation.messageNodes.flatMap { it.messages }
                .flatMap { it.parts }
                .any { it is UIMessagePart.Tool && it.toolName == "spawn_subagent" }
            if (!hasSpawn) {
                Log.w(TAG, "resume SKIPPED: no spawn part in conversation (conv=$conversationId task=${task.taskId})")
                return
            }

            // 关键：先生成前回填该子代理的 spawn 占位为终态结果——否则 resume 生成读到的
            // 还是 "dispatched" 占位，模型看不到真实结果，只能瞎编/重复旧答（onSuccess 才回填就晚了）。
            // backfillSubAgentPlaceholders 幂等：已回填的跳过。
            backfillSubAgentPlaceholders(conversationId)

            // 子代理完成气泡：往派发它的 assistant 消息里追加 spawn_subagent_completed 气泡 part。
            // 必须在 handleMessageComplete 读会话快照**之前**落库，续答流式才不会把它覆盖掉；
            // 渲染走现有工具气泡管线，续答文本（BUG3 已合并进同一消息）自然在其之后。
            // 气泡对**任意轮次**的子代理都插入（UI 展示完整）；下面的轮次锚点只拦截"注入 AI 上下文"。
            insertSubAgentCompletionPart(conversationId, task)

            // 轮次锚点：只对"最新用户消息之后派发"的子代理唤醒续答。
            // 旧轮次（最新用户消息之前派发）的子代理结果不该注入新轮次——否则用户发起
            // 第二轮对话后，第一轮子代理完成时这里会取消第二轮的生成 job 强行注入旧结果，
            // 导致返回信息重复、AI 反复读取同一批结果。完成气泡/占位回填已在上方完成，
            // 这里 return 前任务已被 resumedTaskIds 标记消费，兜底补唤醒也不会再触发它。
            if (!isSpawnInCurrentRound(conversation, task.taskId)) {
                Log.w(TAG, "resume SKIPPED: task from previous round (task=${task.taskId})")
                return
            }

            Log.i(TAG, "resumeAfterSubAgent: task=${task.taskId} status=${task.status} conversation=$conversationId")

            // 唤醒指令：作为 resume 上下文注入，handleMessageComplete 会把 resumeContext
            // 拼进 system prompt 末尾（BUG3 修复），模型据此续答、并入同一条 assistant 消息。
            // 摘要直接内联在指令里，不依赖 spawn tool result 的可见性——长对话被 limitContext
            // 截断或模型忽略工具输出时，结果仍能送达模型，杜绝"结果接了模型却没看到"。
            val agentName = SubAgentCatalog.byId(task.agentId)?.name ?: task.agentId
            val resumePrompt = if (task.status == SubAgentStatus.CANCELLED) {
                // 手动取消：告诉母代理"用户取消了该子代理"，让它知悉并决定如何继续。
                // 取消任务通常没有结果摘要，不内联 summary（避免误导模型以为有产出）。
                buildString {
                    appendLine("## Sub-agent cancelled by user")
                    appendLine("The sub-agent \"$agentName\" (${task.agentId}) was manually cancelled by the user before it finished.")
                    appendLine("- Acknowledge this cancellation in your reply; do NOT pretend it succeeded.")
                    appendLine("- If the cancelled sub-agent was providing partial value, state what is still missing.")
                    appendLine("- Decide how to continue: answer with what you already have, or tell the user the task was stopped.")
                    appendLine("- Do NOT re-answer from scratch; build on your existing answer.")
                }
            } else {
                buildString {
                    appendLine("## Sub-agent result arrived")
                    appendLine("The sub-agent \"$agentName\" (${task.agentId}) has completed with status \"${task.status.name.lowercase()}\".")
                    task.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                        appendLine("Its result summary:")
                        appendLine("```")
                        appendLine(summary.take(3000))
                        appendLine("```")
                    }
                    appendLine("- If your previous answer is already complete, supplement or refine it with this new information.")
                    appendLine("- If you were waiting for other sub-agents, you may continue waiting, or answer based on what you have now.")
                    appendLine("- Do NOT re-answer from scratch; build on your existing answer.")
                }
            }

            // 与 sendMessage/regenerate/approval 一致：注册标准生成 job，让 resume 期间
            // isGenerating/停止按钮/stopGeneration/自动滚动全部生效（BUG4：resume 可中断）。
            // job.join() 保持 resumingConversations 串行化——否则 finally 提前释放锁，
            // 后续完成的子代理会并发 resume 互相 setJob 取消。
            // 注意：不加 _generationDoneFlow.emit（避免每次 resume 重复触发 haptic/TTS）。
            val session = getOrCreateSession(conversationId)
            val previousJob = session.getJob()
            previousJob?.cancel()
            val job = appScope.launch {
                try {
                    runCatching { previousJob?.join() }
                    handleMessageComplete(conversationId, resumeContext = resumePrompt)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    addError(e, conversationId, title = context.getString(R.string.error_title_generation))
                }
            }
            session.setJob(job)
            Log.i(TAG, "resume job started: task=${task.taskId}")
            // resume 续答超时保护：若续答生成时模型 SSE 挂起（与派发后挂起同因），job 永不结束，
            // resumingConversations 锁被永久占用，后续完成的子代理全部卡死（并行多子代理丢失的变种）。
            // 超时后取消该 resume job，释放锁，让后续子代理能独立补唤醒（scheduleRoundEndResume 兜底）。
            withTimeoutOrNull(RESUME_JOB_TIMEOUT_MS) { job.join() } ?: run {
                Log.w(TAG, "resume job TIMEOUT, cancelling: task=${task.taskId}")
                job.cancel()
                runCatching { job.join() }
            }
            Log.i(TAG, "resume job done: task=${task.taskId}")
        } finally {
            resumingConversations.remove(conversationId)
        }
    }

    /**
     * 判断子代理（taskId == 派发它的 spawn 气泡的 toolCallId）是否派发于"最新用户消息之后"，
     * 即是否属于**当前轮次**。用于 resume 只唤醒本轮派发的子代理：
     * 用户发起新一轮对话后，上一轮派发的子代理完成时不应打断/污染新一轮生成。
     * spawn 气泡所在 node 若在最新用户消息 node 之后 → 当前轮次。
     */
    private fun isSpawnInCurrentRound(conversation: Conversation, taskId: String): Boolean {
        val lastUserIndex = conversation.messageNodes.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return false
        val spawnIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { msg ->
                msg.parts.any {
                    it is UIMessagePart.Tool && it.toolName == "spawn_subagent" && it.toolCallId == taskId
                }
            }
        }
        return spawnIndex > lastUserIndex
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid, initialMode: String? = null) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        // 内存态已有内容时跳过 Room 覆盖：生成期间消息只存内存、尚未落库，
        // 切回正在生成的会话若用 Room 旧快照覆盖，AI 回复/工具气泡会短暂消失，
        // 要等下一个流式 chunk 才恢复（工具思考/审批暂停时可能长时间无内容）。
        // 仅当 session 为空（首次进入/被 idle 回收后重建）才从 Room 加载。
        if (session.state.value.messageNodes.isNotEmpty()) return
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            // 群组讨论会话用哨兵 assistantId，不更新"当前助手"全局状态
            if (!conversation.isGroupDiscussion) {
                settingsStore.updateAssistant(conversation.assistantId)
            }
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            )
                // 新对话继承助手上次选择的默认工作目录
                .copy(
                    workspaceCwd = assistant.defaultWorkspaceCwd,
                    // 有显式默认模式时固化快照；无显式默认时保持 null，会话跟随助手配置
                    mode = initialMode ?: resolveModeRef(
                        assistant = assistant,
                        settings = currentSettings,
                        trustedFolderActive = trustedFolderRepository.currentSettings().activeProjectId != null,
                    ),
                )
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    /**
     * 生成中发送消息的统一入口：会话正在生成时把消息排入队列（不打断当前流式），
     * 当前回合正常结束后自动发送；空闲时直接发送。带附件消息无法走 steering 文本引导，
     * 只能走这个排队机制——避免「生成中发图片静默取消当前回复」（#17）。
     */
    fun sendMessageQueued(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
    ) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            session.pendingSendQueue.value =
                session.pendingSendQueue.value + PendingSendItem(content = content, answer = answer)
        } else {
            sendMessage(conversationId, content, answer = answer)
        }
    }

    /** 回合正常结束后自动发送排队的待发消息（join 当前 job 确保其彻底结束，避免 setJob 取消正在收尾的 job） */
    private fun scheduleDrainPendingSend(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.pendingSendQueue.value.isEmpty()) return
        if (session.pendingSendDrainJob?.isActive == true) return
        session.pendingSendDrainJob = appScope.launch {
            try {
                session.getJob()?.join()
                // 等待期间用户若发起了新操作，sendMessage 会清空队列 → 这里拿到空就退出，避免抢占
                while (true) {
                    // 先等引导 drain 结束，避免两条 drain 互相 setJob 取消对方刚启动的生成。
                    while (session.steeringDrainJob?.isActive == true) {
                        session.steeringDrainJob?.join()
                    }
                    // 等待期间用户可能已发送新消息/取消排队，重新取队首，避免发送已失效的条目。
                    val item = session.pendingSendQueue.value.firstOrNull() ?: break
                    session.pendingSendQueue.value = session.pendingSendQueue.value.drop(1)
                    sendMessage(
                        conversationId = conversationId,
                        content = item.content,
                        answer = item.answer,
                        clearPendingQueue = false,
                    )
                    val job = session.getJob()
                    job?.join()
                    if (job?.isCancelled == true) break
                }
            } finally {
                session.pendingSendDrainJob = null
            }
        }
    }

    /** 取消一条排队中的待发送消息（UI 卡片点叉时调用） */
    fun cancelPendingSend(conversationId: Uuid, itemId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.pendingSendQueue.value = session.pendingSendQueue.value.filterNot { it.id == itemId }
    }

    /** 订阅会话的待发送消息队列（UI 渲染卡片用），按入队顺序排列 */
    fun getPendingSendFlow(conversationId: Uuid): Flow<List<PendingSendItem>> {
        val session = getOrCreateSession(conversationId)
        return session.pendingSendQueue
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        clearPendingQueue: Boolean = true,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        // 用户主动新发送覆盖旧的未送达排队消息（如用户停止后残留的 pending）；
        // drain 内部发送传 false，保证队列里剩余的条目继续按序发送。
        if (clearPendingQueue) session.pendingSendQueue.value = emptyList()
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()

                // 群组讨论：跳过助手正则预处理，直接追加用户消息，交给调度器继续讨论
                if (currentConversation.isGroupDiscussion) {
                    // 首次话题：会话标题为空时用首条文本截断做标题（历史列表可读）；
                    // 纯附件（无文字）时用占位标题，避免群组历史列表显示"尚未开始"。
                    val firstText = content.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text.orEmpty().trim()
                    val title = if (currentConversation.title.isBlank() && firstText.isNotEmpty()) {
                        firstText.take(30)
                    } else if (currentConversation.title.isBlank() && firstText.isEmpty()) {
                        "[图片/附件]"
                    } else {
                        currentConversation.title
                    }
                    val newConversation = currentConversation.copy(
                        title = title,
                        updateAt = java.time.Instant.now(),
                        messageNodes = currentConversation.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = content,
                        ).toMessageNode(),
                    )
                    saveConversation(conversationId, newConversation)
                    if (answer) {
                        runCatching { discussionOrchestrator.runDiscussion(conversationId) }
                    }
                    _generationDoneFlow.emit(conversationId)
                    return@launch
                }

                val processedContent = preprocessUserInputParts(content, assistant)

                val rawText = processedContent.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
                // 工作区初始化指令 /init [说明]：改写为让母代理探索并生成 .agent/INDEX.md、更新 MEMORY，
                // 改写前由 app 确保环境配置(AGENTS/MEMORY/notes)就绪。需已绑定可用工作区。
                // 与子代理命令同风格解析：命令词取首个空格前, 其后内容作为初始化说明。
                val isInitCommand = answer && rawText?.trim()?.let { it.substringBefore(' ') == "/init" } == true
                val initTask = rawText?.trim()?.removePrefix("/init")?.trim() ?: ""

                // 子代理指令（/search xxx 等）：把指令改写成明确的普通用户消息再落库，
                // 母代理走正常生成流程——思考、spawn_subagent 派发、子代理后台跑、完成时
                // 自动唤醒续答返回。支持一条消息多个指令（换行分隔）。
                val cmds = if (answer && !isInitCommand) {
                    rawText?.let { SubAgentCommands.parseAll(it) }?.ifEmpty { null }
                } else null
                val settingsNow = settingsStore.settingsFlow.first()
                val effectiveContent = when {
                    // /init：先确保 .agent 环境配置，再改写为初始化指令让母代理执行
                    isInitCommand -> {
                        val workspaceId = assistant.workspaceId?.toString()
                        val workspace = workspaceId?.let { workspaceRepository.getById(it) }
                        if (workspace == null || workspace.shellStatus != WorkspaceShellStatus.READY.name) {
                            addError(
                                IllegalStateException(context.getString(R.string.error_workspace_not_bound)),
                                conversationId,
                                title = context.getString(R.string.error_title_workspace),
                            )
                            return@launch
                        }
                        runCatching { workspaceRepository.ensureAgentsFile(workspaceId) }
                        runCatching { workspaceRepository.ensureMemoryIndex(workspaceId) }
                        processedContent.map { part ->
                            if (part is UIMessagePart.Text) part.copy(text = workspaceInitInstruction(initTask)) else part
                        }
                    }
                    // cmds 非空时 rewriteToInstruction 必然返回改写文本（同源解析），此处兜底保持原样
                    cmds != null && settingsNow.enableSubAgent -> {
                        val instruction = rawText?.let { SubAgentCommands.rewriteToInstruction(it) }
                        processedContent.map { part ->
                            if (part is UIMessagePart.Text && instruction != null) {
                                part.copy(text = instruction)
                            } else part
                        }
                    }
                    else -> processedContent
                }
                if (cmds != null && !settingsNow.enableSubAgent) {
                    // 子代理未开启：提示用户先去 Agent Action 设置页开启，不执行
                    addError(
                        IllegalStateException(context.getString(R.string.error_sub_agent_disabled)),
                        conversationId,
                        title = context.getString(R.string.error_title_sub_agent),
                    )
                }

                // 添加消息到列表（指令已改写成"请使用子代理"的普通消息）
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = effectiveContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    // ---- 引导消息 ----

    /**
     * 向主 AI 发送引导消息：不新增独立用户消息，而是把引导文本以可见工具气泡合并进
     * 最后一条 assistant 消息，并注入 AI 上下文让它按引导继续生成（并入同一个气泡）。
     *
     * AI 正在生成时按 [immediate] 分两种模式：
     * - immediate=false（默认）：引导进入 steering 队列，等当前回合输出完成后由
     *   drain 依次自动注入并续答（自动引导）。
     * - immediate=true（排队引导旁的「立即发送」）：把该项标记为立即注入，
     *   GenerationHandler 在下一轮边界（工具调用完成/输出结束）消费注入。
     * 队列里已有排队引导时，再次调用本方法会清空旧队列、打断当前生成并直接注入新引导，
     * 避免引导越积越多、用户的新指令迟迟不生效。
     *
     * 实现复用 [handleMessageComplete] 的 resumeContext 续答机制：resumeContext 追加为
     * provider 看到的最后一条 USER 消息、不落持久化列表，handleMessageChunk 仍并入上一条
     * assistant 消息——正好满足"引导合并进 AI 气泡、不单独成条、继续生成"。
     *
     * 运行中子代理的引导走 [me.rerere.rikkahub.data.ai.subagent.SubAgentRunner.submitGuidance]
     * （详情页入口，每步注入子代理思维链），与本方法互不干扰。
     */
    fun sendGuidance(
        conversationId: Uuid,
        text: String,
        /** true = 排队引导旁的「立即发送」，GenerationHandler 在下一轮边界立即注入；
         *  false = 默认排队，等当前回合输出完成后依次自动注入。 */
        immediate: Boolean = false,
    ) {
        if (text.isBlank()) return
        val session = getOrCreateSession(conversationId)
        if (session.state.value.isGroupDiscussion) return

        val runningJob = session.getJob()
        // AI 正在生成：不打断当前流式请求（打断会触发 OkHttp "stream was reset: CANCEL"，
        // 且被迫从头重生成反应慢）。把引导写入 steering 队列：immediate=true 由
        // GenerationHandler 在下一轮边界消费；其余排队等当前回合结束后由 drain 依次注入。
        if (runningJob != null && runningJob.isActive) {
            // 已有排队引导时再次发送：直接打断当前任务并立即注入新引导，避免排队越积越多。
            if (session.steeringQueue.value.isNotEmpty()) {
                interruptGuidanceAndSend(session, conversationId, text)
                return
            }
            session.steeringQueue.value = session.steeringQueue.value + PendingSteering(
                text = text,
                immediate = immediate,
            )
            ensureSteeringDrain(session, conversationId)
            return
        }

        // AI 空闲：直接注入续答（setJob 让续答可被停止按钮中断）
        val job = appScope.launch {
            try {
                appendGuidancePart(conversationId, text)
                handleMessageComplete(conversationId, resumeContext = buildGuidanceInstruction(text))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    /** 排队引导卡片上的「打断并发送」：清空排队，中断当前生成，直接注入该引导 */
    fun sendGuidanceInterrupt(conversationId: Uuid, text: String) {
        val session = sessions[conversationId] ?: return
        if (session.state.value.isGroupDiscussion) return
        interruptGuidanceAndSend(session, conversationId, text)
    }

    private fun interruptGuidanceAndSend(
        session: ConversationSession,
        conversationId: Uuid,
        text: String,
    ) {
        if (text.isBlank()) return
        // 旧的排队引导已被用户的最新指令取代，直接清空；同时停掉 drain，避免它把剩余项再捞回来。
        session.steeringQueue.value = emptyList()
        session.steeringDrainJob?.cancel()
        appScope.launch {
            try {
                stopGeneration(conversationId)
                // stopGeneration 只 join 到 job 结束，invokeOnCompletion 清空 job 引用可能稍晚，
                // 等引用真正清空后再注册新 job，避免新 job 被 setJob 的“取消旧 job”逻辑误杀。
                withTimeoutOrNull(2_000) {
                    while (session.getJob() != null) delay(20)
                }
                appendGuidancePart(conversationId, text)
                val job = appScope.launch {
                    try {
                        handleMessageComplete(conversationId, resumeContext = buildGuidanceInstruction(text))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
                    }
                }
                session.setJob(job)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
    }

    /** 取消排队中的引导：从队列移除指定项（UI 气泡随之消失） */
    fun cancelPendingGuidance(conversationId: Uuid, itemId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.steeringQueue.value = session.steeringQueue.value.filterNot { it.id == itemId }
    }

    /** 订阅会话的排队引导队列（UI 渲染气泡用），按入队顺序排列 */
    fun getPendingGuidanceFlow(conversationId: Uuid): Flow<List<PendingGuidanceItem>> {
        val session = getOrCreateSession(conversationId)
        return session.steeringQueue.map { list ->
            list.map { PendingGuidanceItem(id = it.id, text = it.text) }
        }
    }

    /** 确保 steering 队列被串行消费：生成中每次入队后调用；已有 drain 在跑则跳过 */
    private fun ensureSteeringDrain(session: ConversationSession, conversationId: Uuid) {
        if (session.steeringDrainJob?.isActive == true) return
        session.steeringDrainJob = appScope.launch {
            drainSteeringQueue(conversationId, session)
        }
    }

    /**
     * 串行消费 steering 队列：等当前回合（job）结束后取队首注入为 user_guidance 气泡并续答，
     * 注入产生的下一回合结束后再取下一个，直到队列清空。用户停止生成时丢弃剩余排队项，
     * 避免幽灵重启生成（#14）。
     */
    private suspend fun drainSteeringQueue(conversationId: Uuid, session: ConversationSession) {
        try {
            while (true) {
                val currentJob = session.getJob()
                currentJob?.join()
                if (currentJob?.isCancelled == true) {
                    // 用户手动停止生成：丢弃剩余排队引导
                    session.steeringQueue.value = emptyList()
                    break
                }
                val item = session.steeringQueue.value.firstOrNull() ?: break
                session.steeringQueue.value = session.steeringQueue.value.drop(1)
                appendGuidancePart(conversationId, item.text)
                val job = appScope.launch {
                    runCatching {
                        handleMessageComplete(conversationId, resumeContext = buildGuidanceInstruction(item.text))
                    }
                }
                session.setJob(job)
                job.join()
                if (job.isCancelled) {
                    // 注入产生的回合被用户停止：丢弃剩余排队引导
                    session.steeringQueue.value = emptyList()
                    break
                }
            }
        } finally {
            session.steeringDrainJob = null
        }
        // 兜底：drain 退出瞬间若又有新项入队（竞态窗口），补拉一轮
        if (session.steeringQueue.value.isNotEmpty()) {
            ensureSteeringDrain(session, conversationId)
        }
    }

    /**
     * 把引导文本以可见工具气泡追加到会话最后一条 assistant 消息（无 assistant 消息则新建）。
     * toolName = "user_guidance"，渲染走 [GuidanceToolUI]（注册在 ToolUIRegistry）。
     */
    private suspend fun appendGuidancePart(conversationId: Uuid, text: String) {
        val conversation = getConversationFlow(conversationId).value
        val guidancePart = UIMessagePart.Tool(
            toolCallId = Uuid.random().toString(),
            toolName = "user_guidance",
            input = "{}",
            // 输出为 JSON（含 text 字段）：渲染器把 content 解析成 JSON 读取 text；
            // 模型侧以工具结果形式读到引导文本。文本含引号/换行时经 buildJsonObject 正确转义。
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("text", JsonPrimitive(text))
                    }.toString()
                )
            ),
            approvalState = ToolApprovalState.Approved,
        )
        if (conversation.messageNodes.isEmpty()) {
            // 空会话：新建仅含引导气泡的 assistant 消息，生成会往里续
            updateConversationState(conversationId) { conv ->
                conv.copy(
                    messageNodes = conv.messageNodes + UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(guidancePart),
                    ).toMessageNode(),
                )
            }
        } else {
            // 追加到最后一条消息的 parts 末尾。若最后一条是 USER（无 AI 气泡可合并），
            // 也新建一条 assistant 消息承载引导。
            val lastNode = conversation.messageNodes.last()
            val lastMsg = lastNode.currentMessage
            if (lastMsg.role == MessageRole.ASSISTANT) {
                updateConversationState(conversationId) { conv ->
                    val nodes = conv.messageNodes.toMutableList()
                    val tailIndex = nodes.lastIndex
                    val tail = nodes[tailIndex]
                    nodes[tailIndex] = tail.copy(
                        messages = tail.messages.map { m ->
                            if (m.id == lastMsg.id) m.copy(parts = m.parts + guidancePart) else m
                        },
                    )
                    conv.copy(messageNodes = nodes)
                }
            } else {
                updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(guidancePart),
                        ).toMessageNode(),
                    )
                }
            }
        }
        saveConversation(conversationId, getConversationFlow(conversationId).value)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
                        compressedHistory = null,
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message) ?: return@launch
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        if (nodeIndex < 0) return@launch
                        if (nodeIndex == 0) {
                            // 首条即 assistant：生成上下文为空，重生成无意义，报可读错误而非崩溃（#16）
                            addError(
                                IllegalStateException(
                                    "This message is the first one and has no preceding context to regenerate from."
                                ),
                                conversationId,
                                title = context.getString(R.string.error_title_regenerate_message),
                            )
                            return@launch
                        }
                        // 重生成 assistant 消息：截断到目标消息之前，生成上下文之后不留悬空旧尾部（#15）。
                        // 与 USER 分支的 subList 截断保持一致，避免「A1' 不知道 U2 → U2→A2 悬空」的语义错乱。
                        val newConversation = conversation.copy(
                            messageNodes = conversation.messageNodes.subList(0, nodeIndex),
                            compressedHistory = null,
                        )
                        saveConversation(conversationId, newConversation)
                        handleMessageComplete(conversationId)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(
                    messageNodes = updatedNodes,
                    compressedHistory = null,
                )
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        /** 异步唤醒续答上下文（子代理完成时注入）。非空时：messages 尾部追加该提示、跳过标题/建议生成。 */
        resumeContext: String? = null,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        // 群组讨论会话不走常规生成链路，由 GroupDiscussionOrchestrator 调度
        if (initialConversation.isGroupDiscussion) return
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        if (model == null) {
            // 配置的模型在 provider 列表里找不到（模型被删 / provider 被移除 / 未配置）。
            // 原实现静默 return：用户会看到消息发出去了却没回复、也无任何报错，这里明确提示。
            addError(
                IllegalStateException(
                    "The chat model is unavailable. Please check the model settings of this assistant."
                ),
                conversationId,
                title = context.getString(R.string.error_title_generation),
            )
            return
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            // 会话级能力模式策略：决定本次生成的工具族/提示词片段/环境说明注入
            val modePolicy = resolveConversationPolicy(
                conversation = conversation,
                assistant = assistant,
                settings = settings,
                trustedFolderActive = trustedFolderRepository.currentSettings().activeProjectId != null,
            )

            // start generating
            val session = getOrCreateSession(conversationId)
            var hasEmittedGenerationStarted = false
            // 生成用消息（重生成时是 messageRange 子序列）
            val messagesToGenerate = if (messageRange != null) {
                conversation.currentMessages.subList(
                    messageRange.start,
                    messageRange.endInclusive + 1,
                )
            } else {
                conversation.effectiveMessages()
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                // 唤醒指令作为 provider 看到的最后一条 USER 消息注入（GenerationHandler 内部
                // 追加到发送列表末尾），不写 system、不进持久化消息列表：system 前缀稳定 → 缓存命中；
                // 持久化尾部保持上一条 ASSISTANT → 续答并入同一条消息（BUG3 修复 + 缓存优化）。
                messages = messagesToGenerate,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversation.id.toString(),
                policy = modePolicy,
                resumeContext = resumeContext,
                steeringQueue = session.steeringQueue,
                memories = loadMemoriesForGeneration(
                    assistant = assistant,
                    messages = messagesToGenerate,
                ),
                inputTransformers = buildList {
                    if (modePolicy.includeReminders) add(TimeReminderTransformer)
                    if (modePolicy.includePromptInjection) add(PromptInjectionTransformer)
                    add(PlaceholderTransformer)
                    if (modePolicy.allowDocument) add(DocumentAsPromptTransformer)
                    if (modePolicy.allowDocument) add(OcrTransformer)
                    if (modePolicy.includeReminders) add(todoReminderTransformer)
                    add(templateTransformer)
                    if (modePolicy.allowWorkspace) add(workspaceReminderTransformer)
                    if (modePolicy.allowTrustedFolder) add(trustedFolderReminderTransformer)
                    if (modePolicy.allowKnowledge) add(knowledgeBaseReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (modePolicy.allowTodo && settings.enableTodoList) {
                        add(createTodoTool(conversation.id.toString(), todoStorage))
                    }
                    if (modePolicy.allowSubAgent && settings.enableSubAgent) {
                        addAll(createSubAgentTools(subAgentRunner, conversation.id))
                    }
                    if (modePolicy.allowStudy && assistant.enabledStudyTools.isNotEmpty()) {
                        addAll(studyTools.getTools(
                            enabledTools = assistant.enabledStudyTools,
                            conversationId = conversation.id.toString(),
                            assistantId = assistant.id.toString(),
                            studySubject = assistant.studySubject,
                            permissions = StudyToolPermissions.fromSettings(settings),
                        ))
                    }
                    if (modePolicy.allowSearch && assistant.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    if (modePolicy.allowLocalTools) {
                        addAll(localTools.getTools(assistant.localTools))
                    }
                    if (modePolicy.allowHistory && assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    if (modePolicy.allowWorkspace) {
                        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    }
                    if (modePolicy.allowTrustedFolder) {
                        addAll(createTrustedFolderTools(trustedFolderRepository))
                    }
                    if (modePolicy.allowSkillUse || modePolicy.allowSkillAdmin) {
                        val allSkills = skillManager.listSkills()
                        if (allSkills.isNotEmpty()) {
                            addAll(
                                createSkillTools(
                                    enabledSkills = assistant.enabledSkills,
                                    allSkills = allSkills,
                                    setEnabledSkills = { skills ->
                                        settingsStore.updateAssistantSkills(assistant.id, skills)
                                    },
                                ).let { filterSkillToolsByMode(it, modePolicy) }
                            )
                        }
                    }
                    if (modePolicy.allowMcpAdmin) {
                        addAll(
                            createMcpManagerTools(
                            mcpManager = mcpManager,
                            settingsStore = settingsStore,
                            assistant = assistant,
                            isEnabled = settings.enableMcpManager,
                        )
                    )
                    }
                    if (modePolicy.allowMcpUse) {
                        mcpManager.getAllAvailableTools(assistant).also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
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
                    // 管理模式专属工具：环境/日志只读感知 + 提供商/新模式写入（需审批）
                    if (modePolicy.allowCreativeTools) {
                        addAll(
                            createCreativeTools(
                                context = context,
                                settingsStore = settingsStore,
                                assistant = assistant,
                                conversationRepository = conversationRepo,
                            )
                        )
                    }
                    if (modePolicy.allowProviderAdmin) {
                        addAll(
                            createProviderAdminTools(
                                settingsStore = settingsStore,
                                providerManager = providerManager,
                                auditStore = managementAuditStore,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                    }
                    if (modePolicy.allowAssistantAdmin) {
                        addAll(
                            createAssistantAdminTools(
                                settingsStore = settingsStore,
                                auditStore = managementAuditStore,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                    }
                    if (modePolicy.allowSettingsAdmin) {
                        addAll(
                            createSettingsAdminTools(
                                settingsStore = settingsStore,
                                auditStore = managementAuditStore,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                    }
                    if (modePolicy.allowDataAdmin) {
                        addAll(
                            createDataAdminTools(
                                settingsStore = settingsStore,
                                auditStore = managementAuditStore,
                                conversationRepo = conversationRepo,
                                trustedFolderRepository = trustedFolderRepository,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                        addAll(
                            createWorkspaceAdminTools(
                                settingsStore = settingsStore,
                                workspaceRepository = workspaceRepository,
                                auditStore = managementAuditStore,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                        addAll(
                            createTrustedFolderAdminTools(
                                trustedFolderRepository = trustedFolderRepository,
                                auditStore = managementAuditStore,
                            )
                        )
                        addAll(
                            createKnowledgeAdminTools(
                                settingsStore = settingsStore,
                                knowledgeManager = knowledgeManager,
                                auditStore = managementAuditStore,
                                rollbackStore = managementRollbackStore,
                            )
                        )
                        addAll(
                            createConversationAdminTools(
                                settingsStore = settingsStore,
                                conversationRepo = conversationRepo,
                                auditStore = managementAuditStore,
                                currentConversationId = conversation.id,
                            )
                        )
                    }
                    if (modePolicy.allowCreativeTools ||
                        modePolicy.allowProviderAdmin ||
                        modePolicy.allowAssistantAdmin ||
                        modePolicy.allowSettingsAdmin ||
                        modePolicy.allowDataAdmin ||
                        modePolicy.allowSkillAdmin ||
                        modePolicy.allowMcpAdmin
                    ) {
                        addAll(createAuditTools(managementAuditStore))
                        addAll(
                            createRollbackTools(
                                rollbackStore = managementRollbackStore,
                                settingsStore = settingsStore,
                                auditStore = managementAuditStore,
                            )
                        )
                    }
                    // Knowledge base tools
                    if (modePolicy.allowKnowledge && assistant.knowledgeBaseIds.isNotEmpty()) {
                        val kbTools = createKnowledgeBaseTools(
                            settings = settings,
                            assistant = assistant,
                            conversation = conversation,
                        )
                        if (kbTools.isNotEmpty()) {
                            addAll(kbTools)
                        }
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val currentConversation = getConversationFlow(conversationId).value
                val lastNode = currentConversation.messageNodes.lastOrNull()
                val completionTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val updatedConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { node ->
                        val newMessages = node.messages.map { message ->
                            val finished = message.finishReasoning()
                            if (node === lastNode &&
                                finished.role == MessageRole.ASSISTANT &&
                                finished.finishedAt == null
                            ) {
                                finished.copy(finishedAt = completionTime)
                            } else {
                                finished
                            }
                        }
                        // finishReasoning 对无未完成推理的消息返回原引用 → 节点保持原引用，
                        // 避免生成结束时全表重建引用导致一次性整屏重组
                        val nodeChanged = newMessages.indices.any { newMessages[it] !== node.messages[it] }
                        if (nodeChanged) node.copy(messages = newMessages) else node
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // 标记生成活动：resumeAfterSubAgent 靠它判断父回合是否正常产出
                        sessions[conversationId]?.lastGenerationActivityAt = SystemClock.elapsedRealtime()
                        if (!hasEmittedGenerationStarted) {
                            hasEmittedGenerationStarted = true
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationStarted(conversationId = conversationId)
                            )
                        }
                        val currentConversation = getConversationFlow(conversationId).value
                        val updatedConversation = currentConversation
                            .updateCurrentMessages(
                                displayMessagesForChunk(
                                    displayMessages = currentConversation.currentMessages,
                                    chunkMessages = chunk.messages,
                                )
                            )
                        // 流式 chunk 只追加/更新消息内容，不会移除消息或附件，
                        // 跳过 checkFilesDelete 的全表文件扫描（每 chunk O(n)→O(1)）。
                        updateConversation(conversationId, updatedConversation, checkFiles = false)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            if (it is CancellationException) {
                // 用户主动停止生成 / resume 超时取消：不补唤醒，避免"已停止却自动续答复活"
            } else {
                addError(it, conversationId, title = context.getString(R.string.error_title_generation))
                Logging.log(TAG, "handleMessageComplete: $it")
                Logging.log(TAG, it.stackTraceToString())

                // 母代理回合异常结束：补唤醒该会话已完成但未消费的子代理（结果必达）
                scheduleRoundEndResume(conversationId)
            }
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            // 子代理：生成结束后把 spawn 占位回写成终态 JSON 落库（幂等）
            backfillSubAgentPlaceholders(conversationId)

            // 异步唤醒续答（resumeContext 非空）：不重新生成标题/建议，避免噪音
            if (resumeContext == null) {
                // 标题/建议串行生成：两者各自 getConversationById 后整对象 copy 落库，
                // 并发会读改写交错互相覆盖（标题或建议偶发丢失，无报错）。串行保证后写者读到前写者结果。
                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, finalConversation)
                    generateSuggestion(conversationId, finalConversation)
                }
            }

            // 母代理回合正常结束：补唤醒该会话已完成但未消费的子代理
            scheduleRoundEndResume(conversationId)

            // 回合正常结束：自动发送生成中排队的待发消息（带附件的新消息，不打断当前流式）
            scheduleDrainPendingSend(conversationId)
        }
    }

    /**
     * 生成时的记忆注入：全量 → 话题相关 top-K + 最近兜底。
     * 检索失败时回退全量注入，绝不崩。
     */
    private suspend fun loadMemoriesForGeneration(
        assistant: Assistant,
        messages: List<UIMessage>,
    ): List<AssistantMemory> {
        val memoryAssistantId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        return runCatching {
            memoryRepository.getRelevantMemories(
                assistantId = memoryAssistantId,
                query = extractMemoryQuery(messages),
            )
        }.getOrElse { e ->
            Log.w(TAG, "memory retrieval failed, fall back to full memories", e)
            if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories()
            else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
    }

    /**
     * 记忆检索词提取：默认取最近一条 USER 消息文本（最新话题，最贴当前意图）；
     * 太短/无实质词（如「那这个呢？」）则回退拼接最近 3 条 USER 消息给 FTS 更多检索面。
     * 返回 null → 无可用检索词，注入侧仅走「最近记忆」兜底。
     */
    private fun extractMemoryQuery(messages: List<UIMessage>): String? {
        val userMessages = messages.filter { it.role == MessageRole.USER }
        if (userMessages.isEmpty()) return null
        val latest = userMessages.last().toText().trim()
        if (latest.isBlank()) return null
        return if (latest.length < 4 || latest.none { it.isLetterOrDigit() }) {
            userMessages.takeLast(3)
                .joinToString("\n") { it.toText().trim() }
                .trim()
                .takeIf { it.isNotBlank() }
        } else {
            latest.take(MemoryRepository.MEMORY_QUERY_MAX_CHARS)
        }
    }

    /**
     * 母代理回合结束后的兜底补唤醒：回合进行中子代理完成、resume 被防重入/串行化挡掉且
     * 未标记消费的，在此补上。成功/失败/取消的回合都要调用——若只在成功路径调用，
     * 母代理回合异常结束时已完成的子代理结果会永久丢失（"子代理空转、结果接不到"）。
     *
     * 在独立协程里执行并等待母代理生成 job 真正结束（isGenerating 变 false）后再检查，
     * 否则刚结束回合的 resume 会再次命中防重入 guard。
     */
    private fun scheduleRoundEndResume(conversationId: Uuid) {
        appScope.launch {
            // 等当前母代理生成 job 结束，确保 isGenerating 已变 false
            runCatching { sessions[conversationId]?.getJob()?.join() }
            delay(200)  // 小延时：等待 session.setJob 的 invokeOnCompletion 把 job 置空
            val pendingResume = subAgentRunner.tasksFlow.value.values
                .filter {
                    it.parentConversationId == conversationId &&
                        // 排除 CANCELLED：停止生成/用户取消的子代理不唤醒续答
                        (it.status == SubAgentStatus.SUCCEEDED ||
                            it.status == SubAgentStatus.TIMEOUT ||
                            it.status == SubAgentStatus.FAILED ||
                            it.status == SubAgentStatus.TOKEN_LIMIT) &&
                        !resumedTaskIds.contains(it.taskId)
                }
                .minByOrNull { it.finishedAt ?: it.createdAt }
            if (pendingResume != null) {
                Log.i(TAG, "round-end fallback resume: task=${pendingResume.taskId}")
                resumeAfterSubAgent(pendingResume)
            }
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

    private suspend fun createKnowledgeBaseTools(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
    ): List<Tool> {
        val allowedIds = assistant.knowledgeBaseIds.map { it.toString() }.toSet()
        if (allowedIds.isEmpty()) return emptyList()

        // 解析 rerank model（首库配置 ?: 全局默认；未配置/不可解析 → null，AI 检索回退 RRF 排序）
        val rerankModelId = assistant.knowledgeBaseIds.firstOrNull()?.let { kbId ->
            knowledgeManager.baseRepository.getById(kbId.toString())?.rerankModelId
        } ?: settings.rerankModelId?.toString()
        val reranker = resolveReranker(settings, rerankModelId)

        // 按库解析 embedding 模型：每库用该库入库时的模型（无则回退全局），
        // 保证检索 query 向量维度与该库 chunk 一致
        val embeddingForBase: suspend (String) -> EmbeddingConfig? = { baseId ->
            resolveEmbeddingConfig(baseId, settings)
        }

        // 工具创建时一次性计算最近对话摘要（排除当前用户问题，由 kb_search 的 query 承载）
        val historyText = conversation.currentMessages
            .dropLast(1)
            .takeLast(6)
            .filter { it.toText().isNotBlank() }
            .joinToString("\n\n") { it.summaryAsText(maxLength = 500) }

        val rewrite: suspend (String) -> String =
            if (assistant.enableKnowledgeQueryRewrite) {
                { query -> rewriteQueryForSearch(query, historyText, settings, assistant) }
            } else {
                { it }
            }

        val hyde: suspend (String) -> String? = { query ->
            generateHydeText(query, settings, assistant)
        }

        val multiQuery: suspend (String) -> List<String> = { query ->
            generateMultiQueries(query, settings, assistant)
        }

        val tool = KnowledgeSearchTool(
            knowledgeManager = knowledgeManager,
            getAllowedKnowledgeBaseIds = { allowedIds },
            getEmbeddingForBase = embeddingForBase,
            getReranker = { reranker },
            rewriteQuery = rewrite,
            generateHydeText = hyde,
            generateMultiQueries = multiQuery,
        )
        return listOf(tool.create(), tool.createListTool())
    }

    /**
     * 解析单个知识库的 embedding 配置（provider + model 来自同一次解析，原子获取）。
     * 解析失败返回 null（调用方降级纯关键词检索）。runCatching 只包住唯一会抛的 Uuid.parse，
     * 避免把协程取消也吞掉。
     */
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

    /**
     * 检索前查询改写：结合最近对话历史，把依赖上下文的 query 改写为自包含检索 query。
     * 无历史 / 改写失败时原样返回。
     */
    private suspend fun rewriteQueryForSearch(
        query: String,
        history: String,
        settings: Settings,
        assistant: Assistant,
    ): String {
        if (query.isBlank()) return query
        if (history.isBlank()) return query  // 首轮无历史，改写=原样，省一次 LLM 调用
        return try {
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: return query
            val provider = model.findProvider(settings.providers) ?: return query
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        DEFAULT_QUERY_REWRITE_PROMPT.applyPlaceholders(
                            "query" to query,
                            "history" to history,
                        )
                    )
                ),
                params = backgroundTextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,  // 改写不需要推理，省 token/延迟
                ),
            )
            result.message.toText().trim().takeIf { it.isNotBlank() } ?: query
        } catch (e: CancellationException) {
            throw e  // 不吞取消，让生成链正常中止
        } catch (e: Exception) {
            query  // 改写失败回退原 query
        }
    }

    /**
     * HyDE（Hypothetical Document Embeddings）：让 LLM 根据 query 生成一段假设答案，
     * 用假设答案的向量做检索，改善口语化/模糊 query 的召回。
     * 失败时返回 null，调用方将回退到原 query。
     */
    private suspend fun generateHydeText(
        query: String,
        settings: Settings,
        assistant: Assistant,
    ): String? {
        if (query.isBlank()) return null
        return try {
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: return null
            val provider = model.findProvider(settings.providers) ?: return null
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        DEFAULT_HYDE_PROMPT.applyPlaceholders(
                            "query" to query,
                        )
                    )
                ),
                params = backgroundTextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            result.message.toText().trim().takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Multi-Query 查询扩展：生成 2-3 个不同措辞的检索查询。
     * 失败时返回空列表，调用方回退到单查询检索。
     */
    private suspend fun generateMultiQueries(
        query: String,
        settings: Settings,
        assistant: Assistant,
    ): List<String> {
        if (query.isBlank()) return emptyList()
        return try {
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: return emptyList()
            val provider = model.findProvider(settings.providers) ?: return emptyList()
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        DEFAULT_MULTIQUERY_PROMPT.applyPlaceholders("query" to query)
                    )
                ),
                params = backgroundTextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            result.message.toText().trim()
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && it != query }
                ?.take(3)
                ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 解析 rerank 模型为 Reranker；未配置或不可用返回 null。 */
    private fun resolveReranker(settings: Settings, rerankModelId: String?): me.rerere.knowledge.retrieval.Reranker? {
        if (rerankModelId == null) return null
        return runCatching {
            val model = settings.findModelById(kotlin.uuid.Uuid.parse(rerankModelId)) ?: return null
            val providerSetting = model.findProvider(settings.providers) ?: return null
            if (providerSetting !is ProviderSetting.OpenAI) return null
            @Suppress("UNCHECKED_CAST")
            val provider = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting.OpenAI>
            me.rerere.knowledge.retrieval.Reranker(provider, providerSetting, model)
        }.getOrNull()
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(
            conversationId,
            conversation.copy(
                messageNodes = messagesNodes,
                compressedHistory = null,
            )
        )
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 提示词优化 ----

    /**
     * 优化一段输入文字：先用 场景×语气 的系统提示词重写，返回优化结果。
     * 未配置提示词优化模型时回退到全局默认聊天模型（settings.chatModelId）。
     * 注意：失败不进入会话错误气泡（addError），由调用方在弹窗层提示。
     */
    internal suspend fun optimizePrompt(
        text: String,
        scene: PromptOptimizeScene,
        tone: PromptOptimizeTone,
        depth: PromptOptimizeDepth,
        extraNote: String = "",
    ): Result<String> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.promptOptimizeModelId, fallback = settings.chatModelId)
            ?: throw IllegalStateException("No model available for prompt optimization")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("No provider available for prompt optimization")
        val providerHandler = providerManager.getProviderByType(provider)
        // 自定义模板：优先取按场景存储的，fallback 到旧版全局模板；填入场景/语气/深度/内容占位符
        val systemPrompt = settings.promptOptimizePromptForScene(scene)?.let { custom ->
            custom.applyPlaceholders(
                "scene" to scene.toDisplayText(),
                "tone" to tone.toDisplayText(),
                "depth" to depth.toDisplayText(),
                "content" to text,
                // 兼容旧版模板里的 {level} 占位符（旧模板用 level 表示程度/语气）
                "level" to tone.toDisplayText(),
            )
        } ?: promptOptimizeSystemPrompt(scene, tone, depth)
        // 附加说明作为额外要求拼进 user 消息，不参与 {content} 占位符本体
        val userContent = if (extraNote.isNotBlank()) "$text\n\n【额外要求】$extraNote" else text
        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.system(systemPrompt),
                UIMessage.user(userContent),
            ),
            params = backgroundTextGenerationParams(
                model,
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.promptOptimizeThinkingBudgetForScene(scene)),
            ),
        )
        result.message.toText().trim().orEmpty()
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            // 群组讨论不生成建议
            if (conversation.isGroupDiscussion) return@runCatching
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.effectiveMessages()

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // 只替换 AI 请求用的上下文快照，messageNodes 保留完整历史用于展示
        val compressedContextMessages = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary))
            }
            addAll(messagesToKeep)
        }
        val newConversation = conversation.copy(
            compressedHistory = CompressedHistory(
                messages = compressedContextMessages,
                lastOriginalMessageId = conversation.currentMessages.lastOrNull()?.id,
                summaryText = compressedSummaries.joinToString("\n\n"),
            ),
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation, checkFiles: Boolean = true) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        if (checkFiles) {
            checkFilesDelete(conversation, session.state.value)
        }
        // version 递增：让 MutableStateFlow 的 equals 先比 version 字段 O(1) 短路，
        // 避免流式每 chunk 在主线程深比较 messageNodes→parts→全量文本字符串（长对话掉帧源）
        session.state.value = conversation.copy(version = conversation.version + 1L)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        // 空会话不落库，但显式配置了模式（mode 非空）的空会话也保存，避免切换模式后重进丢失
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty() && conversation.mode == null) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(
            conversationId,
            currentConversation.copy(
                messageNodes = updatedNodes,
                compressedHistory = null,
            )
        )
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(
            conversationId,
            currentConversation.copy(
                messageNodes = updatedNodes,
                compressedHistory = null,
            )
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(
            messageNodes = updatedNodes,
            compressedHistory = null,
        )
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）。
    // 同时停止该会话后台运行中的子代理（已确认语义：停止生成 = 子代理全部停止），
    // 停止的子代理是 CANCELLED，不触发异步唤醒。
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
        subAgentRunner.cancelByConversation(conversationId)
    }
}

/** 排队中的引导消息（UI 气泡用）：id 用于定位「打断并发送 / 取消 / 编辑」，text 为引导内容 */
data class PendingGuidanceItem(
    val id: Uuid,
    val text: String,
)

/** 生成中排队的待发送消息（UI 卡片用）：answer=false 表示只追加消息不触发生成 */
data class PendingSendItem(
    val id: Uuid = Uuid.random(),
    val content: List<UIMessagePart>,
    val answer: Boolean = true,
)
