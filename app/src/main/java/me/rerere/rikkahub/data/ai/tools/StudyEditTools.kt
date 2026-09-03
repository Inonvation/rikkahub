package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
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
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.model.StudySubject
import me.rerere.rikkahub.ui.pages.study.extractPlainText
import me.rerere.rikkahub.ui.pages.study.fixLatexEscapes

/**
 * 学习工具 DAO 集合，供解析器统一访问。
 */
class StudyDaoSet(
    val vocabularyDao: VocabularyDao,
    val noteDao: NoteDao,
    val wrongQuestionDao: WrongQuestionDao,
    val knowledgeCardDao: KnowledgeCardDao,
)

/**
 * 学习工具目标解析结果。
 */
sealed class StudyTarget {
    data class Found(
        val id: String,
        val title: String,
        val type: String,
    ) : StudyTarget()

    data class Ambiguous(
        val candidates: List<Found>,
    ) : StudyTarget()

    data class NotFound(
        val reason: String,
    ) : StudyTarget()
}

internal fun titleOf(entity: Any): String = when (entity) {
    is VocabularyEntity -> entity.word
    is NoteEntity -> entity.title
    is WrongQuestionEntity -> entity.title.ifBlank { extractPlainText(entity.question).ifBlank { entity.question.take(30) } }
    is KnowledgeCardEntity -> entity.concept
    else -> ""
}

/**
 * 解析学习工具操作目标。
 *
 * 优先级：
 * 1. 如果提供了 id，严格按 id 查找。
 * 2. 如果提供了 search_hint，按标题/关键词模糊匹配，恰好一条命中则使用，多条命中返回候选列表。
 * 3. 都没提供则返回未找到。
 *
 * @param subjectScope 学科隔离：非空时只允许命中该学科的内容，其余视为未找到；
 *        生词（vocabulary）无学科字段，若 scope 非空且非 "other" 则不允许操作生词。
 */
suspend fun resolveStudyTarget(
    type: String,
    id: String?,
    searchHint: String?,
    daos: StudyDaoSet,
    subjectScope: String? = null,
): StudyTarget {
    fun matchesScope(subject: String?): Boolean {
        val scope = subjectScope ?: return true
        if (scope == StudySubject.OTHER) return true // 未配置学科（other）不限
        return subject?.let { StudySubject.normalize(it) == scope } ?: false
    }

    fun filtered(candidates: List<Any>): List<Any> =
        if (subjectScope == null || subjectScope == StudySubject.OTHER) candidates
        else candidates.filter { entity ->
            when (entity) {
                is NoteEntity -> matchesScope(entity.subject)
                is WrongQuestionEntity -> matchesScope(entity.subject)
                is KnowledgeCardEntity -> matchesScope(entity.subject)
                is VocabularyEntity -> subjectScope == StudySubject.ENGLISH // 生词本质属于英语
                else -> true
            }
        }

    if (!id.isNullOrBlank()) {
        val entity = when (type) {
            "vocabulary" -> daos.vocabularyDao.getById(id)
            "note" -> daos.noteDao.getById(id)
            "wrong_question" -> daos.wrongQuestionDao.getById(id)
            "knowledge_card" -> daos.knowledgeCardDao.getById(id)
            else -> return StudyTarget.NotFound("未知类型: $type")
        }
        return if (entity != null) {
            // 已归档（软删除）的条目不允许再被 update/delete/read 命中——search 路径只搜 archived=0，
            // 这里补齐 id 精确命中路径的口径，避免对已删除数据操作（archive 是 no-op 却返回成功）。
            val archived = when (entity) {
                is VocabularyEntity -> entity.archived
                is NoteEntity -> entity.archived
                is WrongQuestionEntity -> entity.archived
                is KnowledgeCardEntity -> entity.archived
                else -> false
            }
            if (archived) {
                StudyTarget.NotFound("该${typeName(type)}已被归档/删除，无法操作")
            } else {
                // 按 id 精确命中也要过学科隔离
                val inScope = when (entity) {
                    is VocabularyEntity -> subjectScope == null || subjectScope == StudySubject.OTHER || subjectScope == StudySubject.ENGLISH
                    is NoteEntity -> matchesScope(entity.subject)
                    is WrongQuestionEntity -> matchesScope(entity.subject)
                    is KnowledgeCardEntity -> matchesScope(entity.subject)
                    else -> true
                }
                if (inScope) {
                    StudyTarget.Found(id = id, title = titleOf(entity), type = type)
                } else {
                    StudyTarget.NotFound("学科范围不匹配，无法操作该${typeName(type)}")
                }
            }
        } else {
            StudyTarget.NotFound("未找到 id=$id 的${typeName(type)}")
        }
    }

    if (!searchHint.isNullOrBlank()) {
        val trimmed = searchHint.trim()
        val allCandidates = when (type) {
            "vocabulary" -> daos.vocabularyDao.search(trimmed)
            "note" -> daos.noteDao.search(trimmed)
            "wrong_question" -> daos.wrongQuestionDao.search(trimmed)
            "knowledge_card" -> daos.knowledgeCardDao.search(trimmed)
            else -> emptyList()
        }
        val candidates = filtered(allCandidates)
        return when (candidates.size) {
            0 -> StudyTarget.NotFound("未找到匹配 \"$searchHint\" 的${typeName(type)}")
            1 -> StudyTarget.Found(
                id = when (type) {
                    "vocabulary" -> (candidates[0] as VocabularyEntity).id
                    "note" -> (candidates[0] as NoteEntity).id
                    "wrong_question" -> (candidates[0] as WrongQuestionEntity).id
                    "knowledge_card" -> (candidates[0] as KnowledgeCardEntity).id
                    else -> ""
                },
                title = titleOf(candidates[0]),
                type = type,
            )
            else -> StudyTarget.Ambiguous(candidates.map { entity ->
                StudyTarget.Found(
                    id = when (entity) {
                        is VocabularyEntity -> entity.id
                        is NoteEntity -> entity.id
                        is WrongQuestionEntity -> entity.id
                        is KnowledgeCardEntity -> entity.id
                        else -> ""
                    },
                    title = titleOf(entity),
                    type = type,
                )
            })
        }
    }

    return StudyTarget.NotFound("请提供 id 或 search_hint")
}

