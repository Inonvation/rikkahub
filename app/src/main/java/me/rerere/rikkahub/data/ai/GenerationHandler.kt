package me.rerere.rikkahub.data.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.sum
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.cleanupLastAssistantBlankLines
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.RETRY_STOP_DELAY
import me.rerere.ai.util.RetryPolicy
import me.rerere.ai.util.isRetryable
import me.rerere.ai.util.retryBackoffDelay
import me.rerere.ai.util.retryWithPolicy
import me.rerere.rikkahub.data.repository.ContextCompositionRepository
import me.rerere.rikkahub.data.ai.buildContextComposition
import me.rerere.rikkahub.data.ai.estimateTokensByChars
import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.ai.subagent.boundToolOutput
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolPath
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// ---- 生成重试 ----
// 重试策略（429 / 5xx / 网络错误的有界指数退避 + jitter + 尊重 Retry-After）
// 已抽到 me.rerere.ai.util.RetryPolicy，默认最多重试 5 次（对齐 DSH/Codex 做法）。

// ---- 流式输出中断续答唤醒 ----
// 网络中断等导致已开始输出的流式失败时，向模型发送「继续生成」指令尝试唤醒续答，
// 让模型在既有部分输出上接续（而非从头重来）。最多唤醒 MAX_STREAM_RESUME_ATTEMPTS 次，
// 超出后仍失败则抛出，保留部分输出交给上层报错。
private const val MAX_STREAM_RESUME_ATTEMPTS = 1

// ---- 防失控启发式（精准死循环检测）----
// 旧方案按「输出指纹」判断"是否新进展"：AI 试错时反复调用同一工具而输出恰好相同
// （查同一数据/读同一文件），会被误判为无进展而自动停止，剥夺模型的试错空间。
// 现改为按「工具入参」判定：只有连续多轮发起**完全相同**的工具调用
// （toolName + input 均一致）才算死循环；入参有变化即视为模型在试错/推进，绝不中断。
private const val MAX_IDENTICAL_CALL_ROUNDS = 4
private const val AUTO_STOP_NOTICE = "\n\n---\n*模型连续多轮重复相同操作且无进展，已自动停止。你可以继续发送消息。*"

/**
 * 生成中的实时统计（对齐 DeepSeek 风格：流式期间同步显示输入/输出 token 与速率）。
 *
 * - [streamMillis] 只累计纯流式输出时长（各步 streamText 的 collect 窗口），
 *   不含工具执行/审批等待——与最终 [UIMessage.streamDurationMillis] 同口径。
 * - [completionTokens] 在 usage 未到达前按已流出字符估算（[isEstimate]=true），
 *   收到 Usage chunk 后切换为真实值。
 */
@Serializable
data class GenerationLiveStats(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val streamMillis: Long = 0L,
    val isEstimate: Boolean = false,
) {
    val tokensPerSecond: Float
        get() = if (streamMillis > 0) completionTokens * 1000f / streamMillis else 0f
}

// ---- 工作区文件工具串行化（并发竞态防护）----
// 背景：GenerationHandler 一轮内并行执行所有工具；workspace_edit_file / workspace_write_file
// 是"读-改-写"三步（readTextInRootfs → replaceText → writeTextInRootfs），无原子性保护。
// 同文件并发两条 edit 会同时读到同一 base，各自计算 updated 后并发 shell cat > 裸写同一 inode，
// 造成 lost-update + 字节交错（尾部重复行），且两条都报成功。
// 对策：对 workspace 文件类工具按 (workspaceId, path) 串行化；workspace_shell 无法确定精确 path，
// 按 workspaceId 串行。非 workspace 工具（trusted_folder_*/study/memory 等）不受影响，保持并行。
// 锁池条目不删除：Mutex 很轻量，且不同 (workspaceId, path) 的数量对一个个人用户是天然有界的
// （一个会话内 AI 触碰的不同文件数有限），长期持有内存开销可忽略。
// 若删除条目需引用计数，且删除与"新等待者 getOrPut"之间有竞态窗口（可能让新协程拿到新 mutex
// 与旧等待者并发），反而不安全，故保持不删。这是 UI 层堵漏，不锁实现层（见方案）。
private class WorkspaceToolLockKey {
    val workspaceId: String
    val path: String?

    constructor(workspaceId: String, path: String?) {
        this.workspaceId = workspaceId
        this.path = path
    }

    override fun equals(other: Any?): Boolean =
        other is WorkspaceToolLockKey && other.workspaceId == workspaceId && other.path == path

    override fun hashCode(): Int = 31 * workspaceId.hashCode() + (path?.hashCode() ?: 0)

    override fun toString(): String = if (path == null) "ws:$workspaceId" else "ws:$workspaceId:$path"
}

private val workspaceToolLocks = ConcurrentHashMap<WorkspaceToolLockKey, Mutex>()

/**
 * 解析工具对应的串行化 key：workspace 文件类工具按 (workspaceId, path)，shell 按 workspaceId；非 workspace 工具返回 null（不串行）。
 * path 以 [cwd] 为基准归一为 Rootfs 绝对路径（工具侧支持相对路径写法），保证同一文件的
 * 相对/绝对两种写法串行到同一把锁；归一失败时退回原始字符串（该调用随后也会被审批/执行拦截）。
 */
private fun workspaceLockKeyFor(
    toolName: String,
    inputJson: JsonObject?,
    workspaceId: String?,
    cwd: String? = null,
): WorkspaceToolLockKey? {
    if (workspaceId.isNullOrBlank()) return null
    return when (toolName) {
        "workspace_edit_file", "workspace_write_file" -> {
            val rawPath = inputJson?.get("path")?.jsonPrimitive?.contentOrNull
            if (rawPath.isNullOrBlank()) return null
            val path = runCatching { resolveWorkspaceToolPath(rawPath, cwd) }.getOrDefault(rawPath)
            WorkspaceToolLockKey(workspaceId, path)
        }
        "workspace_shell" -> WorkspaceToolLockKey(workspaceId, null)
        else -> null
    }
}

