package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, MemoryCategory?) -> AssistantMemory,
    onUpdate: suspend (Int, String, MemoryCategory?) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `category` + `content`
            - Existing relevant record: `edit` + `id` + `category` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in the <memories> tag at the end of the latest user message in later conversations.
            Each memory must be ONE atomic, self-contained fact (a single sentence). Do not bundle unrelated facts.
            Choose `category`: preference (likes/dislikes/style), basic (identity/bio), goal (plans/intentions), work (job/projects), other.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            Store only durable facts; avoid transient states or anything that can be looked up again.
            Duplicate records are rejected automatically: if an equivalent record exists, its existing record is returned — do not create it again; prefer `edit`.
            Edit and delete only work on records in the current memory space; other records are invisible to you.
            Do not show memory content directly in the conversation unless the user explicitly asks.

            Examples:
            {"action":"create","category":"preference","content":"User prefers brief replies."}
            {"action":"edit","id":12,"category":"basic","content":"User's preferred name is A-Xing."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("preference")
                                add("basic")
                                add("goal")
                                add("work")
                                add("other")
                            }
                        )
                        put("description", "Category of the memory record (recommended for create/edit)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "One atomic self-contained fact (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            // 分类是组织性元数据，绝不能阻断记忆写入：
            // 缺失/null 保持历史语义(null)；大小写空白归一化；无法识别的取值一律降级为 OTHER
            val category: MemoryCategory? = when (val raw = params["category"]) {
                null, is JsonNull -> null
                else -> runCatching {
                    // 枚举序列化用大写 name，schema 对模型展示的是小写取值 → 归一化到大写匹配
                    val name = raw.jsonPrimitive.content.trim().uppercase()
                    MemoryCategory.fromNameOrNull(name) ?: MemoryCategory.OTHER
                }.getOrDefault(MemoryCategory.OTHER)
            }
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content, category))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content, category))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
