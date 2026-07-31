package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.appendText
import me.rerere.rikkahub.utils.JsonInstant

/** 超过 N 轮用户消息没有更新 todo，就注入提醒 */
private const val TURNS_BEFORE_REMINDER = 8

/**
 * Todo 被动提醒注入器。
 * 当对话中有活跃的 todo 任务，但模型已经多轮没有更新时，自动注入一条系统提醒。
 */
class TodoReminderTransformer(
    private val todoStorage: TodoStorage,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTodoList) return messages

        val conversationId = ctx.conversationId ?: return messages

        val lastTodoTool = messages.flatMap { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                .filter { it.toolName == "todo_write" && it.isExecuted }
        }.lastOrNull()

        val todoList = if (lastTodoTool != null) {
            val jsonStr = lastTodoTool.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            runCatching {
                JsonInstant.decodeFromString<TodoList>(jsonStr)
            }.getOrNull()
        } else {
            todoStorage.load(conversationId)
        } ?: return messages

        if (!todoList.hasActive()) return messages

        val lastTodoIndex = messages.indexOfLast { msg ->
            msg.parts.any { part ->
                part is UIMessagePart.Tool && part.toolName == "todo_write" && part.isExecuted
            }
        }
        val userMessagesSinceLastTodo = if (lastTodoIndex >= 0) {
            messages.drop(lastTodoIndex + 1).count { it.role == MessageRole.USER }
        } else {
            messages.count { it.role == MessageRole.USER }
        }

        if (userMessagesSinceLastTodo < TURNS_BEFORE_REMINDER) return messages

        val reminder = buildString {
            appendLine("<system-reminder>")
            appendLine("Current task status:")
            todoList.items.forEach { item ->
                val statusMark = when (item.status) {
                    TodoStatus.pending -> "  [ ]"
                    TodoStatus.in_progress -> "  [~]"
                    TodoStatus.completed -> "  [x]"
                    TodoStatus.cancelled -> "  [-]"
                }
                appendLine("$statusMark ${item.content}")
            }
            appendLine("Remember to call todo_write to update task status as you work.")
            append("</system-reminder>")
        }

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$reminder")
            }
        } else {
            listOf(UIMessage.system(reminder)) + messages
        }
    }
}

private fun TodoList.hasActive(): Boolean =
    items.any { it.status == TodoStatus.pending || it.status == TodoStatus.in_progress }