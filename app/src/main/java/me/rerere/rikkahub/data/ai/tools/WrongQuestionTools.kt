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
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.ui.pages.study.fixLatexEscapes
import kotlin.uuid.Uuid

fun createWrongQuestionTool(
    conversationId: String,
    wrongQuestionDao: WrongQuestionDao,
    subject: String, // "math" or "mechanics"
): Tool = Tool(
    name = "save_wrong_question",
    description = """
        Save a problem with its solution and knowledge points to the user's wrong question book for later review.
        Call this after solving a problem that the user struggled with or that is representative.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The problem statement")
                })
                put("answer", buildJsonObject {
                    put("type", "string")
                    put("description", "The correct answer")
                })
                put("solution", buildJsonObject {
                    put("type", "string")
                    put("description", "Step-by-step solution with reasoning")
                })
                put("knowledge_points", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Knowledge points being tested by this problem")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Tags for categorization")
                })
                put("image_paths", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "File paths of problem images (if any)")
                })
            },
            required = listOf("question", "answer", "solution")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val question = fixLatexEscapes(params["question"]?.jsonPrimitive?.contentOrNull ?: error("question is required"))
        val answer = fixLatexEscapes(params["answer"]?.jsonPrimitive?.contentOrNull ?: "")
        val solution = fixLatexEscapes(params["solution"]?.jsonPrimitive?.contentOrNull ?: "")
        val knowledgePoints = params["knowledge_points"]?.jsonArray?.toString() ?: "[]"
        val tags = params["tags"]?.jsonArray?.toString() ?: "[]"
        val imagePaths = params["image_paths"]?.jsonArray?.toString() ?: "[]"

        val entity = WrongQuestionEntity(
            id = Uuid.random().toString(),
            question = question,
            answer = answer,
            solution = solution,
            knowledgePoints = knowledgePoints,
            subject = subject,
            tags = tags,
            imagePaths = imagePaths,
            sourceConversationId = conversationId,
        )
        wrongQuestionDao.insert(entity)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("saved", true)
                    put("subject", JsonPrimitive(subject))
                    put("message", JsonPrimitive("已保存到错题本"))
                }.toString()
            )
        )
    }
)