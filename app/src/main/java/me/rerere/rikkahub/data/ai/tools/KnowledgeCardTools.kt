package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.ui.pages.study.fixLatexEscapes
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import kotlin.uuid.Uuid

fun createKnowledgeCardTool(
    conversationId: String,
    knowledgeCardDao: KnowledgeCardDao,
    subject: String, // "politics" or "mechanics"
): Tool = Tool(
    name = "save_knowledge_card",
    description = """
        Save an important concept with its explanation and memory aid to the user's knowledge card panel.
        Call this after explaining an important concept.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("concept", buildJsonObject {
                    put("type", "string")
                    put("description", "The concept name")
                })
                put("explanation", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed explanation of the concept")
                })
                put("memory_aid", buildJsonObject {
                    put("type", "string")
                    put("description", "Memory aid, mnemonic, or clever trick in Chinese")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tags for categorization")
                })
            },
            required = listOf("concept", "explanation")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val concept = fixLatexEscapes(params["concept"]?.jsonPrimitive?.contentOrNull ?: error("concept is required"))
        val explanation = fixLatexEscapes(params["explanation"]?.jsonPrimitive?.contentOrNull ?: "")
        val memoryAid = params["memory_aid"]?.jsonPrimitive?.contentOrNull ?: ""
        val tags = params["tags"]?.jsonArray?.toString() ?: "[]"

        val entity = KnowledgeCardEntity(
            id = Uuid.random().toString(),
            concept = concept,
            explanation = explanation,
            memoryAid = memoryAid,
            subject = subject,
            tags = tags,
            sourceConversationId = conversationId,
        )
        knowledgeCardDao.insert(entity)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("saved", true)
                    put("concept", JsonPrimitive(concept))
                    put("message", JsonPrimitive("已保存到知识点卡片"))
                }.toString()
            )
        )
    }
)

fun createQuizUserTool(
    knowledgeChunkDao: KnowledgeChunkDao,
): Tool = Tool(
    name = "quiz_user",
    description = """
        Fetch random study material from the knowledge base to quiz the user.
        Call this when the user says "抽背", "提问", or "考考我".
        Returns up to 3 random knowledge chunks. Use them to formulate questions for the user.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("choice")
                        add("short_answer")
                        add("recite")
                    })
                    put("description", "Quiz mode: choice (选择题), short_answer (简答题), recite (抽背概念)")
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional topic filter (e.g., '马原', '毛中特', '四杆机构')")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of questions (default 1, max 3)")
                })
            },
            required = listOf("mode")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "short_answer"
        val topic = params["topic"]?.jsonPrimitive?.contentOrNull
        val count = (params["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 3)

        val chunks = try {
            knowledgeChunkDao.getRandomChunks(topic, count)
        } catch (e: Exception) {
            knowledgeChunkDao.getRandomChunksFallback().take(count)
        }

        if (chunks.isEmpty()) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", true)
                        put("message", JsonPrimitive("知识库中没有相关内容，请先导入学习资料"))
                    }.toString()
                )
            )
        } else {
            val material = chunks.joinToString("\n\n---\n\n") { it.content }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("mode", JsonPrimitive(mode))
                        put("material", JsonPrimitive(material))
                        put("instruction", JsonPrimitive(
                            "请根据以上素材，以${mode}模式向用户提问，每次只问一题，等待用户回答后再继续。"
                        ))
                    }.toString()
                )
            )
        }
    }
)