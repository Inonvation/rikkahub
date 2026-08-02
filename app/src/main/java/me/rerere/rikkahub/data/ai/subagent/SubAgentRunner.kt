package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 进程内任务表容量上限：超出淘汰最早完成的任务，防止无界增长 */
private const val MAX_TASKS = 50

/**
 * 子代理执行引擎：接收母代理派发的任务，解析模型、装配工具、跑独立回合，
 * 维护进程内任务状态表（taskId -> SubAgentTask），供 UI 实时订阅。
 *
 * 执行模式（await 驱动）：
 * - [runAsync] 在 AppScope detached 后台运行，立即返回 taskId（子代理独立于母代理 job）。
 * - 母代理通过 [awaitTask] 阻塞等待任务终态并取结果。
 */
class SubAgentRunner(
    private val appScope: AppScope,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val toolAssembler: SubAgentToolAssembler,
    private val conversationRepo: ConversationRepository,
) {
    private val _tasks = MutableStateFlow<Map<String, SubAgentTask>>(emptyMap())
    val tasksFlow: StateFlow<Map<String, SubAgentTask>> = _tasks.asStateFlow()

    /** 任务 Job 登记表：taskId -> Job，供 [cancel] 真正取消协程（v2 异步用） */
    private val taskJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** UI 实时订阅单个任务的进度（taskId == 母代理 Tool.toolCallId） */
    fun observeTask(taskId: String): kotlinx.coroutines.flow.Flow<SubAgentTask?> =
        tasksFlow.map { it[taskId] }

    /** 同步读取单个任务的当前状态 */
    fun getTask(taskId: String): SubAgentTask? = _tasks.value[taskId]

    /**
     * 阻塞等待任务到达终态（await_subagent 工具用）。
     * 基于 [observeTask]（StateFlow 多收集器安全，不抢 completions 事件）。
     * 任务不存在 → 立即返回 null（调用方按 not_found 处理）；已终态 → 立即返回。
     */
    suspend fun awaitTask(taskId: String): SubAgentTask? =
        observeTask(taskId).filter { it == null || it.status.isTerminal }.first()

    /** 该任务是否可取消（仅异步任务登记在 taskJobs，有独立 job 句柄） */
    fun isCancellable(taskId: String): Boolean = taskJobs.containsKey(taskId)

    /**
     * 解析子代理最终模型（优先级）：
     * 任务指定 modelId > 子代理定义 defaultModelId > Settings.subAgentModelId > Settings.chatModelId。
     * UI 审查 #5：配置的 modelId 失效（如被删除）时回落到下一个优先级，而非直接 FAILED。
     */
    private fun resolveModel(
        settings: Settings,
        def: SubAgentDefinition,
        request: SubAgentRequest,
    ): me.rerere.ai.provider.Model? {
        val candidates = listOfNotNull(
            request.modelId,
            def.defaultModelId,
            settings.subAgentModelId,
            settings.chatModelId,
        )
        // 逐个尝试解析，优先命中第一个有效的；全失效才返回 null
        candidates.forEach { id ->
            val m = settings.findModelById(id)
            if (m != null) return m
        }
        return null
    }

    /**
     * 执行单个子代理回合直到终态（runAsync 在 detached 协程里调用）。
     * 取消沿调用协程链传播（调用方 cancel 时，withTimeout 协程也被取消）。
     *
     * @param taskId 可选：由调用方指定（runAsync 传入预先生成的 id）；默认内部生成
     */
    suspend fun runSynchronously(
        request: SubAgentRequest,
        parentConversationId: Uuid,
        taskId: String = Uuid.random().toString(),
    ): SubAgentTask {
        val def = SubAgentCatalog.byId(request.agentId)
        // M4: 用 first() 等设置加载完成，避免冷启动拿到 dummy settings
        val settings = settingsStore.settingsFlow.first()

        // 注册任务（QUEUED）
        val initial = SubAgentTask(
            taskId = taskId,
            agentId = request.agentId,
            parentConversationId = parentConversationId,
            request = request.task,
            modelId = request.modelId,
        )
        _tasks.update { (it + (taskId to initial)).trimToMax() }

        if (def == null) {
            val failed = initial.copy(
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = "Unknown subagent: ${request.agentId}",
            )
            _tasks.update { it + (taskId to failed) }
            return failed
        }

        var task = initial.copy(status = SubAgentStatus.RUNNING, startedAt = Clock.System.now())
            .addStep("子代理 ${def.name} 开始执行")
        _tasks.update { it + (taskId to task) }

        try {
            val result = withTimeout(def.timeoutSeconds * 1000) {
                runInternal(def, settings, request, task)
            }
            task = result
        } catch (e: TimeoutCancellationException) {
            // 超时保留已输出的部分结果：从结构化消息提取摘要，母代理仍能读到已生成内容，
            // 而不是只看到"任务超时"的空壳。task.messages 由 onMessagesUpdate 实时更新，
            // 即使 runInternal 被取消，task 变量里已累积的流式内容仍在。
            val partialSummary = subAgentResultSummary(task.messages).first
                .takeIf { it.isNotBlank() }
                ?: task.streamText.takeIf { it.isNotBlank() }
            task = task.copy(
                status = SubAgentStatus.TIMEOUT,
                finishedAt = Clock.System.now(),
                error = "任务超时（${def.timeoutSeconds}s）",
                resultSummary = task.resultSummary ?: partialSummary,
            )
        } catch (e: CancellationException) {
            task = task.copy(
                status = SubAgentStatus.CANCELLED,
                finishedAt = Clock.System.now(),
                error = "任务已取消",
            )
            _tasks.update { it + (taskId to task) }
            taskJobs.remove(taskId)?.cancel()
            throw e
        } catch (e: Exception) {
            // 执行异常同样保留已输出内容，避免失败时母代理读到空壳
            val partialSummary = subAgentResultSummary(task.messages).first
                .takeIf { it.isNotBlank() }
                ?: task.streamText.takeIf { it.isNotBlank() }
            task = task.copy(
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = e.message ?: e.javaClass.simpleName,
                resultSummary = task.resultSummary ?: partialSummary,
            )
        }
        _tasks.update { it + (taskId to task) }
        taskJobs.remove(taskId)
        return task
    }

    private suspend fun runInternal(
        def: SubAgentDefinition,
        settings: Settings,
        request: SubAgentRequest,
        initial: SubAgentTask,
    ): SubAgentTask {
        val model = resolveModel(settings, def, request)
            ?: return initial.copy(
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = "子代理模型未配置",
            )
        val providerSetting = model.findProvider(settings.providers)
            ?: return initial.copy(
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = "模型 ${model.displayName} 的 provider 未配置",
            )
        val providerImpl = providerManager.getProviderByType(providerSetting)

        val parentConversation = runCatching {
            conversationRepo.getConversationById(initial.parentConversationId)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            null
        } ?: Conversation.ofId(id = initial.parentConversationId)

        var task = initial

        fun addStep(message: String): SubAgentTask {
            task = task.addStep(message)
            _tasks.update { it + (task.taskId to task) }
            return task
        }

        addStep("解析子代理模型 ${model.displayName}")

        // 装配工具（按能力）
        val tools = withContext(Dispatchers.IO) {
            toolAssembler.assemble(def, settings, parentConversation)
        }
        addStep("装配工具 ${tools.size} 个")

        // 构造消息：system（子代理职责 + 工具提示词）+ 父代理上下文摘要 + 用户任务
        val system = buildString {
            append(def.systemPrompt)
            // 统一输出要求：让最终结果对母代理可整合（母代理只收到 resultSummary，看不到中间过程）。
            // 明确"最终输出 = 返回给母代理的 summary"，格式与 await_subagent 返回的 summary 字段语义一致。
            appendLine()
            appendLine(
                """
                ## Final Output Contract
                Your final response is returned verbatim as the result summary to the parent agent,
                which synthesizes the final answer for the user. Therefore:
                - Lead with your complete conclusion (directly usable for synthesis).
                - Include key data and sources (numbers, URLs, file names) inline — never say "see above".
                - Be concise and conclusion-focused; do not narrate your process or repeat tool output.
                """.trimIndent()
            )
            // 各工具注入 prompt（搜索/技能等工具用 systemPrompt 字段暴露说明）
            tools.forEach { tool ->
                val prompt = tool.systemPrompt(model, listOf(UIMessage.user(request.task)))
                if (prompt.isNotBlank()) {
                    appendLine()
                    append(prompt)
                }
            }
        }
        val messages = buildList {
            add(UIMessage.system(system))
            // 子代理主任务紧接 system（任务上下文最近，且保持 system→task 前缀稳定，利于缓存）
            add(UIMessage.user(request.task))
            // 父代理最近上下文摘要（控制 token；L2: 坏 DB 记录不拖垮子代理）。
            // 放在任务之后作补充背景，避免打断 system→task 前缀
            val parentSummary = runCatching {
                parentContextSummary(parentConversation)
            }.getOrNull() ?: ""
            if (parentSummary.isNotBlank()) {
                add(UIMessage.user("母代理上下文摘要（背景参考）：\n$parentSummary"))
            }
        }

        addStep("开始子代理回合（${def.maxSteps} 步上限）")

        val resultMessages = withContext(Dispatchers.IO) {
            subAgentRunLoop(
                json = json,
                providerImpl = providerImpl,
                providerSetting = providerSetting,
                messages = messages,
                tools = tools,
                params = TextGenerationParams(
                    model = model,
                    tools = tools,   // 关键：不带 tools 模型不会产出 tool call
                    reasoningLevel = ReasoningLevel.AUTO,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
                maxSteps = def.maxSteps,
                onStep = { addStep(it) },
                onToolCall = { toolName, inputSummary ->
                    // 记录子代理调用的工具（供 UI 内联展示）
                    task = task.addToolCall(toolName, inputSummary)
                    _tasks.update { it + (task.taskId to task) }
                },
                onStreamUpdate = { text ->
                    // 实时流式输出：仅在有文本时更新；且任务已是终态（如超时被置 TIMEOUT）时不覆盖，
                    // 避免迟到的流式回调把终态改回 RUNNING 卡死（MED-4）
                    if (text.isNotBlank()) {
                        task = task.copy(streamText = text)
                        _tasks.update { map ->
                            val current = map[task.taskId]
                            if (current == null || !current.status.isTerminal) {
                                map + (task.taskId to task)
                            } else map
                        }
                    }
                },
                onReasoningUpdate = { delta ->
                    // 实时思考内容：追加累积（与 streamText 不同的语义：文本是全量覆盖、思考是追加）。
                    // 终态保护与 streamText 一致：迟到的思考回调不覆盖终态。
                    if (delta.isNotBlank()) {
                        task = task.copy(reasoning = task.reasoning + delta)
                        _tasks.update { map ->
                            val current = map[task.taskId]
                            if (current == null || !current.status.isTerminal) {
                                map + (task.taskId to task)
                            } else map
                        }
                    }
                },
                onMessagesUpdate = { msg ->
                    // 实时结构化消息序列（供 UI 重建思维链时间线）。终态保护与 streamText 一致。
                    task = task.copy(messages = msg)
                    _tasks.update { map ->
                        val current = map[task.taskId]
                        if (current == null || !current.status.isTerminal) {
                            map + (task.taskId to task)
                        } else map
                    }
                },
            )
        }

        val (summary, resultParts) = subAgentResultSummary(resultMessages)
        val final = task.copy(
            status = SubAgentStatus.SUCCEEDED,
            finishedAt = Clock.System.now(),
            resultSummary = summary.takeIf { it.isNotBlank() } ?: "子代理执行完成",
            result = resultParts,
            // 兜底：即使 onMessagesUpdate 漏推，终态也保存完整结构化消息
            messages = resultMessages,
        )
        _tasks.update { it + (task.taskId to final) }
        return final
    }

    /** 父代理最近消息的文本摘要，控制注入 token */
    private fun parentContextSummary(conversation: Conversation): String {
        return conversation.currentMessages
            .takeLast(6)
            .joinToString(separator = "\n---\n") { msg ->
                val role = if (msg.role == MessageRole.USER) "用户" else "助手"
                "$role: ${msg.toText()}"
            }
            .take(1000)
    }

    private fun UIMessage.toText(): String = parts.joinToString(separator = " ") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    // ---- 异步派发 ----

    /** 后台执行：立即返回 taskId，任务在 AppScope detached 运行。taskId 与内部注册一致。 */
    fun runAsync(request: SubAgentRequest, parentConversationId: Uuid, taskId: String = Uuid.random().toString()): String {
        val job = appScope.launch(start = CoroutineStart.DEFAULT) {
            runSynchronously(request, parentConversationId, taskId = taskId)
        }
        taskJobs[taskId] = job
        job.invokeOnCompletion { taskJobs.remove(taskId) }
        return taskId
    }

    /** 取消任务：取消关联协程并置状态为 CANCELLED。
     *  仅对异步任务（[runAsync]，登记在 taskJobs）有效并返回 true；未登记的返回 false。
     *  调用方据此决定是否显示取消按钮。 */
    fun cancel(taskId: String): Boolean {
        val job = taskJobs[taskId] ?: return false
        job.cancel()
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            if (task.status == SubAgentStatus.QUEUED || task.status == SubAgentStatus.RUNNING) {
                map + (taskId to task.copy(status = SubAgentStatus.CANCELLED, error = "用户取消"))
            } else map
        }
        return true
    }
}

/** 任务表容量裁剪：保留最近 MAX_TASKS 条 */
private fun Map<String, SubAgentTask>.trimToMax(): Map<String, SubAgentTask> {
    if (size <= MAX_TASKS) return this
    // 按创建时间保留最近的 MAX_TASKS 条
    val sorted = entries.sortedByDescending { it.value.createdAt }
    return sorted.take(MAX_TASKS).associate { it.toPair() }
}
