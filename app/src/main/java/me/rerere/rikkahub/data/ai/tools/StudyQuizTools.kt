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
import me.rerere.rikkahub.data.model.StudySubject

/**
 * study_quiz 工具：从学习面板随机抽取已保存的生词 / 错题作为测验素材，交给导师出题。
 * 只读，不需要审批。受学科隔离约束：生词仅英语学科助手可用；错题按助手学科过滤。
 */
fun createStudyQuizTool(
    daos: StudyDaoSet,
    subjectScope: String? = null,
): Tool = Tool(
    name = "study_quiz",
    description = """
        Fetch random saved study items to quiz the user.
        Call this when the user wants to review or be quizzed on saved content (e.g. "考考我", "抽背", "测验").
        type "vocabulary" returns random saved words (English tutor), type "wrong_question" returns random saved
        wrong problems for redo. Use the returned material to formulate questions, ask one at a time, and wait
        for the user's answer before continuing.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("vocabulary"); add("wrong_question")
                    })
                    put("description", "Type of saved content to quiz on")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("choice"); add("short_answer"); add("recite")
                    })
                    put("description", "Quiz mode: choice (选择题), short_answer (简答题), recite (抽背)")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of questions (default 1, max 3)")
                })
            },
            required = listOf("type", "mode")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val type = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResult("type 不能为空")
        val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "short_answer"
        val count = (params["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 3)

        val sqlSubject = if (subjectScope == null || subjectScope == StudySubject.OTHER) null else subjectScope

        val material = when (type) {
            "vocabulary" -> {
                if (subjectScope != null && subjectScope != StudySubject.OTHER && subjectScope != StudySubject.ENGLISH) {
                    return@Tool errorResult("当前助手学科范围不允许测验生词")
                }
                daos.vocabularyDao.getRandom(count).joinToString("\n\n---\n\n") { v ->
                    buildString {
                        append("**").append(v.word).append("**")
                        if (v.pronunciation.isNotBlank()) append(" /").append(v.pronunciation).append("/")
                        append("\n释义：").append(v.translations)
                        if (v.mnemonic.isNotBlank()) append("\n助记：").append(v.mnemonic)
                    }
                }
            }
            "wrong_question" -> daos.wrongQuestionDao.getRandom(sqlSubject, count).joinToString("\n\n---\n\n") { wq ->
                buildString {
                    if (wq.title.isNotBlank()) append("标题：").append(wq.title).append("\n")
                    append("题目：").append(wq.question)
                    if (wq.answer.isNotBlank()) append("\n答案：").append(wq.answer)
                    if (wq.solution.isNotBlank()) append("\n解析：").append(wq.solution)
                }
            }
            else -> return@Tool errorResult("未知类型: $type")
        }

        if (material.isBlank()) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", true)
                        put("message", JsonPrimitive(
                            if (type == "vocabulary") "生词面板中还没有保存单词，先去学习几个单词吧~"
                            else "错题面板中还没有保存错题，先做几道题保存下来吧~"
                        ))
                    }.toString()
                )
            )
        } else {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("type", JsonPrimitive(type))
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
