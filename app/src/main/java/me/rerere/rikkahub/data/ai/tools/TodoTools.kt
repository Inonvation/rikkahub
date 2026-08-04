package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
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
)

@Serializable
data class TodoList(
    val items: List<TodoItem>,
)

/** 任务集指纹：由条目的 id 与 content 稳定计算，用于"任务完成后用户关闭卡片"的持久化标记。
 *  任务集不变则指纹不变；AI 新增/更换任务后指纹变化。忽略 status（全部完成时全部是终态）。 */
fun TodoList.fingerprint(): String {
    val content = items
        .map { "${it.id}:${it.content}" }
        .sorted()
        .joinToString("|")
    return Integer.toHexString(content.hashCode())
}

/**
 * 将 todo 列表渲染成给子代理的只读参考（英文，跟随子代理 prompt 语言）。
 * 子代理只读此计划对齐输出，不拥有 todo 工具——计划所有权在父代理。
 */
fun TodoList.renderReference(): String = buildString {
    appendLine("## Current Task Plan (read-only reference)")
    appendLine("The parent agent maintains this plan and may update it while you work. Align your output with it.")
    appendLine("You have no todo tool — do not attempt to modify this plan; the parent agent owns it.")
    if (items.isEmpty()) {
        appendLine("(no tracked items)")
    } else {
        items.forEach { item ->
            val marker = when (item.status) {
                TodoStatus.pending -> "[ ]"
                TodoStatus.in_progress -> "[~]"
                TodoStatus.completed -> "[x]"
                TodoStatus.cancelled -> "[-]"
            }
            appendLine("- $marker ${item.content}  (${item.status.name.replace('_', ' ')})")
        }
    }
}

// 注：早期版本 TodoList 带 message / TodoItem 带 dependencies 默认字段，配合统一 Json 的
// encodeDefaults=true，storage 序列化时会泄出 "message":null 脏值。删除这两个字段后已无
// 默认字段可泄；decode 侧 ignoreUnknownKeys=true 仍会丢弃旧文件/旧模型输出里的残留 key。

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

        IMPORTANT: Update the todo list one step at a time, immediately after each step finishes.
        Before starting a step, call todo_write to mark it in_progress; when the step finishes,
        call todo_write right away to mark it completed. Do NOT defer updates and do NOT batch
        several status changes into one update at the end.
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
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("content")
                            add("status")
                        })
                    })
                })
            },
            required = listOf("items")
        )
    },
    systemPrompt = { _, _ ->
        "Use `todo_write` to track complex tasks (3+ steps). Create a plan before starting, update item status (pending→in_progress→completed/cancelled) as you work. Always pass the full list, not just changes. Before starting a step, mark it in_progress; when the step finishes, mark it completed — one update per step, immediately, never batch updates at the end."
    },
    execute = { args ->
        val items = args.jsonObject["items"]?.jsonArray
            ?: error("items array is required")
        val output = buildJsonObject {
            put("items", items)
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