/** 串行执行工具：同一 workspace 文件 / 同一 workspace 的 shell 依次执行，其余并行 */
private suspend fun executeToolSerialized(
    tool: UIMessagePart.Tool,
    toolsInternal: List<Tool>,
    workspaceId: String?,
    workspaceCwd: String?,
    json: Json,
    execute: suspend (UIMessagePart.Tool, List<Tool>, suspend (UIMessagePart.Tool) -> Unit) -> UIMessagePart.Tool?,
    onToolStarted: suspend (UIMessagePart.Tool) -> Unit,
    onToolQueued: suspend (UIMessagePart.Tool) -> Unit,
): UIMessagePart.Tool? {
    val inputJson = runCatching {
        json.parseToJsonElement(tool.input.ifBlank { "{}" }) as? JsonObject
    }.getOrNull()
    val key = workspaceLockKeyFor(tool.toolName, inputJson, workspaceId, workspaceCwd) ?: return execute(tool, toolsInternal, onToolStarted)
    val mutex = workspaceToolLocks.getOrPut(key) { Mutex() }
    // 非竞争：直接拿到锁，无需排队标识
    if (mutex.tryLock()) {
        return try {
            execute(tool, toolsInternal, onToolStarted)
        } finally {
            mutex.unlock()
        }
    }
    // 竞争：排在锁后面等待，先发「等待中」状态，再阻塞直到拿到锁
    val queuedTool = tool.copy(
        queuedAt = Clock.System.now(),
        queuedAtMs = SystemClock.elapsedRealtime(),
    )
    onToolQueued(queuedTool)
    mutex.lock()
    return try {
        execute(tool, toolsInternal, onToolStarted)
    } finally {
        mutex.unlock()
    }
}

/** 工具调用的指纹（toolName + 原始入参），用于检测「完全相同调用重复」的死循环 */
private fun UIMessagePart.Tool.inputFingerprint(): String {
    return "$toolName:${input.trim()}"
}

/**
 * 构造「流式输出中断续答唤醒」指令：流式生成因网络/服务端错误中断时，作为最后一条 USER
 * 消息注入，提示模型在已生成的部分输出上继续，而不是从头重来。
 */
internal fun buildStreamResumeInstruction(): String = buildString {
    appendLine("## Your previous output was interrupted")
    appendLine("Your previous response was cut off by a connection error before it was finished.")
    appendLine("- Continue writing from exactly where you stopped. Do NOT re-answer from scratch or repeat what is already written.")
    appendLine("- If you were in the middle of a tool call or reasoning, finish it and then complete your answer.")
    appendLine("- Keep the same style and structure as your existing output.")
}

