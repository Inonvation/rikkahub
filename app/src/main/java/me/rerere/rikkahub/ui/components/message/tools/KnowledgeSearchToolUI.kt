package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Database01
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 知识库检索工具（kb_search / kb_list）的渲染器。
 *
 * kb_search / kb_list 的输出是给模型读的**纯文本**（`KnowledgeSearchTool.formatResults()`
 * 的 `[N] 来源: 文档名 (相关度 85%)` 格式，或 `scanAll` 的 `N. 命中行` 格式），不是 JSON，
 * 走通用气泡只能看到一串原文。这里解析原始输出文本，把「命中来源列表」提炼成
 * 知识库来源引用卡片——不改工具实现、模型看到的文本保持不变。
 */
private object KnowledgeSearchOutputParser {
    private val foundChunks = Regex("""Found (\d+) relevant chunks""")
    private val foundMatches = Regex("""found (\d+) matches""")
    private val sourceLine = Regex("""\[\d+\] 来源: (.+?) \((.+?)\)""")
    private val scanLine = Regex("""^\d+\. (.+)$""")
    /** formatResults 的条目分隔：`---\n[1] 来源: ...` */
    private val segmentSplit = Regex("""\n---\n""")

    /** 单个来源的详情：文档名 + 分数描述 + 命中内容片段 */
    data class SourceDetail(
        val name: String,
        val score: String,
        val snippet: String,
    )

    /** 解析结果：命中数 + 来源列表（Summary 用）+ 来源详情（Preview 用） */
    data class Parsed(
        val count: Int?,
        val sources: List<Pair<String, String>>,
        val details: List<SourceDetail>,
    )

    fun parse(text: String): Parsed {
        val count = foundChunks.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: foundMatches.find(text)?.groupValues?.get(1)?.toIntOrNull()

        val details = mutableListOf<SourceDetail>()
        // formatResults 格式（每段一来源）："---\n[1] 来源: 文档名 (相关度 85%)\n<命中内容>\n"
        text.split(segmentSplit).forEach { segment ->
            val seg = segment.trim()
            if (seg.isBlank()) return@forEach
            val m = sourceLine.find(seg) ?: return@forEach
            val name = m.groupValues[1].trim()
            val score = m.groupValues[2].trim()
            // 来源行之后的全部内容 = 命中片段
            val content = seg.substring(m.range.last + 1).trim()
            details.add(SourceDetail(name, score, content))
        }

        // scan 格式: "N. 命中行" —— 仅在没有来源段时回退（避免误抓正文里以数字开头的行）
        if (details.isEmpty()) {
            scanLine.findAll(text).forEach { m ->
                val line = m.groupValues[1].trim()
                if (line.isNotBlank()) {
                    details.add(SourceDetail(line.take(60), "", ""))
                }
            }
        }

        val sources = details.map { it.name to it.score }
        return Parsed(count, sources, details)
    }
}

/** kb_search / kb_list 的输出文本（合并所有 Text 部件） */
private fun toolOutputText(context: ToolUIContext): String =
    context.tool.output.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }

@Composable
private fun ToolUIContext.rememberParsed(): KnowledgeSearchOutputParser.Parsed =
    remember(tool) { KnowledgeSearchOutputParser.parse(toolOutputText(this)) }

/**
 * 从 kb_search 入参提取可读的检索参数行（label to value），详情页展示「AI 实际用了什么」。
 * 注意：query 是模型传给工具的检索词；若开了 query 改写，实际检索词可能与此不同。
 */
private fun argumentRows(arguments: kotlinx.serialization.json.JsonElement): List<Pair<String, String>> {
    val obj = arguments as? JsonObject ?: return emptyList()
    val rows = mutableListOf<Pair<String, String>>()
    obj["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
        rows.add("检索词" to it)
    }
    obj["mode"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "hybrid" }?.let {
        rows.add("模式" to it)
    }
    obj["topK"]?.jsonPrimitive?.contentOrNull?.let {
        rows.add("TopK" to it)
    }
    obj["keywordWeight"]?.jsonPrimitive?.contentOrNull?.let {
        rows.add("关键词权重" to it)
    }
    (obj["knowledgeBaseIds"] as? JsonArray)?.let { arr ->
        rows.add("检索知识库" to "${arr.size} 个")
    }
    return rows
}

/**
 * 检索关键词黄色高亮：query 按空白分词，大小写不敏感子串匹配，合并重叠区间。
 * 无命中时原样输出全文。与 KnowledgeBaseDetailPage 的检索测试高亮一致。
 */
