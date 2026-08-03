package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 子代理任务运行时状态（进程内内存态，不持久化；进程死亡即丢失） */
@Serializable
enum class SubAgentStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, TIMEOUT, CANCELLED, TOKEN_LIMIT;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == TIMEOUT || this == CANCELLED || this == TOKEN_LIMIT
}

/** 子代理任务实例。taskId 与母代理 Tool.toolCallId 一一对应 */
@Serializable
data class SubAgentTask(
    val taskId: String,
    val agentId: String,
    val parentConversationId: Uuid,
    val request: String,
    val modelId: Uuid? = null,
    var status: SubAgentStatus = SubAgentStatus.QUEUED,
    val steps: List<SubAgentStepLog> = emptyList(),
    /** 已调用的工具记录（名称 + 简要入参），供 UI 内联展示"调用了哪些工具" */
    val toolCalls: List<SubAgentToolCall> = emptyList(),
    /** 实时流式输出：子代理生成文本的增量累积，供 UI 详情实时展示 */
    var streamText: String = "",
    /** 实时思考内容：子代理 Reasoning 文本的增量累积，供 UI 详情实时展示（像母代理的思考卡片） */
    var reasoning: String = "",
    /** 结构化消息序列，供 UI 复用主聊天区的思维链渲染。 */
    var messages: List<UIMessage> = emptyList(),
    /** 任务累计 token 用量，跨子代理多步骤生成累加。 */
    var usage: TokenUsage? = null,
    val createdAt: Instant = Clock.System.now(),
    var startedAt: Instant? = null,
    var finishedAt: Instant? = null,
    /** 简短摘要 → 作为母代理上下文的 tool_result */
    var resultSummary: String? = null,
    /** 完整产物（嵌套 part，图片/文档/文本） */
    var result: List<UIMessagePart> = emptyList(),
    var error: String? = null,
    val cancelRequested: Boolean = false,
    /** 已重试次数（第 1 轮失败后自动重跑，retryCount=1）。JSON blob 向后兼容。 */
    var retryCount: Int = 0,
) {
    fun addStep(message: String, status: SubAgentStatus? = null): SubAgentTask =
        copy(steps = steps + SubAgentStepLog(at = Clock.System.now(), message = message, status = status))

    fun addToolCall(name: String, inputSummary: String = ""): SubAgentTask =
        copy(toolCalls = toolCalls + SubAgentToolCall(at = Clock.System.now(), name = name, inputSummary = inputSummary))

    val durationMillis: Long?
        get() {
            val start = startedAt ?: return null
            val end = finishedAt ?: Clock.System.now()
            return (end - start).inWholeMilliseconds
        }
}

@Serializable
data class SubAgentStepLog(
    val at: Instant,
    val message: String,
    val status: SubAgentStatus? = null,
)

/** 子代理调用的单个工具记录 */
@Serializable
data class SubAgentToolCall(
    val at: Instant,
    val name: String,
    val inputSummary: String = "",
)