/** 构造重试可见状态文案（对齐 DSH llm/retry 事件的可读化）：`第 n/max 次重试 · 等待 Xs` */
private fun buildRetryStatus(attempt: Int, maxRetries: Int, delayMs: Long): String =
    "第 $attempt/$maxRetries 次重试 · 等待 ${(delayMs + 999) / 1000}s"

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val contextCompositionRepository: ContextCompositionRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        /** 能力模式策略，null = 全量（内部调用不受模式裁剪） */
        policy: ChatModePolicy? = null,
        /** 内部续答唤醒指令（子代理完成时注入）。只在本次生成的**第一步**追加为 provider
         *  看到的最后一条 USER 消息，之后置空——指令注入一次即成为上下文历史，逐步重复
         *  注入会让模型每步都把它当作刚收到的输入、在思考里反复确认。不写进 system（保持
         *  system 前缀字节不变 → prompt cache 命中）。只进 internalMessages（发送列表），
         *  不落持久化列表。默认 null 不影响普通生成。 */
        resumeContext: String? = null,
        /** steering 队列：会话级待注入引导（FIFO）。immediate=true 的项在下一轮边界
         *  （工具调用/输出完成）消费，引导文本作为真实 USER 消息追加到上下文尾部（对齐
         *  Codex turn/steer：一次、尾部、append-only），不打断当前流式；其余项排队不消费，
         *  等回合结束后由 ChatService 作为用户消息依次发送。 */
        steeringQueue: kotlinx.coroutines.flow.MutableStateFlow<List<PendingSteering>>? = null,
        /** 生成中实时统计（token/速率），供 UI 在流式期间同步显示；null = 不上报 */
        liveStats: MutableStateFlow<GenerationLiveStats?>? = null,
    ): Flow<GenerationChunk> = channelFlow<GenerationChunk> {
        // 工具开始事件由并行的 async 子协程发出；flow 的 emit 不允许跨协程，必须用 channelFlow 的 send。
        val emit: suspend (GenerationChunk) -> Unit = { send(it) }
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        // 续答唤醒指令只发一次：哪一步用掉即置空（见循环内 effectiveResumeContext）
        var pendingResumeContext = resumeContext

        // 防失控启发式状态（跨轮累积）：
        // prevRoundFingerprints = 上一轮工具调用指纹集合；identicalCallRounds = 连续完全相同调用轮数
        var prevRoundFingerprints: Set<String>? = null
        var identicalCallRounds = 0
        var generationEnded = false
        var waitingForUser = false

        // 本回合累计：纯流式时长（不含工具执行）+ 跨步 usage 累加。
        // 每步 StreamChunkHandler 的 merge 是「最新非零覆盖」语义（适配单流内增量 usage），
        // 工具循环多步之间必须外置累加，否则只剩最后一步的 usage（会话花费被低估）。
        // 审批等待后的新一轮 generateText：从末条 assistant 恢复基线，避免清零。
        val lastAssistantBaseline = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
        var turnStreamMillis = lastAssistantBaseline?.streamDurationMillis ?: 0L
        var turnUsage: TokenUsage? = lastAssistantBaseline?.usage

        fun flushAssistantUsage() {
            val last = messages.lastOrNull() ?: return
            if (last.role != MessageRole.ASSISTANT) return
            val stepUsage = last.usage ?: return
            turnUsage = turnUsage.sum(stepUsage)
            messages = messages.dropLast(1) + last.copy(usage = turnUsage)
        }

        fun clearAssistantUsageForNextStep() {
            val last = messages.lastOrNull() ?: return
            if (last.role != MessageRole.ASSISTANT || last.usage == null) return
            messages = messages.dropLast(1) + last.copy(usage = null)
        }

        try {
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            // 进入新一轮生成前清掉上一轮流式结束时写入的 finishedAt：
            // 工具循环仍在同一回合内，消息并未真正完成，UI 不应提前显示完成状态。
            messages = messages.markLastAssistantFinished(false)

            // steering：仅消费「立即发送」模式的引导（用户点了对应气泡的发送按钮）——
            // 在下一轮边界（上一个工具调用/输出完成后的自然边界）把引导文本作为真实 USER
            // 消息追加到上下文尾部，对齐 Codex turn/steer：一次、尾部、append-only（不改写
            // 已发送内容，prompt cache 保住）。引导落地后即成为历史，后续步骤不再重复注入，
            // 模型不会每步都「重新收到」引导。默认排队项不在轮内消费，保留在队列中，等整个
            // 回合输出结束后由 ChatService 作为用户消息依次发送。
            val queue = steeringQueue
            val pendingSteering = queue?.value?.firstOrNull { it.immediate }
            if (pendingSteering != null) {
                // update 保证消费与 UI 并发入队不互吞（CAS 重试）
                queue!!.update { it.filterNot { item -> item.id == pendingSteering.id } }
                messages = messages + UIMessage(
                    role = MessageRole.USER,
                    parts = pendingSteering.parts,
                )
                emit(GenerationChunk.Messages(messages))
            }

            // 续答唤醒指令只在第一步追加，之后置空（一次性消费）
            val effectiveResumeContext = pendingResumeContext
            pendingResumeContext = null

            // 规范化排序：provider 前缀缓存以 tools 数组顺序为键的一部分，
            // 与装配路径的书写顺序解耦（见 ToolCanonicalOrder.kt）
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if ((policy?.allowMemory ?: true) && assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content, category ->
                            memoryRepo.addMemory(memoryAssistantId, content, category)
                        },
                        onUpdate = { id, content, category ->
                            memoryRepo.updateContent(memoryAssistantId, id, content, category)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemoryInScope(memoryAssistantId, id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }.canonicalToolOrder()

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                // 每步流式 usage 独立 merge（最新非零覆盖）；上一步结果已外置累加，先清空
                clearAssistantUsageForNextStep()
                try {
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                                conversationId = conversationId?.toString(),
                            )
                            val visualMessages = messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            ).cleanupLastAssistantBlankLines()
                            // 流式 chunk 的 Finish 会写入 finishedAt，但工具循环还没结束，
                            // 这里统一清掉，避免 UI 在回合中途提前显示“完成”。
                            emit(GenerationChunk.Messages(visualMessages.markLastAssistantFinished(false)))
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = toolsInternal,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationId = conversationId,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                        workspaceCwd = workspaceCwd,
                        policy = policy,
                        resumeContext = effectiveResumeContext,
                        liveStats = liveStats,
                        priorStreamMillis = turnStreamMillis,
                        priorUsage = turnUsage,
                    )
                } finally {
                    // 无论成功/异常/取消，都把本步 usage 与流式时长并入回合累计并写回消息
                    flushAssistantUsage()
                    val lastAfterStep = messages.lastOrNull()
                    if (lastAfterStep?.role == MessageRole.ASSISTANT) {
                        val stamped = lastAfterStep.streamDurationMillis ?: 0L
                        val stepStream = stamped - turnStreamMillis
                        if (stepStream > 0 || lastAfterStep.usage !== turnUsage) {
                            turnStreamMillis = maxOf(turnStreamMillis, stamped)
                            messages = messages.dropLast(1) + lastAfterStep.copy(
                                streamDurationMillis = turnStreamMillis,
                                usage = turnUsage ?: lastAfterStep.usage,
                            )
                        }
                    }
                }
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                ).cleanupLastAssistantBlankLines()

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // 母代理这轮纯文本没调工具，正常结束。
                    // 若仍有已派发但未取结果的子代理，交给完成事件流异步唤醒续答，
                    // 不再强制续轮干等（见 ChatService.resumeAfterSubAgent）。
                    messages = messages.markLastAssistantFinished(true)
                    emit(GenerationChunk.Messages(messages))
                    generationEnded = true
                    break
                }
                messages = messages.markLastAssistantFinished(false)
                emit(GenerationChunk.Messages(messages))

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // 填充工具目的说明（审批卡片/详情展示用）；旧消息已带则保留
                    val withDescription = if (tool.description.isBlank() && toolDef != null) {
                        tool.copy(description = toolDef.description)
                    } else {
                        tool
                    }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            withDescription.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            withDescription
                        }

                        else -> withDescription
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    waitingForUser = true
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            val messageUpdateMutex = Mutex()
            val updateToolPart: suspend (UIMessagePart.Tool) -> Unit = { updated ->
                messageUpdateMutex.withLock {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == updated.toolCallId) {
                            updated
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }
            }
            val onToolStarted: suspend (UIMessagePart.Tool) -> Unit = { started -> updateToolPart(started) }
            // 单个工具完成即回填 UI，避免并行时「已完成的工具仍显示执行中」
            val onToolCompleted: suspend (UIMessagePart.Tool) -> Unit = { completed -> updateToolPart(completed) }
            // 工具排在 workspace 串行锁后等待时，先发「等待中」状态
            val onToolQueued: suspend (UIMessagePart.Tool) -> Unit = { queued -> updateToolPart(queued) }
            // Handle tools (execute approved tools, handle denied tools)
            // 并行执行：多个工具（含多子代理派发）并发跑，按原始顺序回填结果。
            // workspace 文件类工具经 executeToolSerialized 串行化（同一文件/同一 workspace 的
            // shell 依次执行），防止同文件并发编辑的"读-改-写"竞态；其余工具仍并行。
            val workspaceIdForLock = assistant.workspaceId?.toString()
            val executedTools = coroutineScope {
                toolsToProcess.map { tool ->
                    async {
                        val result = executeToolSerialized(
                            tool,
                            toolsInternal,
                            workspaceIdForLock,
                            workspaceCwd,
                            json,
                            ::executeTool,
                            onToolStarted,
                            onToolQueued,
                        )
                        // 完成一个立即刷新一个，让已完成工具不再停留在「执行中」
                        if (result != null) onToolCompleted(result)
                        result
                    }
                }.awaitAll().filterNotNull()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                waitingForUser = true
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                        conversationId = conversationId?.toString(),
                    )
                )
            )

            // ---- 防失控启发式（精准死循环检测）----
            // 仅当「本轮工具调用指纹集合」与上一轮完全一致（toolName + 入参均未变）时累计
            // 死循环轮数；任何一轮出现新的工具或不同的入参，都视为模型在试错/推进，立即清零。
            // 相比旧的「输出无新证据」判定，不再误杀「反复读同一数据但尝试不同方案」的场景。
            val currentFingerprints = executedTools.map { it.inputFingerprint() }.toSet()
            val prev = prevRoundFingerprints
            if (prev != null && currentFingerprints.isNotEmpty() && currentFingerprints == prev) {
                identicalCallRounds++
            } else {
                identicalCallRounds = 0
            }
            prevRoundFingerprints = currentFingerprints

            if (identicalCallRounds >= MAX_IDENTICAL_CALL_ROUNDS) {
                Log.i(TAG, "generateText: auto-stop (identical tool calls for $MAX_IDENTICAL_CALL_ROUNDS rounds)")
                val lastMsg = messages.last()
                messages = messages.dropLast(1) + lastMsg.copy(
                    parts = lastMsg.parts + UIMessagePart.Text(AUTO_STOP_NOTICE)
                )
                messages = messages.markLastAssistantFinished(true)
                emit(GenerationChunk.Messages(messages))
                generationEnded = true
                break
            }
        }

        // maxSteps 耗尽属于生成自然结束，补一次最终完成标记；
        // 等待用户审批/工具未就绪时保持未完成，避免 UI 提前显示“已结束”。
        if (!generationEnded && !waitingForUser) {
            messages = messages.markLastAssistantFinished(true)
            emit(GenerationChunk.Messages(messages))
        }
        } finally {
            // 无论正常结束/取消/异常，都清掉实时统计，避免残留到下一次生成
            liveStats?.value = null
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        /** 能力模式策略，null = 全量（内部调用不受模式裁剪） */
        policy: ChatModePolicy? = null,
        resumeContext: String? = null,
        /** 生成中实时统计上报；null = 不上报 */
        liveStats: MutableStateFlow<GenerationLiveStats?>? = null,
        /** 本回合已累计的纯流式时长（毫秒），用于 liveStats 与 streamDurationMillis 基线 */
        priorStreamMillis: Long = 0L,
        /** 本回合已累计的 usage（跨工具步），用于 liveStats 的输入 token 展示 */
        priorUsage: TokenUsage? = null,
    ) {
        // 捕获最终 system 文本，供下方构成快照使用（buildList lambda 内不可见）
        var builtSystem: String? = null
        // 工具 schema 的 JSON 序列化与 token/字符统计统一在后台线程做一次：
        // Tool.parameters() 每次调用都会重新序列化完整 schema JSON，单请求内
        // PromptMetrics 与上下文构成估算若各自序列化一遍，工具多、schema 大
        // （MCP/管理模式）时是主线程上实打实的重复开销。统计结果两处复用。
        val toolSchemaStats: Map<String, ToolSchemaStats> = withContext(Dispatchers.Default) {
            tools.associate { tool ->
                val schemaJson =
                    runCatching { tool.parameters()?.toString().orEmpty() }.getOrDefault("")
                tool.name to ToolSchemaStats(
                    schemaChars = tool.name.length + tool.description.length + schemaJson.length,
                    tokens = estimateTokensByChars(tool.name) +
                        estimateTokensByChars(tool.description) +
                        estimateTokensByChars(schemaJson),
                )
            }
        }
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                val profilePrompt = if (assistant.useUserProfile) {
                    buildUserProfilePrompt(
                        profile = settings.userProfile,
                        nickname = settings.displaySetting.userNickname,
                    )
                } else {
                    null
                }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                } else if (profilePrompt == null) {
                    // 身份层与用户层全空：注入最小身份行，避免 system 以工具自述开头
                    append(BASE_IDENTITY_PROMPT)
                }

                // 用户基本资料（全局稳定注入）：紧跟助手提示词，只在设置变更时变化，
                // 是 system 缓存前缀的一部分；空配置跳过。
                profilePrompt?.let {
                    appendLine()
                    append(it)
                }

                // 记忆不进 system：检索结果逐轮变化会打穿后续全部缓存前缀。
                // 改由 MemoryContextTransformer 追加到最后一条 USER 消息（见 transforms 调用）。

                // 工具prompt
                if (policy?.includeToolSystemPrompt ?: true) {
                    // 去重：同一份 systemPrompt（如 SkillTools 挂在多个技能工具上）只注入一次。
                    buildToolSystemPrompts(tools, model, messages).forEach { prompt ->
                        appendLine()
                        append(prompt)
                    }
                }
                // 行为层：决策/工具/子代理/提问准则（默认开，可关）。放末尾，不覆盖用户自定义提示词
                if ((policy?.includeAgentBehaviorPrompt ?: true) && settings.enableAgentBehaviorPrompt) {
                    appendLine()
                    append(
                        buildAgentBehaviorPrompt(
                            tools = tools,
                            profile = policy?.behaviorProfile ?: AgentBehaviorProfile.STANDARD,
                        )
                    )
                }
            }
            PromptMetrics.lastSystemPromptChars = system.length
            PromptMetrics.lastApproxTokens = system.length / 4
            PromptMetrics.lastToolCount = tools.size
            PromptMetrics.lastToolSchemaChars = toolSchemaStats.values.sumOf { it.schemaChars }
            PromptMetrics.lastToolFamilies = buildMap {
                tools.groupBy { toolFamilyForMetrics(it.name) }.forEach { (family, familyTools) ->
                    put(family, familyTools.size)
                }
            }
            // 静态成本 = system prompt + 工具 schema（近似；不含历史消息）。
            // 占 contextTokenLimit 比例超阈值即标记，供调试页提示「能力注入过重」。
            val staticCostChars = system.length + PromptMetrics.lastToolSchemaChars
            PromptMetrics.lastStaticCostChars = staticCostChars
            PromptMetrics.lastStaticCostRatio =
                if (assistant.contextTokenLimit > 0) staticCostChars / 4f / assistant.contextTokenLimit else 0f
            PromptMetrics.lastStaticCostOverBudget =
                PromptMetrics.lastStaticCostRatio > STATIC_COST_BUDGET_RATIO
            Log.i(
                TAG,
                "system_prompt promptRevision=$PROMPT_REVISION chars=${system.length} " +
                    "approxTokens=${system.length / 4} tools=${tools.size} " +
                    "toolSchemaChars=${PromptMetrics.lastToolSchemaChars} families=${PromptMetrics.lastToolFamilies} " +
                    "staticBudget=${"%.2f".format(PromptMetrics.lastStaticCostRatio)}" +
                    "overBudget=${PromptMetrics.lastStaticCostOverBudget}"
            )
            builtSystem = system
            if (system.isNotBlank()) add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
            conversationId = conversationId?.toString(),
            retrievedMemories = if ((policy?.allowMemory ?: true) && assistant.enableMemory) memories else emptyList(),
        )

        // 续答唤醒指令：作为 provider 看到的最后一条 USER 消息追加（transforms 之后，避免被
        // 输入转换器改写；不写 system 保持缓存前缀稳定）。只进 internalMessages 发送列表，
        // 不落持久化 messages —— handleMessageChunk 仍并入上一条 assistant 消息，不分段。
        // 流式输出中断续答唤醒时会被重建为「internalMessages + 部分输出 + 继续指令」。
        var messagesToSend = if (!resumeContext.isNullOrBlank()) {
            internalMessages + UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(resumeContext)),
            )
        } else {
            internalMessages
        }

        // 上下文构成快照：以本请求实际发送内容为准（system 全文 + 工具 schema 按
        // 系统/MCP/技能拆分 + transforms 后消息），供顶栏圆圈 / 浮窗构成详情 / 自动压缩
        // 共用一个数据源；同写入落库，app 重启后按会话恢复（见 ContextCompositionRepository）。
        // 纯估算（schema 复用上方的单次序列化结果 + 全量文本字符统计）放到后台线程，
        // 避免主线程在工具多/消息长时出现可感知的停顿；快照写回仍在调用协程（主线程）执行。
        if (conversationId != null) {
            val composition = withContext(Dispatchers.Default) {
                buildContextComposition(
                    systemText = builtSystem.orEmpty(),
                    tools = tools,
                    messages = messagesToSend,
                    schemaTokensByName = toolSchemaStats.mapValues { it.value.tokens },
                )
            }
            contextCompositionRepository.save(conversationId.toString(), composition)
        }

        var messages: List<UIMessage> = messages
        // 流式续答唤醒时用于定位「本次流式新增的部分输出」：取 messages 中位于原始输入之后的部分。
        val originalMessagesCount = messages.size
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = conversationId?.toString(),
        )
        val retryPolicy = RetryPolicy(maxRetries = settings.aiRequestMaxRetries.coerceIn(0, 10))
        // 本步已累计的纯流式时长（毫秒）：只包 streamText collect 窗口，不含重试等待。
        // 基线 priorStreamMillis 来自本回合前序工具步的累计。
        var stepStreamMillis = 0L
        // 估算输入 token（usage 未到达前）：internalMessages 字符数 / 4 的粗估，收到 Usage 后被真实值覆盖。
        val estimatedPromptTokens = estimateTokensByChars(
            internalMessages.joinToString("\n") { msg ->
                msg.parts.joinToString("\n") { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.text
                        is UIMessagePart.Reasoning -> part.reasoning
                        else -> ""
                    }
                }
            }
        )

        fun publishLiveStats(currentMessages: List<UIMessage>, streamMillis: Long) {
            val stats = liveStats ?: return
            val last = currentMessages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            val usage = last?.usage
            val completionTokens = usage?.completionTokens?.takeIf { it > 0 }
                ?: last?.let { msg ->
                    msg.parts.sumOf { part ->
                        when (part) {
                            is UIMessagePart.Text -> estimateTokensByChars(part.text)
                            is UIMessagePart.Reasoning -> estimateTokensByChars(part.reasoning)
                            else -> 0
                        }
                    }
                } ?: 0
            stats.value = GenerationLiveStats(
                promptTokens = usage?.promptTokens?.takeIf { it > 0 } ?: priorUsage?.promptTokens
                    ?: estimatedPromptTokens,
                completionTokens = completionTokens,
                streamMillis = priorStreamMillis + streamMillis,
                isEstimate = usage?.completionTokens?.takeIf { it > 0 } == null,
            )
        }

        fun stampStreamDuration(currentMessages: List<UIMessage>, streamMillis: Long): List<UIMessage> {
            if (streamMillis <= 0) return currentMessages
            val last = currentMessages.lastOrNull() ?: return currentMessages
            if (last.role != MessageRole.ASSISTANT) return currentMessages
            return currentMessages.dropLast(1) + last.copy(
                streamDurationMillis = priorStreamMillis + streamMillis,
            )
        }

        if (stream) {
            // 指数退避重试：只重试「还没收到任何内容」的失败（429/5xx/网络错误）。
            // 已开始输出后失败不重试，避免重复输出；重试不丢已保留的内容。
            // 已开始输出但中断（网络/超时等可恢复错误）：先发「继续生成」指令唤醒续答，
            // 让模型在已生成的部分输出上接续，而不是从头重来或直接放弃。
            var attempt = 0
            var streamResumeAttempts = 0
            while (true) {
                // 每次尝试（含指数退避重试与续答唤醒）都是独立响应流，必须用新的
                // StreamChunkHandler——其内部持有本次流的合并索引，不可复用。
                val streamChunkHandler = StreamChunkHandler(model)
                val streamStart = SystemClock.elapsedRealtime()
                try {
                    // 跨续答尝试累计：attemptStart 只测本尝试窗口，加到 stepStreamMillis
                    fun currentStepStream(): Long =
                        stepStreamMillis + (SystemClock.elapsedRealtime() - streamStart)
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = messagesToSend,
                        params = params
                    ).collect {
                        messages = streamChunkHandler.handle(messages, it)
                        publishLiveStats(messages, currentStepStream())
                        onUpdateMessages(messages)
                    }
                    stepStreamMillis = currentStepStream()
                    messages = stampStreamDuration(messages, stepStreamMillis)
                    onUpdateMessages(messages)
                    processingStatus.value = null
                    break
                } catch (e: CancellationException) {
                    // 取消（用户停止生成/切会话）仍要把已生成的流式内容落盘，最后 emit 一次
                    stepStreamMillis += SystemClock.elapsedRealtime() - streamStart
                    messages = stampStreamDuration(messages, stepStreamMillis)
                    onUpdateMessages(messages)
                    throw e
                } catch (e: Exception) {
                    // 生成中途异常（断网/超时/服务端错误/模型不可用/参数错误）：先保留已输出的内容不丢，
                    // 再重新抛出让上层 onFailure 弹出错误提示——只 Log 不抛会把失败静默吞掉，
                    // 用户会看到消息发出去了却没回复、也没有任何报错。
                    Log.e(TAG, "stream error, preserving partial output", e)
                    onUpdateMessages(messages)
                    // 协程被取消期间收到的流式异常（如 OkHttp 取消竞态的 "stream was reset: CANCEL"）
                    // 本质是取消的副作用，应视为取消而非真实错误——否则会冒泡成用户可见报错。
                    // 取消后当前协程 isActive 立即变 false，此判断可捕获绝大多数取消竞态。
                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("Generation cancelled during stream", e)
                    }
                    // 已开始输出但中断（可恢复错误）：向模型发「继续生成」指令唤醒续答，
                    // 复用已生成的部分输出作为上下文，让模型接续而非重复。限次，避免死循环。
                    // 仅当本次流式产生了实质内容（非空文本/工具/推理）才续答：若模型刚发出
                    // TextStart 空文本就断流，部分输出为空，续答等价于从头重试，浪费一次尝试，
                    // 此时走下方普通重试更合适。
                    val streamedPart = messages.drop(originalMessagesCount)
                    val hasMeaningfulOutput =
                        streamedPart.isNotEmpty() && streamedPart.any { !it.parts.isEmptyUIMessage() } ||
                            // 输入末尾已是 ASSISTANT（resumeContext 续答时中断）：handle 原地更新，
                            // drop 为空，但 messages.last() 可能已含部分输出
                            (streamedPart.isEmpty() && messages.isNotEmpty() &&
                                messages.last().role == MessageRole.ASSISTANT &&
                                !messages.last().parts.isEmptyUIMessage())
                    if (hasMeaningfulOutput &&
                        streamResumeAttempts < MAX_STREAM_RESUME_ATTEMPTS &&
                        e.isRetryable()
                    ) {
                        streamResumeAttempts++
                        attempt++
                        Log.w(
                            TAG,
                            "stream interrupted after output, resuming with continue instruction (attempt #$streamResumeAttempts)"
                        )
                        // 中断前已流逝的流式时长计入（保留部分输出对应的等待）
                        stepStreamMillis += SystemClock.elapsedRealtime() - streamStart
                        messages = stampStreamDuration(messages, stepStreamMillis)
                        onUpdateMessages(messages)
                        // 重建发送列表：internalMessages(含 system 的完整发送列表) + 本次流式新增的部分输出
                        // + 继续指令。局部 messages 在流式中已被 streamChunkHandler 追加/更新了本次生成的
                        // assistant 内容；模型据此在上文基础上接续。
                        // - 输入末尾是 USER（常规首轮/regenerate）：handle 会新建 assistant 消息，
                        //   取 messages.drop(originalMessagesCount) 即本次部分输出。
                        // - 输入末尾已是 ASSISTANT（resumeContext 续答时中断）：handle 原地更新最后一条，
                        //   drop 为空。此时用含部分输出的 messages.last()（必为 assistant）替换/追加到
                        //   internalMessages 末尾，避免部分输出对模型不可见导致续答从头重写。
                        val resumeBase = if (streamedPart.isNotEmpty()) {
                            internalMessages + streamedPart
                        } else if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                            if (internalMessages.isNotEmpty() && internalMessages.last().role == MessageRole.ASSISTANT) {
                                // internalMessages 末尾是同一 assistant（未被 limitContext 截断）：直接替换
                                internalMessages.dropLast(1) + messages.last()
                            } else {
                                // internalMessages 末尾被截断成非 assistant：追加，保持 USER→ASSISTANT 合法序列
                                internalMessages + messages.last()
                            }
                        } else {
                            internalMessages
                        }
                        messagesToSend = resumeBase + UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(buildStreamResumeInstruction())),
                        )
                        val resumeAfterMs = (e as? HttpException)?.retryAfterMs
                        val resumeDelayMs = retryBackoffDelay(retryPolicy, attempt, resumeAfterMs)
                        if (resumeDelayMs == RETRY_STOP_DELAY) {
                            processingStatus.value = null
                            throw e
                        }
                        processingStatus.value = buildRetryStatus(attempt, retryPolicy.maxRetries, resumeDelayMs)
                        delay(resumeDelayMs)
                        processingStatus.value = null
                        continue
                    }
                    // 可重试且尚未产生实质输出（未开始或刚发出空文本就断流）：指数退避后重试整轮请求
                    if (hasMeaningfulOutput || attempt >= retryPolicy.maxRetries || !e.isRetryable()) {
                        processingStatus.value = null
                        stepStreamMillis += SystemClock.elapsedRealtime() - streamStart
                        messages = stampStreamDuration(messages, stepStreamMillis)
                        onUpdateMessages(messages)
                        throw e
                    }
                    attempt++
                    Log.w(TAG, "stream retry #$attempt/${retryPolicy.maxRetries} after ${e.message}")
                    val streamRetryAfterMs = (e as? HttpException)?.retryAfterMs
                    val streamRetryDelayMs = retryBackoffDelay(retryPolicy, attempt, streamRetryAfterMs)
                    if (streamRetryDelayMs == RETRY_STOP_DELAY) {
                        processingStatus.value = null
                        throw e
                    }
                    processingStatus.value = buildRetryStatus(attempt, retryPolicy.maxRetries, streamRetryDelayMs)
                    delay(streamRetryDelayMs)
                    processingStatus.value = null
                }
            }
        } else {
            var attempt = 0
            while (true) {
                try {
                    val nonStreamStart = SystemClock.elapsedRealtime()
                    val result = providerImpl.generateText(
                        providerSetting = provider,
                        messages = messagesToSend,
                        params = params,
                    )
                    // 非流式：整个 generateText 调用窗口计为「流式时长」（无工具夹杂）
                    stepStreamMillis += SystemClock.elapsedRealtime() - nonStreamStart
                    messages = messages.handleTextGenerationResult(result = result, model = model)
                    messages = stampStreamDuration(messages, stepStreamMillis)
                    onUpdateMessages(messages)
                    publishLiveStats(messages, stepStreamMillis)
                    processingStatus.value = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 非流式无部分输出，失败即可整体重试
                    if (attempt >= retryPolicy.maxRetries || !e.isRetryable()) {
                        processingStatus.value = null
                        throw e
                    }
                    attempt++
                    Log.w(TAG, "generateText retry #$attempt/${retryPolicy.maxRetries} after ${e.message}")
                    val retryAfterMs = (e as? HttpException)?.retryAfterMs
                    val delayMs = retryBackoffDelay(retryPolicy, attempt, retryAfterMs)
                    if (delayMs == RETRY_STOP_DELAY) {
                        processingStatus.value = null
                        throw e
                    }
                    processingStatus.value = buildRetryStatus(attempt, retryPolicy.maxRetries, delayMs)
                    delay(delayMs)
                    processingStatus.value = null
                }
            }
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)
        val fullOutputPath = "/tool_outputs/$fileName"

        // 结构安全截断：JSON 工具输出（搜索/抓取等）重编码为合法 JSON——渲染器仍能读到
        // items/urls 渲染卡片（否则 ChatMessageToolStep 解析失败 → 内容为空 map → 无卡片），
        // 同时注入文件路径与截断标记，模型仍能 cat 完整结果。非 JSON（shell 等）回退纯文本截断。
        // 对象输出注入 truncated/full_output_path；数组输出（conversation_search 等按数组读）
        // 只做有界裁剪、保持数组形状，避免渲染器读到意外结构。
        val safeJson = runCatching {
            val elem = json.parseToJsonElement(fullText)
            when (elem) {
                is JsonObject -> {
                    val bounded = boundToolOutput(elem).jsonObject.toMutableMap()
                    bounded["truncated"] = JsonPrimitive(true)
                    bounded["full_output_path"] = JsonPrimitive(fullOutputPath)
                    json.encodeToString(JsonObject(bounded))
                }

                else -> json.encodeToString(boundToolOutput(elem))
            }
        }.getOrNull()

        val truncatedText = safeJson ?: buildString {
            appendLine("[Tool output truncated: $totalChars characters total]")
            appendLine("Full output saved to: $fullOutputPath")
            appendLine("Use shell to read: `cat $fullOutputPath`")
            appendLine("Use shell to search: `grep \"pattern\" $fullOutputPath`")
            appendLine()
            append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
        }

        return listOf(UIMessagePart.Text(truncatedText)) + nonTextParts
    }

    /**
     * 执行单个工具（供并行派发调用）。返回回填 output 后的 Tool part；
     * Pending 状态返回 null（由调用方过滤）。
     */
    private suspend fun executeTool(
        tool: UIMessagePart.Tool,
        toolsInternal: List<Tool>,
        onToolStarted: suspend (UIMessagePart.Tool) -> Unit,
    ): UIMessagePart.Tool? {
        return when (tool.approvalState) {
            is ToolApprovalState.Denied -> {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                tool.copy(
                    finishedAt = Clock.System.now(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put(
                                        "error",
                                        JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                    )
                                }
                            )
                        )
                    )
                )
            }

            is ToolApprovalState.Answered -> {
                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                tool.copy(
                    finishedAt = Clock.System.now(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    output = listOf(
                        UIMessagePart.Text(answer)
                    )
                )
            }

            is ToolApprovalState.Pending -> null

            else -> {
                // Auto or Approved - execute the tool
                val startedAt = Clock.System.now()
                val startedAtMs = SystemClock.elapsedRealtime()
                val startedTool = tool.copy(
                    startedAt = startedAt,
                    startedAtMs = startedAtMs,
                )
                onToolStarted(startedTool)
                runCatching {
                    val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                        ?: error("Tool ${tool.toolName} not found")
                    val args = runCatching {
                        json.parseToJsonElement(tool.input.ifBlank { "{}" })
                    }.getOrElse {
                        error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                    }
                    Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                    // 隐藏字段 __toolCallId 只注入声明需要的工具（如 spawn_subagent 用它对齐 UI 任务 id）。
                    // 默认不注入：MCP 等工具会把 args 原样转发远程，多出的字段会让远程 schema 校验失败
                    // （如 tavily 的 pydantic unexpected_keyword_argument）。
                    val executeArgs = if (args is kotlinx.serialization.json.JsonObject && toolDef.injectToolCallId) {
                        val withId = args.toMutableMap().apply {
                            put("__toolCallId", JsonPrimitive(tool.toolCallId))
                        }
                        kotlinx.serialization.json.JsonObject(withId)
                    } else args
                    val result = toolDef.execute(executeArgs)
                    val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                    startedTool.copy(
                        startedAt = startedAt,
                        startedAtMs = startedAtMs,
                        finishedAt = Clock.System.now(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                    )
                }.getOrElse {
                    // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Tool execution failed", it)
                    startedTool.copy(
                        startedAt = startedAt,
                        startedAtMs = startedAtMs,
                        finishedAt = Clock.System.now(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put(
                                            "error",
                                            JsonPrimitive(buildString {
                                                append("[${it.javaClass.name}] ${it.message}")
                                                append("\n${it.stackTraceToString()}")
                                            })
                                        )
                                    }
                                )
                            )
                        )
                    )
                }
            }
        }
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            // 流式翻译：只在「还未输出任何译文」时重试，避免已输出片段后重试造成重复译文。
            val translateRetryPolicy = RetryPolicy(maxRetries = 2, initialDelayMs = 400, maxDelayMs = 5_000)
            var attempt = 0
            while (true) {
                var messages = listOf(UIMessage.user(prompt))
                var translatedText = ""
                val streamChunkHandler = StreamChunkHandler(model)
                try {
                    providerHandler.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = TextGenerationParams(
                            model = model,
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        ),
                    ).collect { chunk ->
                        messages = streamChunkHandler.handle(messages, chunk)
                        translatedText = messages.lastOrNull()?.toText() ?: ""

                        if (translatedText.isNotBlank()) {
                            onStreamUpdate?.invoke(translatedText)
                            emit(translatedText)
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (translatedText.isNotBlank() ||
                        attempt >= translateRetryPolicy.maxRetries ||
                        !e.isRetryable()
                    ) {
                        throw e
                    }
                    attempt++
                    val retryAfterMs = (e as? HttpException)?.retryAfterMs
                    val delayMs = retryBackoffDelay(translateRetryPolicy, attempt, retryAfterMs)
                    if (delayMs == RETRY_STOP_DELAY) throw e
                    Log.w(TAG, "translate stream retry #$attempt/${translateRetryPolicy.maxRetries} after ${e.message}")
                    delay(delayMs)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = retryWithPolicy(
                RetryPolicy(maxRetries = 2, initialDelayMs = 400, maxDelayMs = 5_000)
            ) {
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                        topP = 0.95f,
                        customBody = listOf(
                            CustomBody(
                                key = "translation_options",
                                value = buildJsonObject {
                                    put("source_lang", JsonPrimitive("auto"))
                                    put(
                                        "target_lang",
                                        JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    )
                                }
                            )
                        )
                    ),
                )
            }
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 拍照搜题核心入口：图片（+可选补充文本）→ 流式解题。
 *
 * 模型路由：
 * - 解题模型（settings.solveModelId）支持 IMAGE 输入 → 直接多模态请求；
 * - 不支持且配置了 OCR 模型（settings.ocrModelId）→ 先把图片 OCR 成文本，再纯文本解题
 *   （独立实现而非复用 OcrTransformer.performOcr：后者的静默兜底 "[Image]"/"[ERROR..]"
 *   适合聊天上下文注入，不适合解题场景——OCR 失败必须让用户看到可读错误而非解题跑偏）；
 * -两者皆无 → 抛 IllegalStateException（UI 显示配置引导）。
 *
 * 重试语义与 translateText 一致：仅在尚未输出任何正文时重试，已输出后失败直接抛出，
 * 避免重试造成内容重复。
 */
fun solveQuestion(
    settings: Settings,
    imageUris: List<String>,
    questionText: String? = null,
    onStreamUpdate: ((SolveStreamUpdate) -> Unit)? = null,
): Flow<SolveResult> = flow {
    require(imageUris.isNotEmpty() || !questionText.isNullOrBlank()) {
        "solveQuestion requires at least one image or question text"
    }

    val model = settings.providers.findModelById(settings.solveModelId)
        ?: error("解题模型未配置或已失效，请到设置中配置默认解题模型")
    val provider = model.findProvider(settings.providers)
        ?: error("解题模型对应的服务商不存在")
    val providerHandler = providerManager.getProviderByType(provider)

    val supportsVision = model.inputModalities.contains(Modality.IMAGE)
    // 语言规则：{user_lang} 占位符按系统语言注入，保证输出跟随用户语言（题干/图片语言可能不同）
    val userLang = Locale.getDefault().let { "${it.displayName} (${it.toLanguageTag()})" }
    val solvePrompt = settings.solvePrompt.applyPlaceholders("user_lang" to userLang)
    // 降级路径下实际发给模型的题干文本（OCR 结果 + 用户补充），供调用方落库
    var degradedQuestionText: String? = null
    val messages: List<UIMessage> = if (supportsVision) {
        buildList {
            add(UIMessage.system(solvePrompt))
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = buildList {
                        imageUris.forEach { add(UIMessagePart.Image(it)) }
                        val text = buildString {
                            append("Please solve the problem in the attached image(s).")
                            if (!questionText.isNullOrBlank()) {
                                append("\n\nAdditional notes from the user:\n")
                                append(questionText)
                            }
                        }
                        add(UIMessagePart.Text(text))
                    }
                )
            )
        }
    } else {
        // 降级：解题模型无视觉能力 → OCR 转文本后纯文本解题（共享实现见 SolveOcr.kt）
        if (imageUris.isNotEmpty()) {
            val ocrTexts = ocrSolveImages(providerManager, settings, imageUris)
            val combinedQuestion = buildString {
                append(ocrTexts.joinToString(separator = "\n\n"))
                if (!questionText.isNullOrBlank()) {
                    append("\n\nAdditional notes from the user:\n")
                    append(questionText)
                }
            }
            degradedQuestionText = combinedQuestion
            listOf(UIMessage.system(solvePrompt), UIMessage.user(combinedQuestion))
        } else {
            degradedQuestionText = questionText
            listOf(
                UIMessage.system(solvePrompt),
                UIMessage.user(questionText!!),
            )
        }
    }

    // 与翻译相同的重试策略：只在无任何正文输出时重试，避免重复内容
    val solveRetryPolicy = RetryPolicy(maxRetries = 2, initialDelayMs = 400, maxDelayMs = 5_000)
    var attempt = 0
    while (true) {
        var messages = messages
        var reasoningText = ""
        var fullText = ""
        val streamChunkHandler = StreamChunkHandler(model)
        try {
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    // 思考默认跟随模型默认（AUTO）：fromBudgetTokens(0) 是 OFF（显式关闭），
                    // 把默认预算 0 当 OFF 会让解题默认无思考流；用户显式配置 >0 才按预算档位
                    reasoningLevel = if (settings.solveThinkingBudget > 0) {
                        ReasoningLevel.fromBudgetTokens(settings.solveThinkingBudget)
                    } else {
                        ReasoningLevel.AUTO
                    },
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val lastMessage = messages.lastOrNull()
                reasoningText = lastMessage?.parts
                    ?.filterIsInstance<UIMessagePart.Reasoning>()
                    ?.joinToString("") { it.reasoning }
                    ?: ""
                fullText = lastMessage?.toText() ?: ""
                val blocks = splitSolveOutput(fullText)
                onStreamUpdate?.invoke(
                    SolveStreamUpdate(
                        statement = blocks.statement,
                        reasoning = reasoningText,
                        process = blocks.process,
                        finalAnswer = blocks.finalAnswer,
                    )
                )
            }
            val blocks = splitSolveOutput(fullText)
            emit(
                SolveResult(
                    statement = blocks.statement,
                    reasoning = reasoningText,
                    process = blocks.process,
                    finalAnswer = blocks.finalAnswer,
                    questionText = degradedQuestionText,
                )
            )
            break
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fullText.isNotBlank() ||
                attempt >= solveRetryPolicy.maxRetries ||
                !e.isRetryable()
            ) {
                throw e
            }
            attempt++
            val retryAfterMs = (e as? HttpException)?.retryAfterMs
            val delayMs = retryBackoffDelay(solveRetryPolicy, attempt, retryAfterMs)
            if (delayMs == RETRY_STOP_DELAY) throw e
            Log.w(TAG, "solve stream retry #$attempt/${solveRetryPolicy.maxRetries} after ${e.message}")
            delay(delayMs)
        }
    }
    }.flowOn(Dispatchers.IO)

