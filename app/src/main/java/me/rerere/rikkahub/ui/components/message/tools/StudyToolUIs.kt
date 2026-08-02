package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartBarLine
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.NodeEdit
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.model.StudySubject
import me.rerere.rikkahub.ui.components.richtext.Mermaid
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 四类学习内容的中文标签，顺序固定 */
private val STUDY_TYPE_LABELS = listOf(
    "vocabulary" to "生词",
    "note" to "笔记",
    "wrong_question" to "错题",
    "knowledge_card" to "知识点",
)

/** 从 counts JSON 对象解析出 [标签, 数量] 列表 */
private fun countsOf(obj: JsonObject?): List<Pair<String, Int>> =
    STUDY_TYPE_LABELS.mapNotNull { (key, label) ->
        obj?.get(key)?.jsonPrimitiveOrNull?.intOrNull?.let { label to it }
    }

private fun countsTotal(counts: List<Pair<String, Int>>): Int = counts.sumOf { it.second }

private fun formatCounts(counts: List<Pair<String, Int>>): String =
    counts.joinToString(" · ") { "${it.first} ${it.second}" }

/**
 * 学习统计 (study_stats): 摘要显示四类计数与总数, 详情展示按学科/按天明细
 */
object StudyStatsToolUI : ToolUIRenderer {
    override val toolName: String = "study_stats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ChartBarLine

    private fun content(context: ToolUIContext): JsonObject? = context.content?.jsonObjectOrNull

    override fun hasSummary(context: ToolUIContext): Boolean =
        countsOf(content(context)?.get("counts") as? JsonObject).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val counts = countsOf(content(context)?.get("counts") as? JsonObject)
        if (counts.isEmpty()) return
        Text(
            text = "${formatCounts(counts)} · 共 ${countsTotal(counts)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = content(context)
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        StudyStatsPreview(content)
    }
}

