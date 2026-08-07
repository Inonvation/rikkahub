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
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.model.StudySubject

/**
 * study_read 工具：读取一条学习内容的完整正文（笔记内容 / 错题题干与解析 / 知识点卡片解释 / 生词释义与例句）。
 * 只读，不需要审批。受学科隔离约束：只能读取当前助手学科范围内的内容。
 *
 * @param subjectScope 学科隔离：非空时只允许读取该学科的内容；生词归英语学科。
 */
fun createStudyReadTool(
    daos: StudyDaoSet,
    subjectScope: String? = null,
): Tool = Tool(
    name = "study_read",
    description = """
        Read the full content of one study item (note / wrong_question / knowledge_card / vocabulary) by id.
        Use study_search first to find the id, then call this to get the complete text (note content, question and
        answer, explanation, translations and examples). Use this when the user asks you to review, summarize,
        explain, or reference their existing notes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("vocabulary"); add("note"); add("wrong_question"); add("knowledge_card")
                    })
                    put("description", "Type of study content to read")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact id from study_search result")
                })
            },
            required = listOf("type", "id")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val type = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResult("type 不能为空")
        val id = params["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResult("id 不能为空")

        fun inScope(subject: String?): Boolean {
            val scope = subjectScope ?: return true
            if (scope == StudySubject.OTHER) return true
            return subject?.let { StudySubject.normalize(it) == scope } ?: false
        }

        when (type) {
            "vocabulary" -> {
                // 生词无学科字段，仅英语学科助手可读
                if (subjectScope != null && subjectScope != StudySubject.OTHER && subjectScope != StudySubject.ENGLISH) {
                    return@Tool errorResult("当前助手学科范围不允许读取生词")
                }
                val entity = daos.vocabularyDao.getById(id)
                    ?: return@Tool errorResult("未找到该生词")
                if (entity.archived) return@Tool errorResult("该生词已被归档/删除，无法读取")
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("found", true)
                            put("type", JsonPrimitive(type))
                            put("id", JsonPrimitive(id))
                            put("word", JsonPrimitive(entity.word))
                            put("pronunciation", JsonPrimitive(entity.pronunciation))
                            put("translations", JsonPrimitive(entity.translations))
                            put("examples", JsonPrimitive(entity.examples))
                            put("mnemonic", JsonPrimitive(entity.mnemonic))
                            put("tags", JsonPrimitive(entity.tags))
                            put("created_at", JsonPrimitive(entity.createdAt))
                        }.toString()
                    )
                )
            }
            "note" -> {
                val entity = daos.noteDao.getById(id) ?: return@Tool errorResult("未找到该笔记")
                if (entity.archived) return@Tool errorResult("该笔记已被归档/删除，无法读取")
                if (!inScope(entity.subject)) return@Tool errorResult("学科范围不匹配，无法读取该笔记")
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("found", true)
                            put("type", JsonPrimitive(type))
                            put("id", JsonPrimitive(id))
                            put("title", JsonPrimitive(entity.title))
                            put("content", JsonPrimitive(entity.content))
                            put("category", JsonPrimitive(entity.category))
                            put("subject", JsonPrimitive(entity.subject))
                            put("tags", JsonPrimitive(entity.tags))
                            put("created_at", JsonPrimitive(entity.createdAt))
                        }.toString()
                    )
                )
            }
            "wrong_question" -> {
                val entity = daos.wrongQuestionDao.getById(id) ?: return@Tool errorResult("未找到该错题")
                if (entity.archived) return@Tool errorResult("该错题已被归档/删除，无法读取")
                if (!inScope(entity.subject)) return@Tool errorResult("学科范围不匹配，无法读取该错题")
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("found", true)
                            put("type", JsonPrimitive(type))
                            put("id", JsonPrimitive(id))
                            put("title", JsonPrimitive(entity.title))
                            put("question", JsonPrimitive(entity.question))
                            put("answer", JsonPrimitive(entity.answer))
                            put("solution", JsonPrimitive(entity.solution))
                            put("knowledge_points", JsonPrimitive(entity.knowledgePoints))
                            put("subject", JsonPrimitive(entity.subject))
                            put("tags", JsonPrimitive(entity.tags))
                            put("created_at", JsonPrimitive(entity.createdAt))
                        }.toString()
                    )
                )
            }
            "knowledge_card" -> {
                val entity = daos.knowledgeCardDao.getById(id) ?: return@Tool errorResult("未找到该知识点卡片")
                if (entity.archived) return@Tool errorResult("该知识点卡片已被归档/删除，无法读取")
                if (!inScope(entity.subject)) return@Tool errorResult("学科范围不匹配，无法读取该知识点卡片")
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("found", true)
                            put("type", JsonPrimitive(type))
                            put("id", JsonPrimitive(id))
                            put("concept", JsonPrimitive(entity.concept))
                            put("explanation", JsonPrimitive(entity.explanation))
                            put("memory_aid", JsonPrimitive(entity.memoryAid))
                            put("subject", JsonPrimitive(entity.subject))
                            put("tags", JsonPrimitive(entity.tags))
                            put("created_at", JsonPrimitive(entity.createdAt))
                        }.toString()
                    )
                )
            }
            else -> return@Tool errorResult("未知类型: $type")
        }
    }
)

/**
 * study_list 工具：按类型分页列举用户在"学习面板"中已保存的内容（生词 / 笔记 / 错题 / 知识点卡片）。
 * 只读，不需要审批。受学科隔离约束：只能列举当前助手学科范围内的内容。
 *
 * @param subjectScope 学科隔离：非空时只返回该学科的内容；生词（无学科）仅在 scope 为英语时返回。
 */
fun createStudyListTool(
    daos: StudyDaoSet,
    subjectScope: String? = null,
): Tool = Tool(
    name = "study_list",
    description = """
        List the user's saved study content (vocabulary/note/wrong_question/knowledge_card) in the study panel.
        Use this when the user wants to review, browse, or get an overview of what has been saved so far.
        Returns a paginated list of {id, type, title, subject}. Call study_read with an id to open the full content.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("vocabulary"); add("note"); add("wrong_question"); add("knowledge_card")
                    })
                    put("description", "Type of study content to list")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max results to return (default 20, 1-50)")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Skip the first N results for pagination (default 0)")
                })
            },
            required = listOf("type")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val type = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResult("type 不能为空")
        val limit = (params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (params["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0).coerceAtLeast(0)

        class ListEntry(val id: String, val title: String, val subject: String, val category: String?)

        val sqlSubject = if (subjectScope == null || subjectScope == StudySubject.OTHER) null else subjectScope
        val vocabularyAllowed = subjectScope == null || subjectScope == StudySubject.OTHER || subjectScope == StudySubject.ENGLISH

        val entries: List<ListEntry>
        val total: Int
        when (type) {
            "vocabulary" -> {
                if (!vocabularyAllowed) {
                    entries = emptyList()
                    total = 0
                } else {
                    total = daos.vocabularyDao.countActive()
                    entries = daos.vocabularyDao.getPaged(limit, offset).map {
                        ListEntry(it.id, titleOf(it), "", null)
                    }
                }
            }
            "note" -> {
                total = daos.noteDao.countActive(sqlSubject)
                entries = daos.noteDao.getPaged(sqlSubject, limit, offset).map {
                    ListEntry(it.id, titleOf(it), it.subject, it.category)
                }
            }
            "wrong_question" -> {
                total = daos.wrongQuestionDao.countActive(sqlSubject)
                entries = daos.wrongQuestionDao.getPaged(sqlSubject, limit, offset).map {
                    ListEntry(it.id, titleOf(it), it.subject, null)
                }
            }
            "knowledge_card" -> {
                total = daos.knowledgeCardDao.countActive(sqlSubject)
                entries = daos.knowledgeCardDao.getPaged(sqlSubject, limit, offset).map {
                    ListEntry(it.id, titleOf(it), it.subject, null)
                }
            }
            else -> return@Tool errorResult("未知类型: $type")
        }

        val page = entries
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("count", JsonPrimitive(page.size))
                    put("total", JsonPrimitive(total))
                    put("type", JsonPrimitive(type))
                    put("offset", JsonPrimitive(offset))
                    put("has_more", JsonPrimitive(offset + page.size < total))
                    put("results", buildJsonArray {
                        page.forEach { entry ->
                            add(buildJsonObject {
                                put("id", JsonPrimitive(entry.id))
                                put("type", JsonPrimitive(type))
                                put("title", JsonPrimitive(entry.title))
                                put("subject", JsonPrimitive(entry.subject))
                                if (entry.category != null) put("category", JsonPrimitive(entry.category))
                            })
                        }
                    })
                }.toString()
            )
        )
    }
)
