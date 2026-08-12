package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PendingSteering
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // steering：生成中待注入的引导队列（FIFO）。immediate=true 的项由 GenerationHandler 在
    // 下一轮边界（工具调用完成/输出结束）立即注入；其余项排队不动，等回合结束后由
    // ChatService 的 drain 依次自动注入为 user_guidance 气泡 + 续答指令。UI 直接订阅本队列渲染气泡。
    val steeringQueue = MutableStateFlow<List<PendingSteering>>(emptyList())

    // steering 队列的串行消费任务（防止并发重复拉起 drain）
    @Volatile
    var steeringDrainJob: Job? = null

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    /**
     * 最近一次生成活动时间（单调时钟，ms）：流式输出落地时由 ChatService 更新。
     * resumeAfterSubAgent 用它在等待期间判断母代理是否还在正常产出——
     * 有活动说明正常流式/工具循环，不该掐断；等待期间毫无活动才视为挂起并接管。
     */
    @Volatile
    var lastGenerationActivityAt: Long = 0L

    /**
     * 生成中排队的待发送消息（带附件时不能走 steering 文本引导，只能排队等回合结束再发）。
     * 回合正常结束后由 ChatService 取出并作为普通新消息发送；被取消时不自动发（保留）。
     */
    @Volatile
    var pendingSendContents: List<UIMessagePart>? = null

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            // 身份校验：仅当「当前引用的就是本次 job」时才清空。否则旧 job 被取消后
            // 仍要跑完 onCompletion（含 suspend 落库），会在新 job 写入之后才完成，
            // 无条件置 null 会把新 job 引用清掉——停止按钮消失、stopGeneration 失效、
            // resumeAfterSubAgent 防重入失效（生成停不下来/并发续答）的根因。
            if (_generationJob.value === job) {
                _generationJob.value = null
            }
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
