package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.AsyncTaskState
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L
// 工具状态心跳间隔：模型在长工具执行/后台任务等待期间不产生流式更新，
// 通知只有靠定时刷新才能推进"已运行时长"，并让用户区分"在跑"与"卡死"
private const val LIVE_UPDATE_NOTIFICATION_HEARTBEAT_MS = 1000L
private const val TOOL_STATUS_KEY_PREFIX = "tool:"

private val notificationJson = Json { ignoreUnknownKeys = true }

internal enum class LiveUpdateKind {
    TOOL,
    THINKING,
    WRITING,
    DEFAULT,
}

internal data class LiveUpdateStatusData(
    val kind: LiveUpdateKind,
    val toolName: String = "",
    val toolCallId: String = "",
    val contentText: String = "",
    val toolStartedAtMs: Long? = null,
    /** 墙钟开始时间（毫秒），单调时钟不可用时兜底（如旧数据只有 Instant 时间戳） */
    val toolStartedEpochMs: Long? = null,
) {
    /** 同一状态流式刷新时继续节流；状态切换（如文本 -> 工具）必须立即刷新。 */
    val statusKey: String
        get() = when (kind) {
            LiveUpdateKind.TOOL -> "tool:$toolCallId"
            LiveUpdateKind.THINKING -> "thinking"
            LiveUpdateKind.WRITING,
            LiveUpdateKind.DEFAULT,
                -> "writing"
        }

    /** 工具已运行时长(ms)：优先单调时钟差（跨休眠稳定），无单调锚点回退墙钟差 */
    val runningElapsedMs: Long?
        get() {
            val monotonic = toolStartedAtMs
            if (monotonic != null) return SystemClock.elapsedRealtime() - monotonic
            val epoch = toolStartedEpochMs
            if (epoch != null) return System.currentTimeMillis() - epoch
            return null
        }
}

internal fun determineLiveUpdateStatus(
    parts: List<UIMessagePart>,
    asyncTaskStateProvider: (String) -> AsyncTaskState? = { null },
): LiveUpdateStatusData {
    val tools = parts.filterIsInstance<UIMessagePart.Tool>()

    // 有尚未结束的工具（运行中/排队/待审批/未开始）时，优先展示工具状态。
    // 不能只看 output 是否为空：并行工具可能先完成一个，让最后一个已完成工具的
    // output 非空而另一个仍在执行。
    tools.lastOrNull { !it.isFinished }?.let { tool ->
        return toolNotification(tool)
    }

    // workspace_shell_async 把任务 ID 返回后工具本身即完成，但后台命令可能仍在跑
    tools.lastOrNull { tool ->
        tool.toolName == "workspace_shell_async" &&
            asyncTaskId(tool)?.let(asyncTaskStateProvider) == AsyncTaskState.RUNNING
    }?.let { tool ->
        return toolNotification(tool)
    }

    // Provider 端执行中的工具（如服务端搜索）
    parts.filterIsInstance<UIMessagePart.ServerTool>()
        .lastOrNull { !it.isFinished }
        ?.let { tool ->
            return LiveUpdateStatusData(
                kind = LiveUpdateKind.TOOL,
                toolName = tool.toolName.substringAfterLast("__"),
                toolCallId = tool.toolCallId,
                contentText = tool.input?.toString().orEmpty().take(100),
            )
        }

    // 正在思考（Reasoning 未结束）
    parts.filterIsInstance<UIMessagePart.Reasoning>()
        .lastOrNull { it.finishedAt == null }
        ?.let { reasoning ->
            return LiveUpdateStatusData(
                kind = LiveUpdateKind.THINKING,
                contentText = reasoning.reasoning.takeLast(200),
            )
        }

    // 正在写回复
    parts.filterIsInstance<UIMessagePart.Text>()
        .lastOrNull()
        ?.let { text ->
            return LiveUpdateStatusData(
                kind = LiveUpdateKind.WRITING,
                contentText = text.text.takeLast(200),
            )
        }

    return LiveUpdateStatusData(kind = LiveUpdateKind.DEFAULT)
}

