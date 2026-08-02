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
 * study_search 工具：按类型 + 关键词搜索学习内容，供 AI 在执行 update/delete 前定位目标 id。
 * 只读，不需要审批。
 *
 * @param subjectScope 学科隔离：非空时只返回该学科的内容；生词（无学科）仅在 scope 为英语时返回。
 */
fun createStudySearchTool(
    daos: StudyDaoSet,
    subjectScope: String? = null,
): Tool = Tool(
    name = "study_search",
    description = """
        Search the user's study content (vocabulary/note/wrong_question/knowledge_card) by keyword.
        Use this to find the exact id before calling any update_* or delete_* tool.
        Returns up to limit results with id/type/title/subject.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("vocabulary"); add("note"); add("wrong_question"); add("knowledge_card")
                    })
                    put("description", "Type of study content to search")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword to search by title/word/concept")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max results to return (default 10, 1-30)")
                })
            },
            required = listOf("type", "query")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val type = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResult("type 不能为空")
        val query = params["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val limit = (params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 30)

        fun inScope(subject: String?): Boolean {
            val scope = subjectScope ?: return true
            if (scope == StudySubject.OTHER) return true
            return subject?.let { StudySubject.normalize(it) == scope } ?: false
        }

        val results = when (type) {
            "vocabulary" -> if (subjectScope != null && subjectScope != StudySubject.OTHER && subjectScope != StudySubject.ENGLISH) {
                emptyList() // 非英语学科助手不能搜索生词
            } else {
                daos.vocabularyDao.search(query).take(limit).map { entity ->
                    searchResultItem(id = entity.id, type = type, title = titleOf(entity), subject = "")
                }
            }
            "note" -> daos.noteDao.search(query).filter { inScope(it.subject) }.take(limit).map { entity ->
                searchResultItem(id = entity.id, type = type, title = titleOf(entity), subject = entity.subject)
            }
            "wrong_question" -> daos.wrongQuestionDao.search(query).filter { inScope(it.subject) }.take(limit).map { entity ->
                searchResultItem(id = entity.id, type = type, title = titleOf(entity), subject = entity.subject)
            }
            "knowledge_card" -> daos.knowledgeCardDao.search(query).filter { inScope(it.subject) }.take(limit).map { entity ->
                searchResultItem(id = entity.id, type = type, title = titleOf(entity), subject = entity.subject)
            }
            else -> return@Tool errorResult("未知类型: $type")
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("count", JsonPrimitive(results.size))
                    put("query", JsonPrimitive(query))
                    put("type", JsonPrimitive(type))
                    put("results", buildJsonArray { results.forEach { add(it) } })
                }.toString()
            )
        )
    }
)

private fun searchResultItem(
    id: String,
    type: String,
    title: String,
    subject: String,
) = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("type", JsonPrimitive(type))
    put("title", JsonPrimitive(title))
    put("subject", JsonPrimitive(subject))
}