private fun typeName(type: String): String = when (type) {
    "vocabulary" -> "生词"
    "note" -> "笔记"
    "wrong_question" -> "错题"
    "knowledge_card" -> "知识点卡片"
    else -> type
}

private fun resultJson(vararg pairs: Pair<String, JsonPrimitive>): String = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}.toString()

private fun successResult(vararg pairs: Pair<String, JsonPrimitive>): List<UIMessagePart> = listOf(
    UIMessagePart.Text(resultJson(*pairs))
)

private fun ambiguousResult(target: StudyTarget.Ambiguous): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("ambiguous", true)
        put("message", JsonPrimitive("找到多个匹配项，请使用精确的 id 重新调用"))
        put("candidates", buildJsonArray {
            target.candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(candidate.id))
                    put("title", JsonPrimitive(candidate.title))
                    put("type", JsonPrimitive(candidate.type))
                })
            }
        })
    }.toString())
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.stringOrEmpty(key: String): String = string(key).orEmpty()
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

// region update tools

fun createUpdateVocabularyTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "update_vocabulary",
    description = """
        Update an EXISTING vocabulary entry. Pass id from study_search result, or search_hint to resolve by word.
        Only provided fields are updated. Call study_search first if you are unsure about the id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Exact id from study_search") })
                put("search_hint", buildJsonObject { put("type", "string"); put("description", "Word or keyword to search for") })
                put("word", buildJsonObject { put("type", "string"); put("description", "New word") })
                put("pronunciation", buildJsonObject { put("type", "string"); put("description", "IPA pronunciation") })
                put("translations", buildJsonObject {
                    put("type", "array")
                    put("description", "List of translation objects with pos and definition")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("pos", buildJsonObject { put("type", "string"); put("description", "Part of speech (n., v., adj., adv.)") })
                            put("definition", buildJsonObject { put("type", "string"); put("description", "The translated definition in Chinese") })
                        })
                    })
                })
                put("examples", buildJsonObject {
                    put("type", "array")
                    put("description", "List of example objects with en and zh")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("en", buildJsonObject { put("type", "string"); put("description", "English example sentence") })
                            put("zh", buildJsonObject { put("type", "string"); put("description", "Chinese translation of the example") })
                        })
                    })
                })
                put("mnemonic", buildJsonObject { put("type", "string"); put("description", "Memory aid") })
                put("tags", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Tags") })
            },
            required = emptyList()
        )
    },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.editEnabled) return@Tool errorResult("编辑功能未启用")
        val params = args.jsonObject
        val target = resolveStudyTarget("vocabulary", params.string("id"), params.string("search_hint"), daos, subjectScope)
        when {
            target is StudyTarget.Ambiguous -> return@Tool ambiguousResult(target)
            target !is StudyTarget.Found -> return@Tool errorResult((target as? StudyTarget.NotFound)?.reason ?: "目标解析失败")
        }
        val current = daos.vocabularyDao.getById(target.id) ?: return@Tool errorResult("找不到该生词")
        val word = params.string("word")?.takeIf { it.isNotBlank() }
        val translations = if (params["translations"] != null) parseArrayField(params, "translations") else null
        val examples = if (params["examples"] != null) parseArrayField(params, "examples") else null
        daos.vocabularyDao.update(
            current.copy(
                word = word ?: current.word,
                pronunciation = params.string("pronunciation")?.takeIf { it.isNotBlank() } ?: current.pronunciation,
                translations = translations ?: current.translations,
                examples = examples ?: current.examples,
                mnemonic = params.string("mnemonic")?.takeIf { it.isNotBlank() } ?: current.mnemonic,
                tags = if (params["tags"] != null) parseArrayField(params, "tags") else current.tags,
            )
        )
        successResult("updated" to JsonPrimitive(true), "id" to JsonPrimitive(target.id), "word" to JsonPrimitive(word ?: current.word))
    }
)

fun createUpdateNoteTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "update_note",
    description = """
        Update an EXISTING note. Pass id from study_search result, or search_hint to resolve by title.
        Only provided fields are updated. Call study_search first if you are unsure about the id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Exact id from study_search") })
                put("search_hint", buildJsonObject { put("type", "string"); put("description", "Title or keyword to search for") })
                put("title", buildJsonObject { put("type", "string"); put("description", "New title") })
                put("content", buildJsonObject { put("type", "string"); put("description", "New markdown content") })
                put("category", buildJsonObject { put("type", "string"); put("description", "New category") })
                put("subject", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("english"); add("math"); add("politics"); add("mechanics"); add("other") }); put("description", "Subject code") })
                put("tags", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Tags") })
            },
            required = emptyList()
        )
    },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.editEnabled) return@Tool errorResult("编辑功能未启用")
        val params = args.jsonObject
        val target = resolveStudyTarget("note", params.string("id"), params.string("search_hint"), daos, subjectScope)
        when {
            target is StudyTarget.Ambiguous -> return@Tool ambiguousResult(target)
            target !is StudyTarget.Found -> return@Tool errorResult((target as? StudyTarget.NotFound)?.reason ?: "目标解析失败")
        }
        val current = daos.noteDao.getById(target.id) ?: return@Tool errorResult("找不到该笔记")
        val subject = params.string("subject")?.let { StudySubject.normalize(it) }
        daos.noteDao.update(
            current.copy(
                title = params.string("title")?.let { fixLatexEscapes(it) }?.takeIf { it.isNotBlank() } ?: current.title,
                content = params.string("content")?.let { fixLatexEscapes(it) } ?: current.content,
                category = params.string("category") ?: current.category,
                subject = subject ?: current.subject,
                tags = if (params["tags"] != null) parseArrayField(params, "tags") else current.tags,
                updatedAt = System.currentTimeMillis(),
            )
        )
        successResult("updated" to JsonPrimitive(true), "id" to JsonPrimitive(target.id), "title" to JsonPrimitive(params.string("title") ?: current.title))
    }
)

fun createUpdateWrongQuestionTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "update_wrong_question",
    description = """
        Update an EXISTING wrong question. Pass id from study_search result, or search_hint to resolve by title/question.
        Only provided fields are updated. Call study_search first if you are unsure about the id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Exact id from study_search") })
                put("search_hint", buildJsonObject { put("type", "string"); put("description", "Title/question keyword to search for") })
                put("title", buildJsonObject { put("type", "string"); put("description", "New concise title") })
                put("question", buildJsonObject { put("type", "string"); put("description", "New problem statement") })
                put("answer", buildJsonObject { put("type", "string"); put("description", "New correct answer") })
                put("solution", buildJsonObject { put("type", "string"); put("description", "New step-by-step solution") })
                put("knowledge_points", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Knowledge points") })
                put("subject", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("english"); add("math"); add("politics"); add("mechanics"); add("other") }); put("description", "Subject code") })
                put("tags", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Tags") })
            },
            required = emptyList()
        )
    },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.editEnabled) return@Tool errorResult("编辑功能未启用")
        val params = args.jsonObject
        val target = resolveStudyTarget("wrong_question", params.string("id"), params.string("search_hint"), daos, subjectScope)
        when {
            target is StudyTarget.Ambiguous -> return@Tool ambiguousResult(target)
            target !is StudyTarget.Found -> return@Tool errorResult((target as? StudyTarget.NotFound)?.reason ?: "目标解析失败")
        }
        val current = daos.wrongQuestionDao.getById(target.id) ?: return@Tool errorResult("找不到该错题")
        val subject = params.string("subject")?.let { StudySubject.normalize(it) }
        val title = params.string("title")?.let { fixLatexEscapes(it) }?.takeIf { it.isNotBlank() }
            ?: params.string("question")?.let { fixLatexEscapes(it) }?.takeIf { it.isNotBlank() }
                ?.let { extractPlainText(it).ifBlank { it.take(30) } }
            ?: current.title
        daos.wrongQuestionDao.update(
            current.copy(
                title = title,
                question = params.string("question")?.let { fixLatexEscapes(it) } ?: current.question,
                answer = params.string("answer")?.let { fixLatexEscapes(it) } ?: current.answer,
                solution = params.string("solution")?.let { fixLatexEscapes(it) } ?: current.solution,
                knowledgePoints = if (params["knowledge_points"] != null) parseArrayField(params, "knowledge_points") else current.knowledgePoints,
                subject = subject ?: current.subject,
                tags = if (params["tags"] != null) parseArrayField(params, "tags") else current.tags,
            )
        )
        successResult("updated" to JsonPrimitive(true), "id" to JsonPrimitive(target.id), "title" to JsonPrimitive(title))
    }
)

fun createUpdateKnowledgeCardTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "update_knowledge_card",
    description = """
        Update an EXISTING knowledge card. Pass id from study_search result, or search_hint to resolve by concept.
        Only provided fields are updated. Call study_search first if you are unsure about the id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Exact id from study_search") })
                put("search_hint", buildJsonObject { put("type", "string"); put("description", "Concept or keyword to search for") })
                put("concept", buildJsonObject { put("type", "string"); put("description", "New concept name") })
                put("explanation", buildJsonObject { put("type", "string"); put("description", "New explanation") })
                put("memory_aid", buildJsonObject { put("type", "string"); put("description", "New memory aid") })
                put("subject", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("english"); add("math"); add("politics"); add("mechanics"); add("other") }); put("description", "Subject code") })
                put("tags", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Tags") })
            },
            required = emptyList()
        )
    },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.editEnabled) return@Tool errorResult("编辑功能未启用")
        val params = args.jsonObject
        val target = resolveStudyTarget("knowledge_card", params.string("id"), params.string("search_hint"), daos, subjectScope)
        when {
            target is StudyTarget.Ambiguous -> return@Tool ambiguousResult(target)
            target !is StudyTarget.Found -> return@Tool errorResult((target as? StudyTarget.NotFound)?.reason ?: "目标解析失败")
        }
        val current = daos.knowledgeCardDao.getById(target.id) ?: return@Tool errorResult("找不到该知识点卡片")
        val subject = params.string("subject")?.let { StudySubject.normalize(it) }
        daos.knowledgeCardDao.update(
            current.copy(
                concept = params.string("concept")?.let { fixLatexEscapes(it) }?.takeIf { it.isNotBlank() } ?: current.concept,
                explanation = params.string("explanation")?.let { fixLatexEscapes(it) } ?: current.explanation,
                memoryAid = params.string("memory_aid")?.takeIf { it.isNotBlank() } ?: current.memoryAid,
                subject = subject ?: current.subject,
                tags = if (params["tags"] != null) parseArrayField(params, "tags") else current.tags,
            )
        )
        successResult("updated" to JsonPrimitive(true), "id" to JsonPrimitive(target.id), "concept" to JsonPrimitive(params.string("concept") ?: current.concept))
    }
)