/**
 * 结果页页内追问：对已解出的题目（题干 + 解题过程 + 精炼作答，可选题图）追加单轮提问。
 *
 * 与 [solveQuestion] 的差异（根因：追问是"接着解答聊天"，不是新解题）：
 * - 不分段输出（无 <final_answer> 切分），回答整段流式给调用方；
 * - 无 OCR 降级 / 无多轮重试（交互型问答，失败让用户直接重发即可）；
 * - 模型 = solve 模型；支持视觉时随问题携带题图，否则纯文本（沿用 solve 的降级假设，
 *   无视觉时不发图避免 provider 报错）。
 * 思考预算沿用 solve 的档位规则；思考文本与回答文本都随流式回调返回（调用方负责展示，
 * 不在此丢弃——追问同样是会"先想后答"的任务）。
 */
fun followUpQuestion(
    settings: Settings,
    question: String,
    contextText: String,
    imageUris: List<String>,
    onStreamUpdate: ((FollowUpStreamUpdate) -> Unit)? = null,
): Flow<FollowUpStreamUpdate> = flow {
    require(question.isNotBlank()) { "followUpQuestion requires a question" }
    val model = settings.providers.findModelById(settings.solveModelId)
        ?: error("解题模型未配置或已失效，请到设置中配置默认解题模型")
    val provider = model.findProvider(settings.providers)
        ?: error("解题模型对应的服务商不存在")
    val providerHandler = providerManager.getProviderByType(provider)
    val supportsVision = model.inputModalities.contains(Modality.IMAGE)

    var messages: List<UIMessage> = buildList {
        add(UIMessage.system(contextText))
        add(
            UIMessage(
                role = MessageRole.USER,
                parts = buildList {
                    if (supportsVision) {
                        imageUris.forEach { add(UIMessagePart.Image(it)) }
                    }
                    add(UIMessagePart.Text(question))
                }
            )
        )
    }
    val streamChunkHandler = StreamChunkHandler(model)
    var reasoningText = ""
    var fullText = ""
    providerHandler.streamText(
        providerSetting = provider,
        messages = messages,
        params = TextGenerationParams(
            model = model,
            // 思考档位与解题一致：显式预算 >0 按档位，否则跟随模型默认
            reasoningLevel = if (settings.solveThinkingBudget > 0) {
                ReasoningLevel.fromBudgetTokens(settings.solveThinkingBudget)
            } else {
                ReasoningLevel.AUTO
            },
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        ),
    ).collect { chunk ->
        messages = streamChunkHandler.handle(messages, chunk)
        reasoningText = messages.lastOrNull()?.parts
            ?.filterIsInstance<UIMessagePart.Reasoning>()
            ?.joinToString("") { it.reasoning }
            ?: ""
        fullText = messages.lastOrNull()?.toText() ?: ""
        if (reasoningText.isNotBlank() || fullText.isNotBlank()) {
            onStreamUpdate?.invoke(FollowUpStreamUpdate(reasoning = reasoningText, text = fullText))
        }
    }
    if (reasoningText.isBlank() && fullText.isBlank()) {
        error("模型未返回有效回答，请重试")
    }
    emit(FollowUpStreamUpdate(reasoning = reasoningText, text = fullText))
}.flowOn(Dispatchers.IO)
}

/** 追问单次流式回调载荷：思考全文 + 回答全文（均为累积值，非增量） */
data class FollowUpStreamUpdate(
    val reasoning: String,
    val text: String,
)

/**
 * 给最后一条 assistant 消息设置/清除 finishedAt。
 * 整个生成回合（含工具循环）结束前应保持 null，结束后才写入时间。
 */
private fun List<UIMessage>.markLastAssistantFinished(finished: Boolean): List<UIMessage> {
    val last = lastOrNull() ?: return this
    if (last.role != MessageRole.ASSISTANT) return this
    return dropLast(1) + last.copy(
        finishedAt = if (finished) {
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        } else {
            null
        }
    )
}

/** 单次请求内复用的工具 schema 统计：chars 供 PromptMetrics，tokens 供上下文构成快照。 */
private data class ToolSchemaStats(
    val schemaChars: Int,
    val tokens: Int,
)
