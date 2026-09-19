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

/**
 * 记忆能力指针（system 能力层）：经 [buildMemoryTools] 挂在 memory_tool 上，随工具注册
 * 自动出现在本轮 system（走 buildToolSystemPrompts 收集），与工具生命周期严格同源。
 * 工具 description 只教「怎么调用」，这里补「何时写 / 护栏」：
 * 保存门槛（明确陈述的持久事实，而非主动抓取）、先查 `<memories>` 再 edit、
 * 重复自动拒绝、不主动复述记忆内容。全静态文本，注册期间逐字节稳定，不破坏 system 缓存前缀；
 * 读取口径（数据定位/冲突取舍/不复述）在 <memories> 注入块内，两处分工不重复。
 */
internal val MEMORY_TOOL_SYSTEM_PROMPT = """
You have persistent long-term memory: facts saved with memory_tool are returned to you in a `<memories>` block in later conversations.

When to save:
- Only when the user explicitly states a durable fact about themselves — preference, identity, goal, or ongoing work.
- When the user corrects something already known about them: edit the existing record instead of creating a duplicate.
Guardrails:
- Do not save speculative inferences, transient states ("this time", "today"), one-off task details, or anything derivable from the current conversation.
- Do not store sensitive information (ethnicity, religion, sexual orientation, political views, sex life, criminal records).
- Before creating, check the `<memories>` block: if an equivalent record is already listed, edit that record's id instead of creating a new one.
- Never show memory content in the conversation unless the user explicitly asks.
""".trim()

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, MemoryCategory?) -> AssistantMemory,
    onUpdate: suspend (Int, String, MemoryCategory?) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        systemPrompt = { _, _ -> MEMORY_TOOL_SYSTEM_PROMPT },
        description = """
            Store long-term user facts across conversations.
            Use when the user states a durable fact (preference, identity, goal, work) or corrects one; avoid transient details.
            `action`: create (`category` + `content`, one atomic sentence), edit (record from `<memories>`: `id` + `content`), delete (`id`).
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
                        put("description", "Category (create/edit): preference=likes/dislikes/style, basic=identity, goal=plans, work=job/projects, other=misc")
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