// endregion

// region delete tools

private fun deleteToolParameters(type: String): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject {
        put("id", buildJsonObject { put("type", "string"); put("description", "Exact id from study_search") })
        put("search_hint", buildJsonObject { put("type", "string"); put("description", "Keyword to search for the item") })
        put("confirm_title", buildJsonObject { put("type", "string"); put("description", "Must exactly match the title/word/concept of the item to delete") })
        put("permanent", buildJsonObject { put("type", "boolean"); put("description", "If true, permanently delete instead of archiving") })
    },
    required = listOf("confirm_title")
)

private suspend fun executeDelete(
    type: String,
    params: JsonObject,
    daos: StudyDaoSet,
    subjectScope: String? = null,
): List<UIMessagePart> {
    val target = resolveStudyTarget(type, params.string("id"), params.string("search_hint"), daos, subjectScope)
    when {
        target is StudyTarget.Ambiguous -> return ambiguousResult(target)
        target !is StudyTarget.Found -> return errorResult((target as? StudyTarget.NotFound)?.reason ?: "目标解析失败")
    }
    val confirmTitle = params.string("confirm_title")?.trim()
        ?: return errorResult("confirm_title 不能为空")
    if (confirmTitle != target.title) {
        return errorResult("确认标题不匹配，实际标题为: ${target.title}")
    }
    val permanent = params.boolean("permanent") == true
    if (permanent) {
        when (type) {
            "vocabulary" -> daos.vocabularyDao.deleteById(target.id)
            "note" -> daos.noteDao.deleteById(target.id)
            "wrong_question" -> daos.wrongQuestionDao.deleteById(target.id)
            "knowledge_card" -> daos.knowledgeCardDao.deleteById(target.id)
        }
    } else {
        when (type) {
            "vocabulary" -> daos.vocabularyDao.archive(target.id)
            "note" -> daos.noteDao.archive(target.id)
            "wrong_question" -> daos.wrongQuestionDao.archive(target.id)
            "knowledge_card" -> daos.knowledgeCardDao.archive(target.id)
        }
    }
    return successResult(
        "deleted" to JsonPrimitive(true),
        "id" to JsonPrimitive(target.id),
        "title" to JsonPrimitive(target.title),
        "type" to JsonPrimitive(type),
        "permanent" to JsonPrimitive(permanent),
        "soft" to JsonPrimitive(!permanent)
    )
}