private fun AnnotatedString.Builder.highlightQuery(text: String, query: String) {
    if (query.isBlank()) {
        append(text)
        return
    }
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) {
        append(text)
        return
    }

    // 收集所有命中区间（大小写不敏感）
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(term, index, ignoreCase = true)
            if (start == -1) break
            ranges += start until (start + term.length)
            index = start + term.length
        }
    }
    // 合并重叠/相邻区间，避免嵌套 SpanStyle
    val merged = ranges.sortedBy { it.first }
        .fold(mutableListOf<IntRange>()) { acc, r ->
            if (acc.isEmpty() || r.first > acc.last().last + 1) {
                acc.add(r)
            } else {
                acc[acc.lastIndex] = acc.last().first..maxOf(acc.last().last, r.last)
            }
            acc
        }

    var cursor = 0
    for (range in merged) {
        if (range.first > cursor) append(text.substring(cursor, range.first))
        withStyle(SpanStyle(background = Color(0xFFFFEB3B).copy(alpha = 0.5f))) {
            append(text.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/**
 * 知识库混合/语义/关键词检索：内联摘要显示命中数 + 来源文档名，详情展示来源列表。
 */
object KnowledgeSearchToolUI : ToolUIRenderer {
    override val toolName: String = "kb_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.BookOpen01

    @Composable
    override fun title(context: ToolUIContext): String {
        val query = context.arguments.getStringContent("query")
        return if (query.isNullOrBlank()) {
            "知识库检索"
        } else {
            "知识库检索：$query"
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = toolOutputText(context).isNotBlank()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val parsed = context.rememberParsed()
        val countText = parsed.count?.let { "检索到 $it 个来源" } ?: "知识库检索完成"
        // 多个 Text 必须包在布局容器里（Summary 渲染在 Box 内，Box 的 children 是叠加的，不包会重叠）
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = countText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
            )
            if (parsed.sources.isNotEmpty()) {
                Text(
                    text = parsed.sources.joinToString("、") { it.first },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = toolOutputText(context)
        if (text.isBlank()) {
            DefaultToolPreview(context = context)
            return
        }
        val parsed = context.rememberParsed()
        // 检索参数（AI 实际用的检索词 / 模式 / topK / 权重 / 库数）
        val argRows = remember(context.tool) { argumentRows(context.arguments) }
        // 高亮关键词：取模型传给 kb_search 的 query（与检索测试一致，按空白分词）
        val highlightTerms = (context.arguments as? JsonObject)
            ?.get("query")?.jsonPrimitive?.contentOrNull.orEmpty()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "检索来源",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (argRows.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    argRows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (parsed.details.isEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            } else {
                parsed.details.forEach { detail ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = detail.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (detail.score.isNotBlank()) {
                                Text(
                                    text = detail.score,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (detail.snippet.isNotBlank()) {
                            Text(
                                text = buildAnnotatedString { highlightQuery(detail.snippet, highlightTerms) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 知识库列表（kb_list）：内联摘要显示可用知识库名，详情展示名称 + 描述。
 */
object KnowledgeListToolUI : ToolUIRenderer {
    override val toolName: String = "kb_list"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Database01

    @Composable
    override fun title(context: ToolUIContext): String = "知识库列表"

    /** 输出形如 `Available knowledge bases: [{"id":..,"name":..,"description":..},...]` */
    private fun baseList(context: ToolUIContext): JsonArray? {
        val text = toolOutputText(context)
        if (text.isBlank()) return null
        val jsonPart = text.substringAfter("Available knowledge bases: ", "")
        return runCatching {
            JsonInstant.parseToJsonElement(jsonPart).jsonArray
        }.getOrNull()
    }

    private fun baseName(base: JsonObject): String =
        base["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: base["id"]?.jsonPrimitive?.contentOrNull
            ?: "未知知识库"

    override fun hasSummary(context: ToolUIContext): Boolean = baseList(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val bases = remember(context.tool) { baseList(context) }
        val count = bases?.size ?: 0
        // 多个 Text 必须包在布局容器里（Summary 渲染在 Box 内，不包会重叠）
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "可用知识库 $count 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
            )
            if (count > 0) {
                val names = bases!!.mapNotNull { (it as? JsonObject)?.let(::baseName) }
                Text(
                    text = names.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val bases = remember(context.tool) { baseList(context) }
        if (bases == null || bases.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "知识库列表",
                style = MaterialTheme.typography.headlineSmall,
            )
            bases.forEach { element ->
                val base = element as? JsonObject ?: return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = baseName(base),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    base["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
