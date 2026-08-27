package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.SubAgentUsageDAO
import me.rerere.rikkahub.data.db.dao.SubAgentTaskDAO
import me.rerere.rikkahub.data.db.entity.SubAgentUsageEntity
import me.rerere.rikkahub.data.db.entity.SubAgentTaskEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ai.canonicalToolOrder
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.ai.tools.renderReference
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 进程内任务表容量上限：超出淘汰最早完成的任务，防止无界增长 */
private const val MAX_TASKS = 50

/**
 * 引导消息标记：注入子代理的引导 USER 消息用 speakerName 标记，
 * 详情页据此把引导渲染成"用户引导"气泡（区别于任务描述/父摘要等普通 USER 消息）。
 */
const val SUBAGENT_GUIDANCE_MARKER = "subagent_guidance"

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
    private val usageDao: SubAgentUsageDAO,
    private val taskDao: SubAgentTaskDAO,
    private val todoStorage: TodoStorage,
) {
    private val _tasks = MutableStateFlow<Map<String, SubAgentTask>>(emptyMap())
    val tasksFlow: StateFlow<Map<String, SubAgentTask>> = _tasks.asStateFlow()

    /**
     * 子代理完成事件流：终态（SUCCEEDED/TIMEOUT/FAILED）写入时 emit，供母代理异步唤醒。
     * CANCELLED 不 emit（停止生成/用户取消后不唤醒母代理续答）。
     * 消费去重由订阅方（ChatService）维护 taskId 集合，Runner 只负责广播终态。
     */
    private val _taskCompleted = MutableSharedFlow<SubAgentTask>(extraBufferCapacity = 32)
    val taskCompletedFlow: SharedFlow<SubAgentTask> = _taskCompleted.asSharedFlow()

    init {
        // 进程启动：从 Room 恢复历史任务，让聊天里已落库的 spawn/await 工具调用
        // 在重启后仍能跳转到详情页查看历史。历史任务都是终态，不参与并发计数。
        appScope.launch { restoreHistory() }
    }

    /** 任务 Job 登记表：taskId -> Job，供 [cancel] 真正取消协程（v2 异步用） */
    private val taskJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 并发门控：当前正在运行的子代理数量（QUEUED 未占坑，RUNNING 才占坑） */
    private val _runningCount = MutableStateFlow(0)
    val runningCount: StateFlow<Int> = _runningCount.asStateFlow()

    /** 引导消息通道：taskId -> Channel。详情页 submitGuidance 写入，runInternal 每步生成前取出注入 */
    private val guidanceChannels = java.util.concurrent.ConcurrentHashMap<String, Channel<String>>()

    /** UI 实时订阅单个任务的进度（taskId == 母代理 Tool.toolCallId） */
    fun observeTask(taskId: String): kotlinx.coroutines.flow.Flow<SubAgentTask?> =
        tasksFlow.map { it[taskId] }

    /** 同步读取单个任务的当前状态 */
    fun getTask(taskId: String): SubAgentTask? = _tasks.value[taskId]

    /**
     * 阻塞等待任务到达终态（强制指令 /search 等同步等待用）。
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
    ): Model? {
        // request.modelId 是 string（模型可能传 "default"/"auto"/具体 id）。宽松解析：
        // 能按 Uuid 解析则用（兼容已存库的 Uuid 形式）；解析失败当未指定（回落默认模型）。
        val requestedUuid = request.modelId?.let { id ->
            runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull()
        }
        val candidates = listOfNotNull(
            requestedUuid,
            def.defaultModelId,
            settings.subAgentModelId,
            settings.chatModelId,
        )
        // 逐个尝试解析，优先命中第一个有效且支持工具调用的；全失效才返回 null。
        // B1: 子代理依赖工具完成多步执行，模型不支持 TOOL 能力时工具永不触发、白跑成纯文本。
        candidates.forEach { id ->
            val m = settings.findModelById(id)
            if (m != null && m.abilities.contains(ModelAbility.TOOL)) return m
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

        // 注册任务（QUEUED）。runAsync 同步段已前置注册；此处兜底（直接调 runSynchronously 时）
        val initial = registerTask(request, parentConversationId, taskId)
            ?: return _tasks.value[taskId] ?: SubAgentTask(
                taskId = taskId,
                agentId = request.agentId,
                parentConversationId = parentConversationId,
                request = request.task,
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = "Unknown subagent: ${request.agentId}",
            )

        // 并发门控：RUNNING 前申请并发位（超上限则等待有空位）。母代理自发 spawn 时由
        // spawn_subagent 工具先查 [isConcurrencyAvailable] 快速失败；这里仍做最终门控兜底。
        // acquire 在 try 内：若等待并发位时任务被取消（CancellationException），finally 释放
        // 计数槽位，避免并发计数泄漏导致后续任务永远等不到空位。
        try {
            acquireConcurrencySlot(settings)
            // A5: 排队期间被取消（cancel() 已把状态置 CANCELLED）→ 不再启动执行，避免"复燃"。
            // 从 _tasks 读最新态：cancel 写的是 CANCELLED，这里直接返回该终态。
            val latest = _tasks.value[taskId]
            if (latest?.status == SubAgentStatus.CANCELLED) {
                return latest
            }
            // def 可能为 null（unknown agentId）——此时 registerTask 已把任务置 FAILED，
            // initial 里 error 已填。这里用 initial 的状态兜底，避免 def!! NPE。
            if (def == null || initial.status == SubAgentStatus.FAILED) {
                return initial
            }
            return runWithTimeout(def, settings, request, initial, taskId)
        } finally {
            releaseConcurrencySlot()
        }
    }

    /**
     * 注册任务（QUEUED）到 _tasks。幂等：taskId 已存在（runAsync 同步段已注册）则复用，
     * 避免重复注册重置 createdAt。def 不存在时置 FAILED 并返回 null（调用方按失败处理）。
     * 非 suspend（runAsync 同步段调用，保证 taskId 立即可见，修复 A2 竞态）——
     * def==null 的罕见失败态只进内存 + 广播事件，不落 Room（从未真正执行，落库价值低）。
     */
    private fun registerTask(
        request: SubAgentRequest,
        parentConversationId: Uuid,
        taskId: String,
    ): SubAgentTask? {
        val def = SubAgentCatalog.byId(request.agentId)
        // 幂等：已注册过（runAsync 前置注册）则直接返回现有任务
        _tasks.value[taskId]?.let { existing ->
            return if (def == null && existing.status == SubAgentStatus.QUEUED) {
                // def 不存在但之前注册成了 QUEUED（前置注册时未查 def）→ 补置 FAILED
                val failed = existing.copy(
                    status = SubAgentStatus.FAILED,
                    finishedAt = Clock.System.now(),
                    error = "Unknown subagent: ${request.agentId}",
                )
                _tasks.update { it + (taskId to failed) }
                emitCompleted(failed)
                null
            } else {
                existing
            }
        }
        // request.modelId 是 string（宽松接受 "default" 等非 Uuid），存入任务时解析成 Uuid 再存
        val initial = SubAgentTask(
            taskId = taskId,
            agentId = request.agentId,
            parentConversationId = parentConversationId,
            request = request.task,
            modelId = request.modelId?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() },
        )
        _tasks.update { (it + (taskId to initial)).trimToMax() }
        if (def == null) {
            val failed = initial.copy(
                status = SubAgentStatus.FAILED,
                finishedAt = Clock.System.now(),
                error = "Unknown subagent: ${request.agentId}",
            )
            _tasks.update { it + (taskId to failed) }
            emitCompleted(failed)
            return null
        }
        return initial
    }

    /**
     * 带超时与自动重试的执行回合。超时/异常/取消都从 _tasks 读最新态，保留已生成的流式内容。
     * 全局超时 [Settings.subAgentTimeoutSeconds] 优先；null 时跟随各子代理定义。
     *
     * 自动重试：超时/瞬态失败（配置类错误除外）时，以「同一任务的第二轮」重跑——
     * 保留上一轮已执行的消息序列作为 seed 继续，而不是从零重新派发（省 token、详情页时间线连续）。
     * 重试次数 [Settings.subAgentMaxRetries]（默认 1，clamp 0..3）；取消贯穿各轮不受重试影响。
     * usage 跨轮累计在同一 task.usage；持久化/广播只在最终轮结束后统一执行一次。
     */
    private suspend fun runWithTimeout(
        def: SubAgentDefinition,
        settings: Settings,
        request: SubAgentRequest,
        initial: SubAgentTask,
        taskId: String,
    ): SubAgentTask {
        val timeoutSeconds = settings.subAgentTimeoutSeconds?.takeIf { it > 0 } ?: def.timeoutSeconds
        val maxRounds = (settings.subAgentMaxRetries.coerceIn(0, 3)) + 1

        var task = initial.copy(status = SubAgentStatus.RUNNING, startedAt = Clock.System.now())
            .addStep("子代理 ${def.name} 开始执行")
        _tasks.update { it + (taskId to task) }

        // 任务开始前确保引导通道存在，供详情页在运行中随时 submitGuidance
        guidanceChannels.putIfAbsent(taskId, Channel(Channel.UNLIMITED))

        // 重试的上下文种子：上一轮完整消息序列（含任务 + 已执行工具 + 流式文本），供下一轮延续
        var seedMessages: List<UIMessage>? = null
        var round = 0

        try {
            while (true) {
                val attempt = try {
                    withTimeout(timeoutSeconds * 1000) {
                        runInternal(def, settings, request, task, seedMessages)
                    }
                } catch (e: TimeoutCancellationException) {
                    // 超时保留已输出的部分结果：从 _tasks 读最新态（onMessagesUpdate 回调更新的是
                    // _tasks 里的实例，局部 task 可能滞后），提取摘要，母代理仍能读到已生成内容。
                    val latest = _tasks.value[taskId] ?: task
                    latest.copy(
                        status = SubAgentStatus.TIMEOUT,
                        finishedAt = Clock.System.now(),
                        error = "任务超时（${timeoutSeconds}s）",
                        resultSummary = latest.resultSummary ?: partialResultSummary(latest),
                    )
                } catch (e: CancellationException) {
                    val latest = _tasks.value[taskId] ?: task
                    task = latest.copy(
                        status = SubAgentStatus.CANCELLED,
                        finishedAt = Clock.System.now(),
                        error = "任务已取消",
                    )
                    _tasks.update { it + (taskId to task) }
                    taskJobs.remove(taskId)?.cancel()
                    throw e
                } catch (e: TokenBudgetExceeded) {
                    // token 预算耗尽：保留部分结果，置 TOKEN_LIMIT（不可重试，重试只会继续烧 token）
                    val latest = _tasks.value[taskId] ?: task
                    latest.copy(
                        status = SubAgentStatus.TOKEN_LIMIT,
                        finishedAt = Clock.System.now(),
                        error = "token 预算耗尽（${latest.usage?.totalTokens ?: "?"}）",
                        resultSummary = latest.resultSummary ?: partialResultSummary(latest),
                    )
                } catch (e: Exception) {
                    // 执行异常同样保留已输出内容，避免失败时母代理读到空壳
                    val latest = _tasks.value[taskId] ?: task
                    latest.copy(
                        status = SubAgentStatus.FAILED,
                        finishedAt = Clock.System.now(),
                        error = sanitizeError(e.message ?: e.javaClass.simpleName),
                        resultSummary = latest.resultSummary ?: partialResultSummary(latest),
                    )
                }
                task = attempt
                _tasks.update { it + (taskId to task) }

                // 是否值得重试：超时或可重试的失败（配置类错误不可重试），且未到重试上限
                val retryable = when (task.status) {
                    SubAgentStatus.TIMEOUT -> true
                    SubAgentStatus.FAILED -> isRetryableFailure(task.error)
                    else -> false
                }
                if (!retryable || round + 1 >= maxRounds) break
                // 重试期间用户取消（cancel() 已置 CANCELLED）→ 不再启动下一轮，避免"复燃"
                if (_tasks.value[taskId]?.status == SubAgentStatus.CANCELLED) break

                round++
                seedMessages = task.messages.takeIf { it.isNotEmpty() }
                // 置回 RUNNING 继续下一轮：保留已执行内容作为 seed，详情页显示连续时间线。
                // 保留首次 startedAt（不重置），让 durationMillis 统计"首次启动→最终结束"的总耗时，
                // 与跨轮累计的 token 用量口径一致；只清掉上一轮的 finishedAt，避免时长统计残留。
                // 直接用 _tasks.update 绕过 updateTaskSafely 的终态保护（这里是从终态主动转回运行态）
                task = task.copy(
                    status = SubAgentStatus.RUNNING,
                    finishedAt = null,
                    retryCount = round,
                    error = null,
                ).addStep("第 ${round + 1} 轮重试：上一轮 ${attempt.status.name.lowercase()}，保留已执行内容继续")
                _tasks.update { it + (taskId to task) }
            }
        } finally {
            // 无论正常结束还是取消（rethrow）都执行：持久化 usage + 任务快照 + 清理引导通道
            runCatching { persistUsage(task) }
            persistTask(task)
            guidanceChannels.remove(taskId)
        }
        // 重试循环退出后：若 cancel 已在重试间隙把任务置 CANCELLED（如重试判断时发现已取消而 break），
        // 以 CANCELLED 为准，避免用本地的 TIMEOUT/FAILED 覆盖"用户取消"这一终态。
        // emitCompleted 对 CANCELLED 直接过滤，不会双发（手动取消由 cancel(notifyParent) 单独广播）。
        val finalTask = _tasks.value[taskId]?.takeIf { it.status == SubAgentStatus.CANCELLED } ?: task
        _tasks.update { it + (taskId to finalTask) }
        taskJobs.remove(taskId)
        emitCompleted(finalTask)
        return finalTask
    }

    /** 是否值得自动重试的失败。配置类错误（模型/provider 未配置、未知子代理）重试必重蹈覆辙，不重试 */
    private fun isRetryableFailure(error: String?): Boolean {
        if (error.isNullOrBlank()) return true
        return !error.contains("模型未配置") &&
            !error.contains("provider 未配置") &&
            !error.contains("Unknown subagent")
    }

    /** 提取任务的部分结果摘要（超时/token 超限/失败时保留给母代理，避免读到空壳） */
    private fun partialResultSummary(task: SubAgentTask): String {
        return subAgentResultSummary(task.messages).first
            .takeIf { it.isNotBlank() }
            ?: task.streamText.takeIf { it.isNotBlank() }
            ?: task.steps.joinToString("\n") { it.message }.takeLast(500)
    }

    /** 终态完成后广播事件（供母代理异步唤醒）。
     *  仅广播"有实际产出"的终态：SUCCEEDED 必然有结果；TIMEOUT/FAILED 仅在存在
     *  部分结果（摘要/流式文本）时广播，避免"空失败"唤醒母代理续答一句废话。
     *  CANCELLED 一律不广播——批量停止/删会话后不唤醒母代理。
     *  注意：手动取消单个任务（详情页/面板取消按钮）不走这里，由 [cancel] 的
     *  notifyParent 分支直接广播 CANCELLED 任务，唤醒母代理感知取消。 */
    private fun emitCompleted(task: SubAgentTask) {
        if (task.status == SubAgentStatus.CANCELLED) return
        val hasPartial = !task.resultSummary.isNullOrBlank() || task.streamText.isNotBlank()
        if (task.status != SubAgentStatus.SUCCEEDED && !hasPartial) return
        Log.i("SubAgentRunner", "emitCompleted: task=${task.taskId} status=${task.status} summaryLen=${task.resultSummary?.length ?: 0}")
        _taskCompleted.tryEmit(task)
    }

    /** 错误信息脱敏（B2）：裁剪长度 + 屏蔽疑似密钥，避免 provider 异常泄露 API key/请求体给母代理。 */
    private fun sanitizeError(message: String): String {
        val trimmed = message.take(500)
        // 屏蔽常见密钥形态：sk-xxx / key=xxx / api_key:xxx / Bearer xxx
        return trimmed
            .replace(Regex("(?i)(sk-[a-zA-Z0-9_-]{6,})", RegexOption.IGNORE_CASE), "sk-***")
            .replace(Regex("(?i)(api[_\\-]?key['\"]?\\s*[:=]\\s*['\"]?[a-zA-Z0-9_-]{8,})"), "api_key=***")
            .replace(Regex("(?i)(bearer\\s+[a-zA-Z0-9._-]{8,})"), "bearer ***")
    }

    /**
     * 终态安全更新任务：任务仍非终态（或已不存在）时才把新值写入 _tasks。
     * 统一的终态保护——迟到的流式/工具回调不得把已终态（超时/失败/取消）的任务改回 RUNNING。
     */
    private fun updateTaskSafely(taskId: String, newValue: () -> SubAgentTask) {
        _tasks.update { map ->
            val current = map[taskId]
            if (current == null || !current.status.isTerminal) {
                map + (taskId to newValue())
            } else map
        }
    }

    /** 并发申请：等待 _runningCount < max 后 +1。max 取自设置，clamp 1..64 */
    private suspend fun acquireConcurrencySlot(settings: Settings) {
        val max = settings.subAgentMaxConcurrent.coerceIn(1, 64)
        _runningCount.first { it < max }
        _runningCount.update { it + 1 }
    }

    private fun releaseConcurrencySlot() {
        _runningCount.update { (it - 1).coerceAtLeast(0) }
    }

    /** 并发是否可再派发（spawn_subagent 工具快速失败检查）。读最新设置，max clamp 1..64 */
    fun isConcurrencyAvailable(): Boolean {
        val max = settingsStore.settingsFlow.value.subAgentMaxConcurrent.coerceIn(1, 64)
        return _runningCount.value < max
    }

    /** 当前并发上限（spawn_subagent 错误 JSON 里回显给母代理） */
    fun concurrencyLimit(): Int = settingsStore.settingsFlow.value.subAgentMaxConcurrent.coerceIn(1, 64)

    /** 任务终态后把 usage 写入 Room（无 usage 则跳过，避免空行撑大表） */
    private suspend fun persistUsage(task: SubAgentTask) {
        val usage = task.usage ?: return
        if (usage.promptTokens <= 0 && usage.completionTokens <= 0 && usage.cachedTokens <= 0) return
        runCatching {
            usageDao.upsert(
                SubAgentUsageEntity(
                    taskId = task.taskId,
                    conversationId = task.parentConversationId.toString(),
                    agentId = task.agentId,
                    modelId = task.modelId?.toString(),
                    status = task.status.name,
                    promptTokens = usage.promptTokens.toLong(),
                    completionTokens = usage.completionTokens.toLong(),
                    cachedTokens = usage.cachedTokens.toLong(),
                    cacheWriteTokens = usage.cacheWriteTokens.toLong(),
                    createdAt = task.createdAt.toEpochMilliseconds(),
                )
            )
        }.onFailure { Log.w("SubAgentRunner", "Failed to persist usage for task", it) }
    }

    // ---- 任务历史持久化（对齐聊天消息落库：状态变化写库，重启后从库恢复） ----

    /** 任务快照写库。状态变化时调用；写失败不影响内存态运行。 */
    private suspend fun persistTask(task: SubAgentTask) {
        runCatching {
            taskDao.upsert(
                SubAgentTaskEntity(
                    taskId = task.taskId,
                    parentConv = task.parentConversationId.toString(),
                    agentId = task.agentId,
                    status = task.status.name,
                    createdAt = task.createdAt.toEpochMilliseconds(),
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    taskJson = JsonInstant.encodeToString(task),
                )
            )
        }.onFailure { Log.w("SubAgentRunner", "Failed to persist task snapshot", it) }
    }

    /**
     * 进程启动时从 Room 恢复历史任务，合并进 _tasks。
     * 历史任务只做展示/详情页跳转用，不参与并发计数。
     * 进程死亡前可能来不及写终态，恢复到的非终态（QUEUED/RUNNING）是"僵尸任务"——
     * 协程早没了，永远到不了终态。统一置 CANCELLED + "进程中断"，避免 UI 显示"执行中"卡死。
     * 若同一 taskId 已存在内存态（如恢复后又有新派发），以内存态为准。
     */
    suspend fun restoreHistory(limit: Int = 50) {
        runCatching {
            val recent = taskDao.getRecent(limit)
            if (recent.isEmpty()) return
            val restored = recent.mapNotNull { entity ->
                runCatching { JsonInstant.decodeFromString<SubAgentTask>(entity.taskJson) }.getOrNull()
            }.map { task ->
                if (task.status.isTerminal) task
                else task.copy(
                    status = SubAgentStatus.CANCELLED,
                    finishedAt = Clock.System.now(),
                    error = "进程中断，任务已终止",
                )
            }.associateBy { it.taskId }
            _tasks.update { current ->
                // 只补进不冲突的（内存里没有的 taskId）；冲突保留内存态
                (restored.filterKeys { it !in current } + current)
            }
        }.onFailure { Log.w("SubAgentRunner", "Failed to restore task history", it) }
    }

    /** 从历史库读取单个任务（内存态没有时，详情页/工具跳转用） */
    suspend fun getTaskFromHistory(taskId: String): SubAgentTask? {
        _tasks.value[taskId]?.let { return it }
        return runCatching {
            val entity = taskDao.getById(taskId) ?: return null
            JsonInstant.decodeFromString<SubAgentTask>(entity.taskJson)
        }.getOrNull()
    }

    /**
     * 重新执行一个已终态任务（详情页"重新执行"续跑用，含僵尸任务）。
     * 以新 taskId 派发同名 agent + 原 request，并把上次的部分结果（摘要/流式文本/末步日志）
     * 作为 [SubAgentRequest.priorContext] 注入，让新任务带着上次进展继续，而不是从零重跑。
     * 运行中（QUEUED/RUNNING）返回 null 防双跑；未知任务返回 null。
     * @return 新任务的 taskId
     */
    suspend fun rerun(taskId: String): String? {
        val task = getTaskFromHistory(taskId) ?: return null
        if (task.status == SubAgentStatus.QUEUED || task.status == SubAgentStatus.RUNNING) return null
        val prior = (task.resultSummary ?: task.streamText)
            ?.takeIf { it.isNotBlank() }
            ?: task.steps.lastOrNull()?.message
        val request = SubAgentRequest(
            agentId = task.agentId,
            task = task.request,
            modelId = task.modelId?.toString(),
            priorContext = prior?.take(1500),
        )
        val newId = Uuid.random().toString()
        runAsync(request, task.parentConversationId, taskId = newId)
        return newId
    }

    /** 删除某会话的全部任务历史（删会话时级联调用） */
    suspend fun deleteTasksOfConversation(conversationId: Uuid) {
        runCatching { taskDao.deleteByConversation(conversationId.toString()) }
            .onFailure { Log.w("SubAgentRunner", "Failed to delete task history", it) }
    }

    private suspend fun runInternal(
        def: SubAgentDefinition,
        settings: Settings,
        request: SubAgentRequest,
        initial: SubAgentTask,
        seedMessages: List<UIMessage>? = null,
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

        // 用实际解析出的模型覆盖 task.modelId（request 可能未指定，而 resolveModel 会落到
        // defaultModelId / subAgentModelId / chatModelId），确保用量落库时记录真实计费模型。
        var task = initial.copy(modelId = model.id)

        fun addStep(message: String): SubAgentTask {
            task = task.addStep(message)
            _tasks.update { it + (task.taskId to task) }
            return task
        }

        addStep("解析子代理模型 ${model.displayName}")

        // 装配工具（按能力）
        val tools = withContext(Dispatchers.IO) {
            toolAssembler.assemble(def, settings, parentConversation)
        }.canonicalToolOrder()
        addStep("装配工具 ${tools.size} 个")

        // 构造消息：system（子代理职责 + 工具提示词）+ 父代理上下文摘要 + 用户任务
        val system = buildString {
            append(def.systemPrompt)
            // 并行提示：声明允许并行的子代理被母代理并发派发时，提示自己可能不是唯一在跑的 worker。
            // 不提供任何嵌套派发/协作工具——协作由母代理统一协调（orchestrator-workers）。
            if (def.allowParallel) {
                appendLine()
                appendLine(
                    """
                    ## Parallel Sibling Sub-Agents
                    The parent agent may have spawned other sub-agents in parallel for the same task.
                    - Do NOT call `spawn_subagent` — you have no such tools.
                    - If you need information another sub-agent may be producing, note the dependency
                      gap in your result and finish your own portion; the parent agent coordinates.
                    - Keep your result self-contained and directly usable on its own.
                    """.trimIndent()
                )
            }
            // 统一输出要求：让最终结果对母代理可整合（母代理只收到 resultSummary，看不到中间过程）。
            // 明确"最终输出 = 返回给母代理的 summary"。
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
            // 只读注入主代理的 todo 计划，让子代理输出对齐任务计划。
            // 仅引用、不提供 todo 工具——计划所有权在父代理，避免 worker 间共享可变状态。
            if (settings.enableTodoList) {
                val plan = runCatching {
                    todoStorage.load(parentConversation.id.toString())?.renderReference()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                if (plan != null) {
                    appendLine()
                    append(plan)
                }
            }
        }
        val messages = buildList {
            add(UIMessage.system(system))
            if (seedMessages != null) {
                // 重试/续跑：以上一轮已执行内容作为上下文（排除 system，system 由上面新构造）
                addAll(seedMessages.filter { it.role != MessageRole.SYSTEM })
            } else {
                // 子代理主任务紧接 system（任务上下文最近，且保持 system→task 前缀稳定，利于缓存）
                add(UIMessage.user(request.task))
                // 续跑上下文：上次执行的部分结果（详情页"重新执行"）。放在任务之后、父摘要之前，
                // 不打断 system→task 缓存前缀。无续跑时为 null，走原有结构。
                request.priorContext?.takeIf { it.isNotBlank() }?.let {
                    add(UIMessage.user("【上次执行的部分结果，作为参考】\n$it"))
                }
                // 父代理最近上下文摘要（控制 token；L2: 坏 DB 记录不拖垮子代理）。
                // 放在任务之后作补充背景，避免打断 system→task 前缀
                val parentSummary = runCatching {
                    parentContextSummary(parentConversation)
                }.getOrNull() ?: ""
                if (parentSummary.isNotBlank()) {
                    add(UIMessage.user("母代理上下文摘要（背景参考）：\n$parentSummary"))
                }
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
                    updateTaskSafely(task.taskId) { task }
                },
                onStreamUpdate = { text ->
                    // 实时流式输出：仅在有文本时更新；且任务已是终态（如超时被置 TIMEOUT）时不覆盖，
                    // 避免迟到的流式回调把终态改回 RUNNING 卡死（MED-4）
                    if (text.isNotBlank()) {
                        task = task.copy(streamText = text)
                        updateTaskSafely(task.taskId) { task }
                    }
                },
                onReasoningUpdate = { delta ->
                    // 实时思考内容：追加累积（与 streamText 不同的语义：文本是全量覆盖、思考是追加）。
                    // 终态保护与 streamText 一致：迟到的思考回调不覆盖终态。
                    if (delta.isNotBlank()) {
                        task = task.copy(reasoning = task.reasoning + delta)
                        updateTaskSafely(task.taskId) { task }
                    }
                },
                onMessagesUpdate = { msg ->
                    // 实时结构化消息序列（供 UI 重建思维链时间线）。终态保护与 streamText 一致。
                    task = task.copy(messages = msg)
                    updateTaskSafely(task.taskId) { task }
                },
                onUsageUpdate = { stepUsage ->
                    // 跨步骤累加 token 用量，写回 _tasks 供详情页展示；终态持久化在 runWithTimeout 统一做
                    task = task.copy(usage = task.usage.sum(stepUsage))
                    updateTaskSafely(task.taskId) { task }
                    // token 预算（opt-in）：累计超限则中断本轮，由 runWithTimeout 置 TOKEN_LIMIT。
                    // 抛在 onUsageUpdate 里会在流式 collect 处向上传播，捕获后保留已生成部分。
                    val budget = settings.subAgentMaxTokens?.takeIf { it > 0 }
                    if (budget != null && (task.usage?.totalTokens ?: 0) > budget) {
                        throw TokenBudgetExceeded()
                    }
                },
                onGuidance = { msgs -> drainGuidance(initial.taskId, msgs) },
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
            .takeLast(8)
            .joinToString(separator = "\n---\n") { msg ->
                val role = if (msg.role == MessageRole.USER) "用户" else "助手"
                "$role: ${msg.toText()}"
            }
            .take(1600)
    }

    // ---- 异步派发 ----

    /** 后台执行：立即返回 taskId，任务在 AppScope detached 运行。taskId 与内部注册一致。
     *  同步段先注册（QUEUED）再 launch：调用方（如强制指令 awaitTask）立刻能看到任务，
     *  避免"协程未启动前 awaitTask 读到 null 误判 not_found"的竞态（A2）。 */
    fun runAsync(request: SubAgentRequest, parentConversationId: Uuid, taskId: String = Uuid.random().toString()): String {
        // 同步段注册任务（QUEUED 写入 _tasks），def 不存在时直接置 FAILED（registerTask 内部处理）
        registerTask(request, parentConversationId, taskId)
        val job = appScope.launch(start = CoroutineStart.DEFAULT) {
            runSynchronously(request, parentConversationId, taskId = taskId)
        }
        taskJobs[taskId] = job
        job.invokeOnCompletion { taskJobs.remove(taskId) }
        return taskId
    }

    /** 取消任务：取消关联协程并置状态为 CANCELLED。
     *  仅对异步任务（[runAsync]，登记在 taskJobs）有效并返回 true；未登记的返回 false。
     *  调用方据此决定是否显示取消按钮。
     *
     *  @param notifyParent 手动取消单个任务（详情页/面板取消按钮）时传 true：广播完成事件，
     *   唤醒母代理续答，让它感知"该子代理已被取消"并在后续输出中体现。
     *   批量停止生成/删会话走 [cancelByConversation]（不传），保持"停止生成不唤醒母代理"语义。 */
    fun cancel(taskId: String, notifyParent: Boolean = false): Boolean {
        val job = taskJobs[taskId] ?: return false
        job.cancel()
        // 记录取消前是否处于运行态：仅对"确实从 QUEUED/RUNNING 取消"的任务广播，
        // 避免对已终态（已完成/超时等）任务重复 notifyParent 唤醒母代理。
        val wasRunning = _tasks.value[taskId]?.status?.let {
            it == SubAgentStatus.QUEUED || it == SubAgentStatus.RUNNING
        } == true
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            if (task.status == SubAgentStatus.QUEUED || task.status == SubAgentStatus.RUNNING) {
                map + (taskId to task.copy(status = SubAgentStatus.CANCELLED, error = "用户取消"))
            } else map
        }
        // 手动取消：广播 CANCELLED 任务（绕过 emitCompleted 的 CANCELLED 过滤——取消任务
        // 可能没有任何部分结果，但母代理必须知情才能回应该取消）。批量路径不传 notifyParent。
        if (notifyParent && wasRunning) {
            _tasks.value[taskId]?.let { _taskCompleted.tryEmit(it) }
        }
        return true
    }

    /**
     * 取消某会话的全部运行中子代理（用户停止生成 / 删除会话时级联调用）。
     * CANCELLED 不触发完成事件（emitCompleted 过滤；cancel 默认 notifyParent=false），
     * 故停止后不会异步唤醒母代理。
     */
    fun cancelByConversation(conversationId: Uuid) {
        _tasks.value.values
            .filter { it.parentConversationId == conversationId && !it.status.isTerminal }
            .forEach { cancel(it.taskId) }
    }

    // ---- 引导消息 ----

    /** 详情页发送引导消息：写入任务通道。任务不存在或已终态返回 false */
    fun submitGuidance(taskId: String, text: String): Boolean {
        if (text.isBlank()) return false
        val ch = guidanceChannels[taskId] ?: return false
        val current = _tasks.value[taskId] ?: return false
        if (current.status.isTerminal) return false
        return ch.trySend(text).isSuccess
    }

    /** 把通道内积压的引导消息以用户消息形式追加到子代理消息列表（每步生成前调用） */
    private suspend fun drainGuidance(taskId: String, messages: List<UIMessage>): List<UIMessage> {
        val ch = guidanceChannels[taskId] ?: return messages
        var result = messages
        while (true) {
            val text = ch.tryReceive().getOrNull() ?: break
            // speakerName 标记：详情页据此把引导渲染成"用户引导"气泡（区别于任务/父摘要等普通 USER 消息）。
            // "【用户引导】" 前缀保留给模型，帮助它理解这是一条引导指令；UI 渲染时去掉。
            result = result + UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("【用户引导】$text")),
                speakerName = SUBAGENT_GUIDANCE_MARKER,
            )
        }
        return result
    }
}

/** 任务表容量裁剪：保留最近 MAX_TASKS 条。运行中（非终态）任务优先保留，只淘汰最早完成的终态任务。
 *  避免新注册时把正在运行的任务从 _tasks 挤掉（协程仍在跑、UI 失联）。 */
private fun Map<String, SubAgentTask>.trimToMax(): Map<String, SubAgentTask> {
    if (size <= MAX_TASKS) return this
    val running = filterValues { !it.status.isTerminal }
    val completed = entries
        .filter { it.value.status.isTerminal }
        .sortedByDescending { it.value.createdAt }
    // 运行中任务全部保留；若仍超限，再按时间淘汰终态任务
    val keepCompleted = completed.take((MAX_TASKS - running.size).coerceAtLeast(0))
    return running + keepCompleted.associate { it.key to it.value }
}
