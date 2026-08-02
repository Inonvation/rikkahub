package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
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
import me.rerere.rikkahub.data.model.StudySubject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TYPE_VOCABULARY = "vocabulary"
private const val TYPE_NOTE = "note"
private const val TYPE_WRONG_QUESTION = "wrong_question"
private const val TYPE_KNOWLEDGE_CARD = "knowledge_card"

/** 统计工具共用的聚合条目 */
internal data class StudyItem(
    val type: String,
    val id: String,
    val title: String,
    val subject: String,
    val createdAt: Long,
)

/** 统计工具共用的入参解析结果 */
internal data class StatsParams(
    val scope: String,
    val subject: String?,
    val type: String?,
    val periodDays: Int,
)

/**
 * 学习统计工具工厂：study_stats / study_summary / study_mindmap。
 * 全部只读、无需审批，但受 [StudyToolPermissions.statsEnabled] 开关控制。
 *
 * @param subjectScope 学科隔离：非空时只统计该学科的内容。
 */
fun createStudyStatsTools(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String? = null,
): List<Tool> = listOf(
    createStudyStatsTool(daos, permissions, subjectScope),
    createStudySummaryTool(daos, permissions, subjectScope),
    createStudyMindmapTool(daos, permissions, subjectScope),
)

private fun createStudyStatsTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String?,
): Tool = Tool(
    name = "study_stats",
    description = """
        Compute statistics over the user's study content (vocabulary/note/wrong_question/knowledge_card),
        filtered by scope/subject/type and period_days. Use this when the user asks about study stats, counts,
        or recent activity.
    """.trimIndent().replace("\n", " "),
    parameters = { statsParameters() },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.statsEnabled) return@Tool errorResult("统计功能未启用")
        val params = parseStatsParams(args.jsonObject)
        val now = System.currentTimeMillis()
        val items = collectStudyItems(daos, params, now, subjectScope)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("period_days", JsonPrimitive(params.periodDays))
                    put("counts", countJson(items))
                    put("by_subject", bySubjectJson(items))
                    byDayJson(items, params.periodDays, now)?.let { put("by_day", it) }
                }.toString()
            )
        )
    }
)

private fun createStudySummaryTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String?,
): Tool = Tool(
    name = "study_summary",
    description = """
        Summarize the user's study content over a period: total counts per type plus the most recent items of each
        type. Use this when the user asks for a study review or summary of what has been learned.
    """.trimIndent().replace("\n", " "),
    parameters = { statsParameters() },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.statsEnabled) return@Tool errorResult("统计功能未启用")
        val params = parseStatsParams(args.jsonObject)
        val now = System.currentTimeMillis()
        val items = collectStudyItems(daos, params, now, subjectScope)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("period_days", JsonPrimitive(params.periodDays))
                    put("total", countJson(items))
                    put("recent", buildJsonObject {
                        put(TYPE_VOCABULARY, recentJson(items, TYPE_VOCABULARY))
                        put(TYPE_NOTE, recentJson(items, TYPE_NOTE))
                        put(TYPE_WRONG_QUESTION, recentJson(items, TYPE_WRONG_QUESTION))
                        put(TYPE_KNOWLEDGE_CARD, recentJson(items, TYPE_KNOWLEDGE_CARD))
                    })
                }.toString()
            )
        )
    }
)

private fun createStudyMindmapTool(
    daos: StudyDaoSet,
    permissions: StudyToolPermissions,
    subjectScope: String?,
): Tool = Tool(
    name = "study_mindmap",
    description = """
        Generate a Mermaid mindmap that visually organizes the user's study content by subject.
        The result contains a "mermaid" field. Please put the returned mermaid code verbatim into a ```mermaid
        code block and display it to the user.
    """.trimIndent().replace("\n", " "),
    parameters = { statsParameters() },
    needsApproval = { false },
    execute = { args ->
        if (!permissions.statsEnabled) return@Tool errorResult("统计功能未启用")
        val params = parseStatsParams(args.jsonObject)
        val now = System.currentTimeMillis()
        val items = collectStudyItems(daos, params, now, subjectScope)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("mermaid", JsonPrimitive(buildMermaid(items)))
                    put("counts", countJson(items))
                    put("period_days", JsonPrimitive(params.periodDays))
                }.toString()
            )
        )
    }
)

