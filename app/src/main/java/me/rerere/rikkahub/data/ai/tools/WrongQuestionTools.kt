package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.model.StudySubject
import me.rerere.rikkahub.ui.pages.study.extractPlainText
import me.rerere.rikkahub.ui.pages.study.fixLatexEscapes
import kotlin.uuid.Uuid

fun createWrongQuestionTool(
    conversationId: String,
    wrongQuestionDao: WrongQuestionDao,
    defaultSubject: String,
): Tool = Tool(
    name = "save_wrong_question",
    description = """
        Save a problem with its solution and knowledge points to the user's wrong question book for later review.
        Call this after solving a problem that the user struggled with or that is representative.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "A short concise plain-text title for this problem without any LaTeX or formulas (under 30 characters), e.g. 对数不等式. If the question contains formulas, describe the topic in plain text instead.")
                })
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The problem statement, concise and under 60 characters")
                })
                put("answer", buildJsonObject {
                    put("type", "string")
                    put("description", "The correct answer")
                })
                put("solution", buildJsonObject {
                    put("type", "string")
                    put("description", "Step-by-step solution with reasoning, Markdown and LaTeX allowed")
                })
                put("knowledge_points", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Knowledge points being tested by this problem")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("english"); add("math"); add("politics"); add("mechanics"); add("other")
                    })
                    put("description", "Optional subject code (english/math/politics/mechanics/other). If omitted, the assistant's configured subject is used.")
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
        val title = fixLatexEscapes(params["title"]?.jsonPrimitive?.contentOrNull ?: "").trim()
        val question = fixLatexEscapes(params["question"]?.jsonPrimitive?.contentOrNull ?: error("question is required"))
        val answer = fixLatexEscapes(params["answer"]?.jsonPrimitive?.contentOrNull ?: "")
        val solution = fixLatexEscapes(params["solution"]?.jsonPrimitive?.contentOrNull ?: "")
        val knowledgePoints = parseArrayField(params, "knowledge_points")
        val tags = parseArrayField(params, "tags")
        val imagePaths = parseArrayField(params, "image_paths")
        val subject = StudySubject.normalize(params["subject"]?.jsonPrimitive?.contentOrNull ?: defaultSubject)
        val safeTitle = title.ifBlank { extractPlainText(question).ifBlank { question.take(30) } }

        val entity = WrongQuestionEntity(
            id = Uuid.random().toString(),
            title = safeTitle,
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
