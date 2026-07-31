package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
enum class TodoStatus {
    pending,
    in_progress,
    completed,
    cancelled,
}

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus = TodoStatus.pending,
    val dependencies: List<String> = emptyList(),
)

@Serializable
data class TodoList(
    val items: List<TodoItem>,
    val message: String? = null,
)

fun createTodoTool(
    conversationId: String,
    todoStorage: TodoStorage,
): Tool = Tool(
    name = "todo_write",
    description = """
        Create and manage a structured task list for planning and tracking complex multi-step work.

        Always create a todolist before starting tasks that involve 3+ distinct steps.
        Update item status as you work: mark as in_progress before starting, completed when done, cancelled if irrelevant.
        Pass the FULL todolist each time, not just the delta.
        The message field is optional — use it to briefly explain what changed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("items", buildJsonObject {
                    put("type", "array")
                    put("description", "The complete list of todo items")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this item")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Description of the task")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("pending")
                                    add("in_progress")
                                    add("completed")
                                    add("cancelled")
                                })
                                put("description", "Current status of the task")
                            })
                            put("dependencies", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                                put("description", "IDs of tasks that must complete first")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("content")
                            add("status")
                        })
                    })
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Brief summary of what changed in this update")
                })
            },
            required = listOf("items")
        )
    },
    systemPrompt = { _, _ ->
        """
        ## Task Management

        You have access to a `todo_write` tool for managing structured task lists.

        ### When to use:
        - The user's request involves 3+ distinct steps or sub-tasks
        - The task is complex and requires planning
        - You need to track progress across multiple tool calls

        ### Workflow:
        1. Before starting work, call `todo_write` to create the initial plan with all items as "pending"
        2. Before starting a task, update its status to "in_progress"
        3. Immediately after completing a task, update its status to "completed"
        4. If a task becomes irrelevant, mark it as "cancelled"
        5. Always pass the FULL todolist (all items), not just the ones that changed

        ### Guidelines:
        - Keep items granular and actionable
        - Use clear, descriptive names
        - Order items logically (consider dependencies)
        - Update the todolist after EACH significant step, not just at the end
        """.trimIndent()
    },
    execute = { args ->
        val items = args.jsonObject["items"]?.jsonArray
            ?: error("items array is required")
        val message = args.jsonObject["message"]?.jsonPrimitive?.contentOrNull
        val output = buildJsonObject {
            put("items", items)
            message?.let { put("message", JsonPrimitive(it)) }
        }
        val outputStr = output.toString()
        // 持久化到文件，防止对话截断/压缩导致数据丢失
        runCatching {
            val todoList = JsonInstant.decodeFromString<TodoList>(outputStr)
            todoStorage.save(conversationId, todoList)
        }
        listOf(UIMessagePart.Text(outputStr))
    }
)

/**
 * 从对话中提取最新的 todolist。
 * 优先从消息中的 todo_write 工具调用读取，找不到时从文件存储 fallback。
 * 如果所有任务已完成且用户已发下一条消息，自动清理文件并返回 null。
 */
fun List<UIMessage>.extractLatestTodoListFromConversation(
    todoStorage: TodoStorage? = null,
    conversationId: String? = null,
): TodoList? {
    val allTools = this.flatMap { msg ->
        msg.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName == "todo_write" }
    }
    val latestTodo = allTools.lastOrNull()
    val result = if (latestTodo != null) {
        val jsonStr = if (latestTodo.isExecuted) {
            latestTodo.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
        } else {
            latestTodo.input
        }
        runCatching { JsonInstant.decodeFromString<TodoList>(jsonStr) }.getOrNull()
    } else {
        todoStorage?.let { conversationId?.let { id -> it.load(id) } }
    } ?: return null

    if (shouldAutoCleanup(result, todoStorage, conversationId)) return null

    return result
}

private fun List<UIMessage>.shouldAutoCleanup(
    todoList: TodoList,
    todoStorage: TodoStorage?,
    conversationId: String?,
): Boolean {
    if (todoStorage == null || conversationId == null) return false
    if (!todoList.isAllDone()) return false

    val lastTodoMsgIndex = indexOfLast { msg ->
        msg.parts.any { part ->
            part is UIMessagePart.Tool && part.toolName == "todo_write" && part.isExecuted
        }
    }
    if (lastTodoMsgIndex < 0) return false
    val hasUserMessageAfter = drop(lastTodoMsgIndex + 1).any { it.role == MessageRole.USER }
    if (!hasUserMessageAfter) return false

    todoStorage.delete(conversationId)
    return true
}

private fun TodoList.isAllDone(): Boolean =
    items.all { it.status == TodoStatus.completed || it.status == TodoStatus.cancelled }