@Composable
private fun StudyStatsPreview(content: JsonObject) {
    val counts = countsOf(content["counts"] as? JsonObject)
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("学习统计", style = MaterialTheme.typography.titleMedium)
                counts.forEach { (label, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$count",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "共 ${countsTotal(counts)} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        val bySubject = content["by_subject"] as? JsonObject
        if (bySubject != null && bySubject.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("按学科", style = MaterialTheme.typography.titleSmall)
                    bySubject.forEach { (subject, countsEl) ->
                        val c = countsOf(countsEl as? JsonObject)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                StudySubject.name(subject),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "共 ${countsTotal(c)} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }

        val byDay = content["by_day"] as? JsonArray
        if (byDay != null && byDay.isNotEmpty()) {
            item {
                Text("按天", style = MaterialTheme.typography.titleSmall)
            }
            items(byDay) { el ->
                val obj = el.jsonObjectOrNull ?: return@items
                val date = obj["date"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                val c = countsOf(obj["counts"] as? JsonObject)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(date, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "共 ${countsTotal(c)} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * 学习思维导图 (study_mindmap): 摘要与详情都内联渲染 mermaid
 */
object StudyMindmapToolUI : ToolUIRenderer {
    override val toolName: String = "study_mindmap"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.NodeEdit

    private fun mermaid(context: ToolUIContext): String? =
        context.content?.jsonObjectOrNull?.getStringContent("mermaid")

    override fun hasSummary(context: ToolUIContext): Boolean = mermaid(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val code = mermaid(context) ?: return
        Mermaid(code = code)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val code = mermaid(context)
        if (code == null) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("学习思维导图", style = MaterialTheme.typography.titleMedium)
            Mermaid(code = code)
        }
    }
}

/**
 * 学习总结 (study_summary): 摘要显示总数, 详情展示各类最近条目
 */
object StudySummaryToolUI : ToolUIRenderer {
    override val toolName: String = "study_summary"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CheckList

    private fun content(context: ToolUIContext): JsonObject? = context.content?.jsonObjectOrNull

    override fun hasSummary(context: ToolUIContext): Boolean =
        countsOf(content(context)?.get("total") as? JsonObject).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val counts = countsOf(content(context)?.get("total") as? JsonObject)
        if (counts.isEmpty()) return
        Text(
            text = "${formatCounts(counts)} · 共 ${countsTotal(counts)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = content(context)
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        StudySummaryPreview(content)
    }
}

@Composable
private fun StudySummaryPreview(content: JsonObject) {
    val total = countsOf(content["total"] as? JsonObject)
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("学习总结", style = MaterialTheme.typography.titleMedium)
                Text(
                    "共 ${countsTotal(total)} 条 · ${formatCounts(total)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        val recent = content["recent"] as? JsonObject
        if (recent != null) {
            STUDY_TYPE_LABELS.forEach { (key, label) ->
                val list = recent[key] as? JsonArray
                if (list != null && list.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            list.forEach { el ->
                                val obj = el.jsonObjectOrNull ?: return@forEach
                                val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?: return@forEach
                                Text(
                                    text = "• $title",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 学习搜索 (study_search): 摘要显示命中数
 */
object StudySearchToolUI : ToolUIRenderer {
    override val toolName: String = "study_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val query = context.arguments.getStringContent("query") ?: ""
        val type = context.arguments.getStringContent("type")
        val label = STUDY_TYPE_LABELS.firstOrNull { it.first == type }?.second ?: type
        return if (query.isBlank()) "搜索学习内容" else "搜索$label「$query」"
    }

    private fun results(context: ToolUIContext): JsonArray? =
        context.content?.jsonObjectOrNull?.get("results") as? JsonArray

    override fun hasSummary(context: ToolUIContext): Boolean =
        (results(context) ?: emptyList<JsonObject>()).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results == null || results.isEmpty()) return
        Text(
            text = "找到 ${results.size} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            modifier = Modifier.shimmer(isLoading = context.loading),
        )
    }
}

/**
 * update_* / delete_* 工具的轻量渲染基类: 标题显示操作对象名
 */
internal abstract class StudyMutationRenderer(
    override val toolName: String,
    private val verb: String,
    private val iconVector: ImageVector,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = iconVector

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.content?.jsonObjectOrNull?.getStringContent("title")
            ?: context.content?.jsonObjectOrNull?.getStringContent("word")
            ?: context.content?.jsonObjectOrNull?.getStringContent("concept")
            ?: context.arguments.getStringContent("word")
            ?: context.arguments.getStringContent("title")
            ?: context.arguments.getStringContent("question")
            ?: context.arguments.getStringContent("concept")
            ?: ""
        return if (name.isBlank()) verb else "$verb: $name"
    }
}

internal object StudyUpdateVocabularyToolUI : StudyMutationRenderer("update_vocabulary", "修改生词", HugeIcons.Edit01)
internal object StudyUpdateNoteToolUI : StudyMutationRenderer("update_note", "修改笔记", HugeIcons.Edit01)
internal object StudyUpdateWrongQuestionToolUI : StudyMutationRenderer("update_wrong_question", "修改错题", HugeIcons.Edit01)
internal object StudyUpdateKnowledgeCardToolUI : StudyMutationRenderer("update_knowledge_card", "修改知识点", HugeIcons.Edit01)
internal object StudyDeleteVocabularyToolUI : StudyMutationRenderer("delete_vocabulary", "删除生词", HugeIcons.Delete01)
internal object StudyDeleteNoteToolUI : StudyMutationRenderer("delete_note", "删除笔记", HugeIcons.Delete01)
internal object StudyDeleteWrongQuestionToolUI : StudyMutationRenderer("delete_wrong_question", "删除错题", HugeIcons.Delete01)
internal object StudyDeleteKnowledgeCardToolUI : StudyMutationRenderer("delete_knowledge_card", "删除知识点", HugeIcons.Delete01)