// region 入参 & 聚合

private fun statsParameters(): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject {
        put("scope", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("all"); add("subject"); add("type") })
            put("description", "统计范围: all(全部)/subject(按学科)/type(按内容类型)")
        })
        put("subject", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add(StudySubject.ENGLISH); add(StudySubject.MATH); add(StudySubject.POLITICS)
                add(StudySubject.MECHANICS); add(StudySubject.OTHER)
            })
            put("description", "学科代码，scope=subject 时使用")
        })
        put("type", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add(TYPE_VOCABULARY); add(TYPE_NOTE); add(TYPE_WRONG_QUESTION); add(TYPE_KNOWLEDGE_CARD)
            })
            put("description", "内容类型，scope=type 时使用")
        })
        put("period_days", buildJsonObject {
            put("type", "integer")
            put("description", "统计最近多少天（默认 30，0 表示不限）")
        })
    },
    required = emptyList()
)

private fun parseStatsParams(params: JsonObject): StatsParams {
    val scope = params["scope"]?.jsonPrimitive?.contentOrNull ?: "all"
    val subject = params["subject"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val type = params["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val periodDays = (params["period_days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30).coerceIn(0, 365)
    return StatsParams(scope = scope, subject = subject, type = type, periodDays = periodDays)
}

/** 聚合全库学习内容（非归档），并按 scope/subject/type/period_days/subjectScope 过滤 */
internal suspend fun collectStudyItems(
    daos: StudyDaoSet,
    params: StatsParams,
    now: Long,
    subjectScope: String? = null,
): List<StudyItem> {
    val cutoff = if (params.periodDays > 0) now - params.periodDays * 24L * 60 * 60 * 1000 else 0L

    fun inScope(item: StudyItem): Boolean {
        val scope = subjectScope ?: return true
        if (scope == StudySubject.OTHER) return true
        if (item.type == TYPE_VOCABULARY) return scope == StudySubject.ENGLISH // 生词无学科字段，归英语
        return item.subject.let { StudySubject.normalize(it) == scope }
    }

    val items = mutableListOf<StudyItem>()
    daos.vocabularyDao.getAllFlow().first().forEach { entity ->
        items += StudyItem(TYPE_VOCABULARY, entity.id, titleOf(entity), "", entity.createdAt)
    }
    daos.noteDao.getAllFlow().first().forEach { entity ->
        items += StudyItem(TYPE_NOTE, entity.id, titleOf(entity), entity.subject, entity.createdAt)
    }
    daos.wrongQuestionDao.getAllFlow().first().forEach { entity ->
        items += StudyItem(TYPE_WRONG_QUESTION, entity.id, titleOf(entity), entity.subject, entity.createdAt)
    }
    daos.knowledgeCardDao.getAllFlow().first().forEach { entity ->
        items += StudyItem(TYPE_KNOWLEDGE_CARD, entity.id, titleOf(entity), entity.subject, entity.createdAt)
    }

    return items.filter { item ->
        // scope=subject 时：normalize 学科后比较（兼容中文/大小写变体），生词无学科字段归英语
        val subjectFilter = params.subject?.takeIf { it.isNotBlank() }?.let { target ->
            StudySubject.normalize(target)
        }
        val scopeOk = params.scope != "subject" || subjectFilter == null || when (item.type) {
            TYPE_VOCABULARY -> subjectFilter == StudySubject.ENGLISH
            else -> item.subject.let { StudySubject.normalize(it) == subjectFilter }
        }
        val typeOk = params.scope != "type" || item.type == params.type
        val periodOk = params.periodDays <= 0 || item.createdAt >= cutoff
        val subjectOk = inScope(item)
        scopeOk && typeOk && periodOk && subjectOk
    }
}

// endregion

// region JSON 拼装

internal fun countJson(items: List<StudyItem>): JsonObject = buildJsonObject {
    put(TYPE_VOCABULARY, JsonPrimitive(items.count { it.type == TYPE_VOCABULARY }))
    put(TYPE_NOTE, JsonPrimitive(items.count { it.type == TYPE_NOTE }))
    put(TYPE_WRONG_QUESTION, JsonPrimitive(items.count { it.type == TYPE_WRONG_QUESTION }))
    put(TYPE_KNOWLEDGE_CARD, JsonPrimitive(items.count { it.type == TYPE_KNOWLEDGE_CARD }))
}

internal fun bySubjectJson(items: List<StudyItem>): JsonObject = buildJsonObject {
    items.filter { it.subject.isNotBlank() }.groupBy { it.subject }.forEach { (subject, list) ->
        put(subject, countJson(list))
    }
}

/** 按天统计；period_days 不在 1..30 时返回 null（调用方省略 by_day 字段） */
internal fun byDayJson(
    items: List<StudyItem>,
    periodDays: Int,
    now: Long,
): JsonArray? {
    if (periodDays !in 1..30) return null
    // 与 collectStudyItems 的截断口径对齐：总数按 now - N*24h 过滤，按天桶的首日下界取
    // 日历日起点与截断点中较晚者，避免 DST/非整点场景下条目计入总数但不进任何按天桶。
    val cutoff = now - periodDays * 24L * 60 * 60 * 1000
    val start = Instant.ofEpochMilli(cutoff)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val today = LocalDate.now()
    return buildJsonArray {
        var day = start
        while (!day.isAfter(today)) {
            val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val bucketStart = if (day == start) maxOf(dayStart, cutoff) else dayStart
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayItems = items.filter { it.createdAt in bucketStart until dayEnd }
            if (dayItems.isNotEmpty()) {
                add(buildJsonObject {
                    put("date", JsonPrimitive(day.toString()))
                    put("counts", countJson(dayItems))
                })
            }
            day = day.plusDays(1)
        }
    }
}

private fun recentJson(items: List<StudyItem>, type: String): JsonArray = buildJsonArray {
    items.filter { it.type == type }
        .sortedByDescending { it.createdAt }
        .take(5)
        .forEach { add(buildJsonObject {
            put("id", JsonPrimitive(it.id))
            put("title", JsonPrimitive(it.title))
            put("created_at", JsonPrimitive(it.createdAt))
        }) }
}

// endregion

// region mermaid 拼装

private fun buildMermaid(items: List<StudyItem>): String {
    val sb = StringBuilder()
    sb.appendLine("mindmap")
    sb.appendLine("  root((学习总览))")

    val bySubject = items.filter { it.subject.isNotBlank() }.groupBy { it.subject }
    bySubject.forEach { (subject, list) ->
        sb.appendLine("    ${StudySubject.name(subject)}")
        list.groupBy { it.type }.forEach { (type, typeList) ->
            sb.appendLine("      ${typeDisplayName(type)} ${typeList.size}")
        }
    }

    val vocabularyCount = items.count { it.type == TYPE_VOCABULARY }
    if (vocabularyCount > 0) {
        sb.appendLine("    生词本")
        sb.appendLine("      生词 $vocabularyCount")
    }

    return sb.toString().trimEnd()
}

private fun typeDisplayName(type: String): String = when (type) {
    TYPE_VOCABULARY -> "生词"
    TYPE_NOTE -> "笔记"
    TYPE_WRONG_QUESTION -> "错题"
    TYPE_KNOWLEDGE_CARD -> "知识点"
    else -> type
}

// endregion