fun createDeleteVocabularyTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "delete_vocabulary",
    description = """
        Delete an EXISTING vocabulary entry. Pass id from study_search result, or search_hint to resolve by word.
        You MUST provide confirm_title which exactly matches the word to delete.
        By default the entry is archived (recoverable in the vocabulary panel). Set permanent=true to delete permanently.
    """.trimIndent().replace("\n", " "),
    parameters = { deleteToolParameters("vocabulary") },
    needsApproval = { permissions.needsApproval("delete_vocabulary") },
    execute = { args ->
        if (!permissions.deleteEnabled) return@Tool errorResult("删除功能未启用")
        executeDelete("vocabulary", args.jsonObject, daos, subjectScope)
    }
)

fun createDeleteNoteTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "delete_note",
    description = """
        Delete an EXISTING note. Pass id from study_search result, or search_hint to resolve by title.
        You MUST provide confirm_title which exactly matches the note title to delete.
        By default the note is archived (recoverable in the notes panel). Set permanent=true to delete permanently.
    """.trimIndent().replace("\n", " "),
    parameters = { deleteToolParameters("note") },
    needsApproval = { permissions.needsApproval("delete_note") },
    execute = { args ->
        if (!permissions.deleteEnabled) return@Tool errorResult("删除功能未启用")
        executeDelete("note", args.jsonObject, daos, subjectScope)
    }
)

fun createDeleteWrongQuestionTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "delete_wrong_question",
    description = """
        Delete an EXISTING wrong question. Pass id from study_search result, or search_hint to resolve by title/question.
        You MUST provide confirm_title which exactly matches the question title to delete.
        By default the question is archived (recoverable in the wrong question panel). Set permanent=true to delete permanently.
    """.trimIndent().replace("\n", " "),
    parameters = { deleteToolParameters("wrong_question") },
    needsApproval = { permissions.needsApproval("delete_wrong_question") },
    execute = { args ->
        if (!permissions.deleteEnabled) return@Tool errorResult("删除功能未启用")
        executeDelete("wrong_question", args.jsonObject, daos, subjectScope)
    }
)

fun createDeleteKnowledgeCardTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): Tool = Tool(
    name = "delete_knowledge_card",
    description = """
        Delete an EXISTING knowledge card. Pass id from study_search result, or search_hint to resolve by concept.
        You MUST provide confirm_title which exactly matches the concept to delete.
        By default the card is archived (recoverable in the knowledge card panel). Set permanent=true to delete permanently.
    """.trimIndent().replace("\n", " "),
    parameters = { deleteToolParameters("knowledge_card") },
    needsApproval = { permissions.needsApproval("delete_knowledge_card") },
    execute = { args ->
        if (!permissions.deleteEnabled) return@Tool errorResult("删除功能未启用")
        executeDelete("knowledge_card", args.jsonObject, daos, subjectScope)
    }
)

// endregion