private fun toolNotification(tool: UIMessagePart.Tool): LiveUpdateStatusData =
    LiveUpdateStatusData(
        kind = LiveUpdateKind.TOOL,
        toolName = tool.toolName.substringAfterLast("__"),
        toolCallId = tool.toolCallId,
        contentText = tool.input.take(100),
        toolStartedAtMs = tool.startedAtMs ?: tool.queuedAtMs,
        toolStartedEpochMs = tool.startedAt?.toEpochMilliseconds() ?: tool.queuedAt?.toEpochMilliseconds(),
    )

private fun asyncTaskId(tool: UIMessagePart.Tool): String? {
    val text = tool.output
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
    if (text.isBlank()) return null
    return runCatching {
        notificationJson.parseToJsonElement(text).jsonObject["taskId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

/**
 * 0:42 / 12:05 / 1:02:03 形式的已运行时长：纯数字本地无关，
 * 单位词由引用它的本地化字符串（notification_live_update_tool_elapsed）承担。
 * 不足 1 小时分钟不补零（0:42）；超过 1 小时后分钟补零（1:02:03）便于对齐秒位。
 */
internal fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = if (seconds < 10) "0$seconds" else "$seconds"
    if (hours > 0) {
        val mm = if (minutes < 10) "0$minutes" else "$minutes"
        return "$hours:$mm:$ss"
    }
    return "$minutes:$ss"
}

/**
 * 单个会话的 Live Update 状态。事件流与心跳协程分属不同线程，
 * 字段变更一律走 ConcurrentHashMap.compute*（按键原子）。
 */
internal class ConversationLiveState(
    /** 最近一次 ChatGenerationUpdate 携带的消息 parts 快照：切后台/异步任务终态时据此重算 */
    val snapshotParts: List<UIMessagePart>,
    val snapshotSender: String,
    /** 最近事件时间（单调时钟），用于判定当前占用通知槽位的会话 */
    val lastEventAt: Long,
    /** 最近一次实际发送的状态 key；null 表示尚未发过（如整个生成都发生在前台） */
    @Volatile
    var lastPostedKey: String? = null,
    /** 最近一次实际发送的时间（节流判定用），0 = 尚未发送 */
    @Volatile
    var lastPostedAt: Long = 0L,
    /** 工具状态时长心跳；非空表示正在跑 */
    @Volatile
    var heartbeat: Job? = null,
)

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 *
 * 通知内容依赖模型流式更新驱动，而长工具执行/后台任务等待期间模型不产流——
 * 因此这里对工具状态额外做两件事：
 * 1. 1s 心跳刷新：推进"已运行时长"，兜底定时刷新避免画面静止；
 * 2. 消费 AppEvent.AsyncTaskTerminal：workspace_shell_async 终态一到就重算并纠正状态，
 *    不等模型下一次流式更新。
 */
class ChatNotificationManager(
    private val context: Application,
    private val appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val isForeground = MutableStateFlow(false)
    private val liveUpdateStates = ConcurrentHashMap<Uuid, ConversationLiveState>()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        // 前台：UI 本身就是状态面，停掉时长心跳，避免无谓的 binder 通知刷新
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            stopHeartbeats()
                        }

                        // 后台：把当前真实状态立即推一次（覆盖 FGS 启动时的静态文案，
                        // 它可能停留在"正在生成回复"），工具状态顺带启动时长心跳
                        Lifecycle.Event.ON_STOP -> {
                            isForeground.value = false
                            refreshAfterBackgrounded()
                        }

                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    is AppEvent.AsyncTaskTerminal -> handleAsyncTaskTerminal(event)
                    else -> {}
                }
            }
        }
    }

    // ---- 事件处理 ----

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        val status = determineLiveUpdateStatus(event.lastMessage.parts) { taskId ->
            workspaceRepository.asyncTaskStatus(taskId)?.state
        }
        val now = SystemClock.elapsedRealtime()
        // 快照总是更新，不受前台短路影响：最后一条事件往往发生在"前台运行 → 切后台"的
        // 瞬间，若前台时没缓存 parts，切后台后就没有可重算的底稿（见 refreshAfterBackgrounded）
        liveUpdateStates.compute(event.conversationId) { _, prev ->
            ConversationLiveState(
                snapshotParts = event.lastMessage.parts,
                snapshotSender = event.senderName,
                lastEventAt = now,
                lastPostedKey = prev?.lastPostedKey,
                lastPostedAt = prev?.lastPostedAt ?: 0L,
                heartbeat = prev?.heartbeat,
            )
        }
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val previous = liveUpdateStates[event.conversationId] ?: return
        if (previous.lastPostedAt != 0L &&
            now - previous.lastPostedAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS &&
            previous.lastPostedKey == status.statusKey
        ) {
            return
        }

        postLiveUpdate(event.conversationId, status)
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        cancelLiveUpdateNotification(event.conversationId)

        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    /**
     * workspace_shell_async 任务终态事件：任务已结束但模型可能尚未产生任何新流式内容，
     * 通知却仍停留在"正在运行工具"。这里用最近快照立即重算并纠正，无需等模型下一次更新。
     */
    private fun handleAsyncTaskTerminal(event: AppEvent.AsyncTaskTerminal) {
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val conversationId = activeConversationId() ?: return
        val state = liveUpdateStates[conversationId] ?: return
        // 当前展示的不是工具状态 → 该通知与后台任务无关，跳过
        if (state.lastPostedKey?.startsWith(TOOL_STATUS_KEY_PREFIX) != true) return

        val derived = determineLiveUpdateStatus(state.snapshotParts) { taskId ->
            workspaceRepository.asyncTaskStatus(taskId)?.state
        }
        // 任务终态后若重算结果不变（说明展示的工具不是该任务，或已被心跳纠正），无需动作
        if (derived.statusKey == state.lastPostedKey) return

        postLiveUpdate(conversationId, derived)
    }

    /** 切后台：把最近快照的当前状态推一次并管理心跳 */
    private fun refreshAfterBackgrounded() {
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val conversationId = activeConversationId() ?: return
        val state = liveUpdateStates[conversationId] ?: return
        val derived = determineLiveUpdateStatus(state.snapshotParts) { taskId ->
            workspaceRepository.asyncTaskStatus(taskId)?.state
        }
        // 无条件补推：前台期间没有任何通知被发送（isForeground 短路），通知栏可能仍是
        // FGS 的静态文案或上一轮后台的旧内容，需要以最新快照刷新；工具状态顺带启动心跳
        postLiveUpdate(conversationId, derived)
    }

    // ---- 发送与心跳 ----

    private fun postLiveUpdate(conversationId: Uuid, status: LiveUpdateStatusData) {
        val state = liveUpdateStates[conversationId] ?: return
        sendLiveUpdateNotification(conversationId, state.snapshotSender, status)
        markPosted(conversationId, status.statusKey)
        manageHeartbeat(conversationId)
    }

    /** 记录"已发送"状态：事件收集线程与心跳协程都可能写，统一走 map 原子更新 */
    private fun markPosted(conversationId: Uuid, statusKey: String) {
        liveUpdateStates.computeIfPresent(conversationId) { _, s ->
            s.also {
                it.lastPostedKey = statusKey
                it.lastPostedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** 通知槽位（同一个 NOTIFICATION_ID）同时只有一个会话可见：取最近活跃的那个 */
    private fun activeConversationId(): Uuid? =
        liveUpdateStates.entries.maxByOrNull { it.value.lastEventAt }?.key

    private fun stopHeartbeat(conversationId: Uuid) {
        liveUpdateStates.computeIfPresent(conversationId) { _, s ->
            s.heartbeat?.cancel()
            s.also { it.heartbeat = null }
        }
    }

    private fun stopHeartbeats() {
        liveUpdateStates.values.forEach { state ->
            state.heartbeat?.cancel()
            state.heartbeat = null
        }
    }

    /**
     * 为工具状态维持 1s 心跳。只有"当前通知归属会话"才启动——
     * 否则多会话各自的心跳会互相覆盖同一个通知 id 造成闪烁。
     * job 在 compute 临界区内创建并登记，避免并发路径重复启动心跳。
     */
    private fun manageHeartbeat(conversationId: Uuid) {
        val current = liveUpdateStates[conversationId] ?: return
        if (current.heartbeat?.isActive == true) return
        if (current.lastPostedKey?.startsWith(TOOL_STATUS_KEY_PREFIX) != true) return

        liveUpdateStates.computeIfPresent(conversationId) { _, s ->
            // 双检：map 内的最新条目为准，防并发创建两个心跳
            if (s.heartbeat?.isActive == true) return@computeIfPresent s
            val isOwner = activeConversationId() == conversationId
            if (!isOwner || s.lastPostedKey?.startsWith(TOOL_STATUS_KEY_PREFIX) != true) {
                return@computeIfPresent s
            }
            // 先声明再赋值，让协程体可捕获自身 Job 用于退出时清理引用
            var job: Job? = null
            job = appScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(LIVE_UPDATE_NOTIFICATION_HEARTBEAT_MS)
                    if (!heartbeatTick(conversationId)) break
                }
                liveUpdateStates.computeIfPresent(conversationId) { _, entry ->
                    // 只清自己这个 job 的引用，避免误清随后被替换的新心跳
                    if (entry.heartbeat === job) entry.also { it.heartbeat = null } else entry
                }
            }
            s.also { it.heartbeat = job }
        }
    }

    /**
     * 单次心跳：返回是否继续。
     * - 同工具：刷新通知推进已运行时长；
     * - 状态已切换（如异步任务终态）：立即发送新状态（兜底，正常由 AsyncTaskTerminal 事件驱动）；
     * - 新状态不再是工具：停止心跳。
     */
    private fun heartbeatTick(conversationId: Uuid): Boolean {
        val state = liveUpdateStates[conversationId] ?: return false
        if (isForeground.value) return false
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return false
        if (!displaySetting.enableLiveUpdateNotification) return false
        if (activeConversationId() != conversationId) return false

        val derived = determineLiveUpdateStatus(state.snapshotParts) { taskId ->
            workspaceRepository.asyncTaskStatus(taskId)?.state
        }
        if (derived.statusKey != state.lastPostedKey) {
            sendLiveUpdateNotification(conversationId, state.snapshotSender, derived)
            markPosted(conversationId, derived.statusKey)
            return derived.kind == LiveUpdateKind.TOOL
        }
        // 同状态：只有带开始时间的工具才需要刷新时长
        if (derived.kind != LiveUpdateKind.TOOL || derived.runningElapsedMs == null) return false

        sendLiveUpdateNotification(conversationId, state.snapshotSender, derived)
        return true
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        senderName: String,
        status: LiveUpdateStatusData,
    ) {
        val chipText: String
        val statusText: String
        when (status.kind) {
            LiveUpdateKind.TOOL -> {
                chipText = context.getString(R.string.notification_live_update_chip_tool)
                val elapsed = status.runningElapsedMs
                statusText = if (elapsed != null) {
                    context.getString(
                        R.string.notification_live_update_tool_elapsed,
                        status.toolName,
                        formatElapsed(elapsed),
                    )
                } else {
                    context.getString(
                        R.string.notification_live_update_tool,
                        status.toolName,
                    )
                }
            }

            LiveUpdateKind.THINKING -> {
                chipText = context.getString(R.string.notification_live_update_chip_thinking)
                statusText = context.getString(R.string.notification_live_update_thinking)
            }

            LiveUpdateKind.WRITING -> {
                chipText = context.getString(R.string.notification_live_update_chip_writing)
                statusText = context.getString(R.string.notification_live_update_writing)
            }

            LiveUpdateKind.DEFAULT -> {
                chipText = context.getString(R.string.notification_live_update_chip_writing)
                statusText = context.getString(R.string.notification_live_update_title)
            }
        }

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID
        ) {
            title = senderName
            content = status.contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateStates.remove(conversationId)?.heartbeat?.cancel()
        context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
