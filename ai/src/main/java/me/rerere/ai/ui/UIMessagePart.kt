package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

/**
 * 服务端工具调用的通用生命周期状态。
 *
 * Provider 返回的更细粒度状态应保存在 [UIMessagePart.ServerTool.metadata] 中，避免在通用层绑定具体协议。
 */
@Serializable
enum class ServerToolStatus {
    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

/** The kind of text carried by a reasoning part. */
@Serializable
enum class ReasoningType {
    @SerialName("reasoning_text")
    REASONING_TEXT,

    @SerialName("summary_text")
    SUMMARY_TEXT,
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    /**
     * 由 Provider 在服务端执行的工具调用，例如网页搜索、文件检索或代码执行。
     *
     * 与 [Tool] 不同，此类型只追踪调用及结果，不参与客户端审批或执行流程。[input] 和 [output]
     * 使用通用 JSON，以容纳不同 Provider、不同工具的协议结构；需要原样回传的 Provider 数据也应保留在其中。
     */
    @Serializable
    @SerialName("server_tool")
    data class ServerTool(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        val isFinished: Boolean
            get() = status == ServerToolStatus.COMPLETED || status == ServerToolStatus.FAILED
    }

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        /**
         * 工具调用的目的说明（来自 Tool 定义的 description，生成时填充）。
         * 用于审批卡片/详情展示"这个操作是干什么的"；旧数据缺省为空串，向后兼容。
         */
        val description: String = "",
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
        val startedAtMs: Long? = null,
        val finishedAtMs: Long? = null,
        /** 排队等待开始执行（如排在 workspace 串行锁后面）的时刻，仅用于 UI 展示「等待中」 */
        val queuedAt: Instant? = null,
        val queuedAtMs: Long? = null,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool has recorded a start time (wall clock or monotonic) */
        val hasStarted: Boolean get() = startedAt != null || startedAtMs != null

        /** Whether the tool has been queued (waiting to start, e.g. behind a workspace serial lock) */
        val hasQueued: Boolean get() = queuedAt != null || queuedAtMs != null

        /** Whether the tool is currently queued and has not started yet */
        val isQueued: Boolean get() = hasQueued && !hasStarted

        /** Whether the tool has recorded an end time (wall clock or monotonic) */
        val isFinished: Boolean get() = finishedAt != null || finishedAtMs != null

        /** Whether the tool has started executing but has not finished yet */
        val isRunning: Boolean get() = hasStarted && !isFinished

        /** Unified execution duration in milliseconds, preferring the monotonic clock. */
        val durationMs: Long?
            get() {
                val startMs = startedAtMs
                val endMs = finishedAtMs
                if (startMs != null && endMs != null) return endMs - startMs
                val start = startedAt
                val end = finishedAt
                if (start != null && end != null) return (end - start).inWholeMilliseconds
                return null
            }

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = !isExecuted && approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                description = description.ifBlank { other.description },
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                startedAt = startedAt ?: other.startedAt,
                finishedAt = finishedAt ?: other.finishedAt,
                startedAtMs = startedAtMs ?: other.startedAtMs,
                finishedAtMs = finishedAtMs ?: other.finishedAtMs,
                queuedAt = queuedAt ?: other.queuedAt,
                queuedAtMs = queuedAtMs ?: other.queuedAtMs,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }
}
