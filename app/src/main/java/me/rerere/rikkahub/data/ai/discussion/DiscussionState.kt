package me.rerere.rikkahub.data.ai.discussion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 讨论进行阶段 */
enum class DiscussionPhase {
    /** 未开始/已结束 */
    IDLE,

    /** 正在决定下一位发言人 */
    SCHEDULING,

    /** 某成员正在发言生成中 */
    GENERATING,

    /** 被用户暂停（插话后重新调度） */
    PAUSED,

    /** 已结束 */
    COMPLETED,

    /** 出错（成员不足/配置异常） */
    ERROR,
}

/**
 * 群组讨论运行时状态（进程内 StateFlow，供 UI 订阅）。
 * 不持久化；重启后由会话历史 + discussion 配置重建。
 */
data class DiscussionState(
    val phase: DiscussionPhase = DiscussionPhase.IDLE,
    /** 正在/即将发言的成员 */
    val currentSpeakerId: Uuid? = null,
    val currentSpeakerName: String? = null,
    /** 已完成轮数（每名成员发言计一轮） */
    val turnIndex: Int = 0,
    /** 预计总轮数（RoundRobin 可预知，Selector 为 maxTurns 上限） */
    val totalTurns: Int = 0,
    /** 当前进行到第几轮（每成员一轮算一轮） */
    val roundIndex: Int = 0,
    val lastError: String? = null,
    /** 累计 token 用量 */
    val usageTokens: Long = 0,
)

/**
 * 群组讨论运行时状态（进程内 StateFlow，供 UI 订阅）。
 * 不持久化；重启后由会话历史 + discussion 配置重建。
 *
 * 按 conversationId 隔离：多个群组同时/先后讨论互不串状态。
 */
class DiscussionStateHolder {
    private val states = ConcurrentHashMap<Uuid, MutableStateFlow<DiscussionState>>()

    fun state(conversationId: Uuid): MutableStateFlow<DiscussionState> =
        states.computeIfAbsent(conversationId) { MutableStateFlow(DiscussionState()) }

    fun update(conversationId: Uuid, transform: (DiscussionState) -> DiscussionState) {
        state(conversationId).update(transform)
    }

    fun reset(conversationId: Uuid) {
        states[conversationId]?.value = DiscussionState()
    }
}
