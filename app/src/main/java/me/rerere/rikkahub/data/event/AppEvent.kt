package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成开始（收到第一个 chunk 时）。 */
    data class ChatGenerationStarted(
        val conversationId: Uuid,
    ) : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()

    /**
     * workspace_shell_async 后台任务到达终态（成功/失败/超时），由 WorkspaceAsyncTaskRunner
     * 在工作线程发出（tryEmit，允许丢失）。
     * ChatNotificationManager 据此立即重算 Live Update 状态——否则通知会停留在"正在运行工具"，
     * 直到模型下一次流式更新（可能很久）才纠正。
     */
    data class AsyncTaskTerminal(
        val taskId: String,
    ) : AppEvent()
}
