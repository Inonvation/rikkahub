package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.appendText

/** 超过 N 轮用户消息没有更新 todo，就注入提醒 */
private const val TURNS_BEFORE_REMINDER = 5

/** 超过 N 个已执行工具步没更新 todo，就注入提醒（治"单轮内攒一堆再批量更新"） */
private const val TOOLS_BEFORE_REMINDER = 3

/** 两次工具步提醒之间至少间隔的工具步数（提醒冷却，防提醒贬值）。
 *  与 Claude Code 的 turnsSinceLastReminder 语义一致：提醒要"稀缺"，太密会被模型当噪声忽略。 */
private const val TOOLS_BETWEEN_REMINDERS = 8

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
        if (!ctx.settings.enableTodoList) return messages

        val conversationId = ctx.conversationId ?: return messages

        // 唯一数据源：直接读 TodoStorage，不从对话消息里反查 todo_write 调用
        val todoList = todoStorage.load(conversationId) ?: return messages

        if (!todoList.hasActive()) return messages

        val lastTodoIndex = messages.indexOfLast { msg ->
            msg.parts.any { part ->
                part is UIMessagePart.Tool && part.toolName == "todo_write" && part.isExecuted
            }
        }
        val tail = if (lastTodoIndex >= 0) messages.drop(lastTodoIndex + 1) else messages

        // 落后判定 1：跨轮——最近一次更新后，用户已经又发了 N 条新消息
        val userMessagesSinceLastTodo = tail.count { it.role == MessageRole.USER }
        // 落后判定 2：单轮内——最近一次更新后，又执行了 N 个其它工具还没更新。
        // 治"AI 闷头做完一大堆，最后才批量更新 todo"。已执行的工具 output 都有回填，
        // 生成中新发起但还没回填的 pending 工具不算（那轮可能马上跟 todo_write）。
        val executedToolsSinceLastTodo = tail.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted && it.toolName != "todo_write" }
            .size

        // 有 active 项且跨多轮没动 = 忘记 todo；单轮内连续干了好几个活没更新 = 攒着批量更新。
        // 两者任一成立就注入提醒（带当前完整状态，模型只需改一两个字段就能跟上）
        val isFallbehindTurns = userMessagesSinceLastTodo >= TURNS_BEFORE_REMINDER
        val isFallbehindTools = executedToolsSinceLastTodo >= TOOLS_BEFORE_REMINDER
        if (!isFallbehindTurns && !isFallbehindTools) return messages

        // 全局累计工具步（含 todo_write），用于提醒冷却基线
        val executedToolsTotal = messages.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .count { it.isExecuted }

        // 工具步触发需过提醒冷却：距上次提醒至少隔 TOOLS_BETWEEN_REMINDERS 个工具步，防提醒贬值。
        // 跨轮触发不查冷却——它的阈值本身就是低频节奏；且若用工具步增量做冷却，
        // 模型"闲聊不干活"时增量恒为 0，跨轮提醒会被永久卡死。
        if (isFallbehindTools && !isFallbehindTurns) {
            val lastReminderStep = todoStorage.loadReminderStep(conversationId)
            if (lastReminderStep != null && executedToolsTotal - lastReminderStep < TOOLS_BETWEEN_REMINDERS) {
                return messages
            }
        }

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
            when {
                // 单轮内连续干了好几个活没更新：明确要求"做完一步更新一步，别攒着"
                isFallbehindTools && !isFallbehindTurns -> appendLine(
                    "You have completed ${executedToolsSinceLastTodo} steps since the last todo_write. " +
                        "Call todo_write NOW to mark the completed items, one update per finished step. " +
                        "Do NOT batch all updates at the end."
                )

                // 跨轮忘了更新
                isFallbehindTurns -> appendLine(
                    "You have not updated the todo list for ${userMessagesSinceLastTodo} user turns. " +
                        "Call todo_write now to sync item status with your actual progress."
                )

                else -> appendLine("Remember to call todo_write to update task status as you work.")
            }
            append("</system-reminder>")
        }

        // 更新提醒基线：从当前累计工具步起算冷却
        todoStorage.saveReminderStep(conversationId, executedToolsTotal)

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