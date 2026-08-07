package me.rerere.rikkahub.data.ai

import android.content.Context
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
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
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.ai.subagent.boundJson
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.IOException
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// ---- 生成重试（429 / 5xx / 网络错误的指数退避）----
private const val MAX_GENERATION_RETRIES = 2
private const val RETRY_INITIAL_BACKOFF_MS = 1000L

// ---- 防失控启发式（对齐 NoteGen 的 toolResultEvidence / MAX_IDENTICAL_READ_RESULT_REPEATS）----
private const val MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS = 2
private const val MAX_IDENTICAL_READ_RESULT_REPEATS = 2
private const val AUTO_STOP_NOTICE = "\n\n---\n*模型连续多轮没有新进展，已自动停止。你可以继续发送消息。*"

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

/** 解析工具对应的串行化 key：workspace 文件类工具按 (workspaceId, path)，shell 按 workspaceId；非 workspace 工具返回 null（不串行） */
private fun workspaceLockKeyFor(
    toolName: String,
    inputJson: JsonObject?,
    workspaceId: String?,
): WorkspaceToolLockKey? {
    if (workspaceId.isNullOrBlank()) return null
    return when (toolName) {
        "workspace_edit_file", "workspace_write_file" -> {
            val path = inputJson?.get("path")?.jsonPrimitive?.contentOrNull
            if (path.isNullOrBlank()) return null
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
    json: Json,
    execute: suspend (UIMessagePart.Tool, List<Tool>) -> UIMessagePart.Tool?,
): UIMessagePart.Tool? {
    val inputJson = runCatching {
        json.parseToJsonElement(tool.input.ifBlank { "{}" }) as? JsonObject
    }.getOrNull()
    val key = workspaceLockKeyFor(tool.toolName, inputJson, workspaceId) ?: return execute(tool, toolsInternal)
    val mutex = workspaceToolLocks.getOrPut(key) { Mutex() }
    return mutex.withLock {
        execute(tool, toolsInternal)
    }
}

private fun Throwable.isRetryable(): Boolean = when (this) {
    is HttpException -> code != null && (code == 429 || code in 500..599)
    is IOException -> true
    else -> false
}

/** 指数退避：第 1 次重试前等 1s，第 2 次 2s，第 3 次 4s */
private fun backoffDelay(attempt: Int): Long = RETRY_INITIAL_BACKOFF_MS * (1L shl (attempt - 1))

/** 工具输出的轻量指纹（toolName + 文本部分），用于检测「重复读 / 无新证据」 */
private fun UIMessagePart.Tool.signature(): String {
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\\n") { it.text }
    return "$toolName:$text"
}

/**
 * 构造「用户引导」续答指令（steering / sendGuidance 共用）：
 * 作为 provider 看到的最后一条 USER 消息注入，提示模型在既有回复上续答而非从头重来。
 */
internal fun buildGuidanceInstruction(text: String): String = buildString {
    appendLine("## User guidance")
    appendLine("The user has sent you the following guidance. Continue your existing response according to it:")
    appendLine("```")
    appendLine(text.take(1000))
    appendLine("```")
    appendLine("- Follow the guidance in your ongoing reply. Do NOT re-answer from scratch; build on your existing answer.")
    appendLine("- If you were waiting for a running sub-agent, you may incorporate this guidance now and continue when results arrive.")
}

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
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationId: String? = null,
        /** 续答唤醒指令（子代理完成时注入）。追加为 provider 看到的**最后一条 USER 消息**，
         *  不写进 system（保持 system 前缀字节不变 → prompt cache 命中，对齐 Claude Code
         *  "用消息不用 prompt 编辑"的做法）。只进 internalMessages（发送列表），不落持久化列表，
         *  因此不会触发 handleMessageChunk 分段。默认 null 不影响普通生成。 */
        resumeContext: String? = null,
        /** steering 信号：会话级待注入引导。非空时在下一轮边界（工具调用/输出完成）消费，
         *  注入为 user_guidance 气泡 + 续答指令，不打断当前流式输出。 */
        steeringSignal: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
        /** steering 被消费回调（UI 清「引导已排入」chip） */
        onSteeringConsumed: (() -> Unit)? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        // 防失控启发式状态（跨轮累积）：
        // seenSignatures = 所有轮见过的工具输出指纹；noProgressRounds = 连续无新证据轮数；
        // lastSignatureByName / identicalRepeatByName = 同一工具连续相同输出计数
        val seenSignatures = mutableSetOf<String>()
        var noProgressRounds = 0
        val lastSignatureByName = mutableMapOf<String, String>()
        val identicalRepeatByName = mutableMapOf<String, Int>()

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // steering：本轮边界消费待注入引导（上一个工具调用/输出完成后的自然边界），
            // 注入可见 user_guidance 气泡并作为本轮续答指令——不打断上一轮已完成的输出，
            // 因此不用等整个回合结束才进入引导。无待注入引导时沿用外部 resumeContext。
            val pendingSteering = steeringSignal?.value
            val effectiveResumeContext = if (!pendingSteering.isNullOrBlank()) {
                steeringSignal?.value = null
                onSteeringConsumed?.invoke()
                val guidancePart = UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = "user_guidance",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text(
                            buildJsonObject { put("text", JsonPrimitive(pendingSteering)) }.toString()
                        )
                    ),
                    approvalState = ToolApprovalState.Approved,
                )
                val lastMsg = messages.lastOrNull()
                messages = if (lastMsg?.role == MessageRole.ASSISTANT) {
                    // 正常情况：追加到最后一条 assistant 气泡
                    messages.dropLast(1) + lastMsg.copy(parts = lastMsg.parts + guidancePart)
                } else {
                    // 空消息或最后一条是 USER（用户刚发完还没产出 AI 气泡）：
                    // 新建 assistant 消息承载引导，避免气泡落进用户消息
                    messages + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(guidancePart))
                }
                emit(GenerationChunk.Messages(messages))
                buildGuidanceInstruction(pendingSteering)
            } else {
                resumeContext
            }

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
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
                            conversationId = conversationId,
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
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
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    conversationId = conversationId,
                    resumeContext = effectiveResumeContext,
                )
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
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // 母代理这轮纯文本没调工具，正常结束。
                    // 若仍有已派发但未取结果的子代理，交给完成事件流异步唤醒续答，
                    // 不再强制续轮干等（见 ChatService.resumeAfterSubAgent）。
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
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
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            // 并行执行：多个工具（含多子代理派发）并发跑，按原始顺序回填结果。
            // workspace 文件类工具经 executeToolSerialized 串行化（同一文件/同一 workspace 的
            // shell 依次执行），防止同文件并发编辑的"读-改-写"竞态；其余工具仍并行。
            val workspaceIdForLock = assistant.workspaceId?.toString()
            val executedTools = coroutineScope {
                toolsToProcess.map { tool ->
                    async {
                        executeToolSerialized(tool, toolsInternal, workspaceIdForLock, json, ::executeTool)
                    }
                }.awaitAll().filterNotNull()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
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
                        conversationId = conversationId,
                    )
                )
            )

            // ---- 防失控启发式（对齐 NoteGen runtime.ts 的 toolResultEvidence / MAX_IDENTICAL_READ_RESULT_REPEATS）----
            // 1) 连续多轮工具输出没有任何新内容（无新证据）→ 自动收尾
            val currentSignatures = executedTools.map { it.signature() }
            val hasNewEvidence = currentSignatures.any { it !in seenSignatures }
            if (hasNewEvidence) {
                noProgressRounds = 0
                seenSignatures.addAll(currentSignatures)
            } else {
                noProgressRounds++
            }

            // 2) 同一工具连续多次返回完全相同输出（重复读死循环）→ 自动收尾。
            //    仅在「本轮无新证据」时才判定——若有新进展（新工具/新输出）说明 AI 在正常推进，
            //    即使某个稳定输出工具（如 kb_list）返回相同结果也不算死循环，避免误杀。
            var repeatedRead = false
            if (!hasNewEvidence) {
                executedTools.forEach { tool ->
                    val sig = tool.signature()
                    val last = lastSignatureByName[tool.toolName]
                    if (last == sig) {
                        val count = (identicalRepeatByName[tool.toolName] ?: 1) + 1
                        identicalRepeatByName[tool.toolName] = count
                        if (count >= MAX_IDENTICAL_READ_RESULT_REPEATS) repeatedRead = true
                    } else {
                        identicalRepeatByName[tool.toolName] = 1
                    }
                    lastSignatureByName[tool.toolName] = sig
                }
            }

            if (repeatedRead || noProgressRounds >= MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS) {
                val reason = if (repeatedRead) {
                    "same tool returned identical result repeatedly"
                } else {
                    "no new progress for $MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS rounds"
                }
                Log.i(TAG, "generateText: auto-stop ($reason)")
                val lastMsg = messages.last()
                messages = messages.dropLast(1) + lastMsg.copy(
                    parts = lastMsg.parts + UIMessagePart.Text(AUTO_STOP_NOTICE)
                )
                emit(GenerationChunk.Messages(messages))
                break
            }
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
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationId: String? = null,
        resumeContext: String? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                // 行为层：决策/工具/子代理/提问准则（默认开，可关）。放末尾，不覆盖用户自定义提示词
                if (settings.enableAgentBehaviorPrompt) {
                    appendLine()
                    append(buildAgentBehaviorPrompt(tools))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
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
            conversationId = conversationId,
        )

        // 续答唤醒指令：作为 provider 看到的最后一条 USER 消息追加（transforms 之后，避免被
        // 输入转换器改写；不写 system 保持缓存前缀稳定）。只进 internalMessages 发送列表，
        // 不落持久化 messages —— handleMessageChunk 仍并入上一条 assistant 消息，不分段。
        val messagesToSend = if (!resumeContext.isNullOrBlank()) {
            internalMessages + UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(resumeContext)),
            )
        } else {
            internalMessages
        }

        var messages: List<UIMessage> = messages
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
            }
        )
        if (stream) {
            // 指数退避重试：只重试「还没收到任何内容」的失败（429/5xx/网络错误）。
            // 已开始输出后失败不重试，避免重复输出；重试不丢已保留的内容。
            val streamChunkHandler = StreamChunkHandler(model)
            var attempt = 0
            var receivedAnyChunk = false
            while (true) {
                try {
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = messagesToSend,
                        params = params
                    ).collect {
                        receivedAnyChunk = true
                        messages = streamChunkHandler.handle(messages, it)
                        onUpdateMessages(messages)
                    }
                    break
                } catch (e: CancellationException) {
                    // 取消（用户停止生成/切会话）仍要把已生成的流式内容落盘，最后 emit 一次
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
                    // 可重试且尚未开始输出：指数退避后重试整轮请求
                    if (receivedAnyChunk || attempt >= MAX_GENERATION_RETRIES || !e.isRetryable()) {
                        throw e
                    }
                    attempt++
                    Log.w(TAG, "stream retry #$attempt after ${e.message}")
                    delay(backoffDelay(attempt))
                }
            }
        } else {
            var attempt = 0
            while (true) {
                try {
                    val result = providerImpl.generateText(
                        providerSetting = provider,
                        messages = messagesToSend,
                        params = params,
                    )
                    messages = messages.handleTextGenerationResult(result = result, model = model)
                    onUpdateMessages(messages)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 非流式无部分输出，失败即可整体重试
                    if (attempt >= MAX_GENERATION_RETRIES || !e.isRetryable()) {
                        throw e
                    }
                    attempt++
                    Log.w(TAG, "generateText retry #$attempt after ${e.message}")
                    delay(backoffDelay(attempt))
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
                    val bounded = boundJson(elem).jsonObject.toMutableMap()
                    bounded["truncated"] = JsonPrimitive(true)
                    bounded["full_output_path"] = JsonPrimitive(fullOutputPath)
                    json.encodeToString(JsonObject(bounded))
                }

                else -> json.encodeToString(boundJson(elem))
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
    ): UIMessagePart.Tool? {
        return when (tool.approvalState) {
            is ToolApprovalState.Denied -> {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                tool.copy(
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
                    output = listOf(
                        UIMessagePart.Text(answer)
                    )
                )
            }

            is ToolApprovalState.Pending -> null

            else -> {
                // Auto or Approved - execute the tool
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
                    tool.copy(
                        output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                    )
                }.getOrElse {
                    // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Tool execution failed", it)
                    tool.copy(
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

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
            val streamChunkHandler = StreamChunkHandler(model)

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
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
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
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
