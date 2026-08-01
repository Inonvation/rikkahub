package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.ui.pages.study.fixLatexEscapes
import kotlin.uuid.Uuid

fun createNoteTool(
    conversationId: String,
    assistantId: String,
    noteDao: NoteDao,
): Tool = Tool(
    name = "save_note",
    description = """
        Save useful content (essay templates, good sentences, problem-solving techniques, formulas, etc.) to the user's notes.
        Call this after providing content that is worth saving for future reference.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "A short descriptive title for the note")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "The full note content in Markdown format")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Category: 作文模板, 好句积累, 语法笔记, 解题思路, 公式定理, 论述框架, 时政热点, 背诵要点, 机构图解, 公式推导, 真题解析")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tags for categorization")
                })
            },
            required = listOf("title", "content", "category")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val title = fixLatexEscapes(params["title"]?.jsonPrimitive?.contentOrNull ?: error("title is required"))
        val content = fixLatexEscapes(params["content"]?.jsonPrimitive?.contentOrNull ?: "")
        val category = params["category"]?.jsonPrimitive?.contentOrNull ?: error("category is required")
        val tags = params["tags"]?.jsonArray?.toString() ?: "[]"

        val entity = NoteEntity(
            id = Uuid.random().toString(),
            title = title,
            content = content,
            category = category,
            tags = tags,
            sourceAssistantId = assistantId,
            sourceConversationId = conversationId,
        )
        noteDao.insert(entity)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("saved", true)
                    put("title", JsonPrimitive(title))
                    put("category", JsonPrimitive(category))
                    put("message", JsonPrimitive("已保存到笔记"))
                }.toString()
            )
        )
    }
)