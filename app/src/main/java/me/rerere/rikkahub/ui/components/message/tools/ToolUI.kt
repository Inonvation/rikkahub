package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
)

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)

    /**
     * 该工具调用是否处于"执行中"（驱动闪烁/加载动画）。
     * 默认用「整体生成中 && 工具未回填」；渲染器可覆盖（如子代理用任务的真实状态，
     * 避免并行执行时"已完成但整体还在生成"被误判为执行中而闪烁）。
     */
    @Composable
    fun isLoading(context: ToolUIContext, defaultLoading: Boolean): Boolean = defaultLoading

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /**
     * 展开后的内联摘要是否可直接点击查看详情（BottomSheet JSON）。
     * 为 true 时摘要区整块可点，且不再显示"查看完整详情"链接（如 todo 进度"x/n 已完成"）。
     */
    val summaryClickable: Boolean
        get() = false

    /**
     * 折叠行标题下方的一行辅助信息（始终可见，折叠/展开都显示）。
     * 返回 null 则不渲染该行。默认不渲染；子代理完成气泡用它展示 token 用量与耗时，
     * 以 composable 形式返回，可直接复用矢量图标（对齐主聊天区的 NerdLine 风格）。
     */
    @Composable
    fun subtitle(context: ToolUIContext): (@Composable () -> Unit)? = null

    /** 即使工具未执行（output 为空，如子代理执行中）也允许点击打开详情。 */
    val alwaysOpenPreview: Boolean
        get() = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        JavascriptToolUI,
        HtmlToMarkdownToolUI,
        GetTimeInfoToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        UseSkillToolUI,
        RecentChatsToolUI,
        ConversationSearchToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        ShellToolUI,
        TodoWriteToolUI,
        StudyStatsToolUI,
        StudyMindmapToolUI,
        StudySummaryToolUI,
        StudySearchToolUI,
        StudyUpdateVocabularyToolUI,
        StudyUpdateNoteToolUI,
        StudyUpdateWrongQuestionToolUI,
        StudyUpdateKnowledgeCardToolUI,
        StudyDeleteVocabularyToolUI,
        StudyDeleteNoteToolUI,
        StudyDeleteWrongQuestionToolUI,
        StudyDeleteKnowledgeCardToolUI,
        SubAgentToolUI,
        SubAgentCompletedToolUI,
        GuidanceToolUI,
        TrustedFolderListToolUI,
        TrustedFolderReadToolUI,
        TrustedFolderSearchToolUI,
        TrustedFolderWriteToolUI,
        TrustedFolderCreateFolderToolUI,
        TrustedFolderEditToolUI,
        TrustedFolderRenameToolUI,
        TrustedFolderMoveToolUI,
        TrustedFolderDeleteToolUI,
        TrustedFolderCheckLinksToolUI,
        KnowledgeSearchToolUI,
        KnowledgeListToolUI,
    ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器。
     *  精确匹配失败时回退到 `toolName.substringBefore("__")` 前缀再查一次——
     *  支持每服务商独立工具（search_web__{id} / scrape_web__{id}）复用基础渲染器。
     *  安全性：未注册的 `mcp__{server}__{tool}` 前缀是 "mcp"（不在注册表内）→ 仍走默认。 */
    fun resolve(toolName: String): ToolUIRenderer =
        renderers[toolName]
            ?: toolName.substringBefore("__").let { prefix -> renderers[prefix] }
            ?: DefaultToolUIRenderer
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

/** 工具入参里读取整数字段（缺省/非数字返回 null） */
internal fun JsonElement?.getJsonInt(key: String): Int? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull

/** 工具入参里读取长整数字段（缺省/非数字返回 null） */
internal fun JsonElement?.getJsonLong(key: String): Long? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.longOrNull

/**
 * 默认工具详情: 上半部「调用工具」(标题 + 工具名 + 入参)，下半部「调用结果」。
 * 用 LazyColumn 懒加载解决大结果卡顿；短字段横排减少空白。
 * 长内容用 [MarkdownBlock] 完整渲染，不截断。
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 调用工具 ──────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.chat_message_tool_call_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    headerActions?.invoke()
                }
                Text(
                    text = context.tool.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JsonFieldView(
                    remember(context.arguments) {
                        runCatching { JsonInstant.parseToJsonElement(context.arguments.toString()) }
                            .getOrNull()
                    }
                )
            }
        }

        // ── 调用结果 ──────────────────────────────
        if (context.tool.output.isNotEmpty()) {
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Text(
                    text = stringResource(R.string.chat_message_tool_call_result),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            context.tool.output.forEachIndexed { partIndex, part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        val parsed = if (part.text.length > 1_000_000) {
                            // 超大输出跳过 JSON 解析，避免构建整棵 JsonElement 内存翻倍 OOM
                            null
                        } else {
                            runCatching { JsonInstant.parseToJsonElement(part.text) }
                                .getOrNull()
                        }
                        when (parsed) {
                            // 数组输出：每个元素一个懒加载项（卡片式）
                            is JsonArray -> {
                                items(parsed.size, key = { index -> "part${partIndex}_item$index" }) { index ->
                                    JsonArrayItemCard(parsed[index])
                                }
                            }
                            // 对象/单值/JSON 文本：结构化展示
                            is JsonObject, is JsonPrimitive -> item {
                                JsonFieldView(parsed)
                            }
                            // 解析失败（含超大输出）：纯文本直接 markdown 渲染，不丢弃
                            null -> item {
                                MarkdownBlock(
                                    content = part.text,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    is UIMessagePart.Image -> item {
                        ZoomableAsyncImage(
                            model = part.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> {}
                }
            }
        }
    }
}

/** 数组里的单个元素：卡片式展示，标题/链接突出，短值横排、长文本折叠 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JsonArrayItemCard(element: JsonElement) {
    val obj = element as? JsonObject
    if (obj == null) {
        // 数组里不是对象的元素（纯值/嵌套数组）直接用结构化展示
        JsonFieldView(element)
        return
    }

    val title = obj["title"]?.jsonPrimitive?.asText()
        ?: obj["name"]?.jsonPrimitive?.asText()
        ?: obj["query"]?.jsonPrimitive?.asText()
    val url = obj["url"]?.jsonPrimitive?.asText()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (url != null) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 剩余字段：短值横排；长文本折叠；嵌套对象/数组递归展示
            val titleKeys = setOf("title", "name", "query", "url")
            val short = mutableListOf<Map.Entry<String, JsonElement>>()
            val longTexts = mutableListOf<Map.Entry<String, JsonElement>>()
            val nested = mutableListOf<Map.Entry<String, JsonElement>>()
            obj.entries.forEach { entry ->
                val key = entry.key
                val value = entry.value
                when {
                    key in titleKeys -> {}
                    value is JsonPrimitive && isShortValue(value) -> short.add(entry)
                    value is JsonPrimitive -> longTexts.add(entry)
                    else -> nested.add(entry)
                }
            }

            if (short.isNotEmpty()) {
                JsonShortFieldsRow(short)
            }
            longTexts.forEach { (key, value) ->
                val text = value.jsonPrimitive.asText()
                if (key != "content") {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                JsonCollapsibleContent(text)
            }
            nested.forEach { (key, value) ->
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JsonFieldView(value)
            }
        }
    }
}

/**
 * 长内容块：默认折叠成 4 行，点「展开」查看完整。
 * 惰性取前 4 行判断是否折叠；超大文本（>8KB）展开后放入固定高度滚动区，
 * 避免单次布局整段巨型文本导致卡顿。
 */
@Composable
private fun JsonCollapsibleContent(content: String) {
    var expanded by remember { mutableStateOf(false) }
    val lineSeq = remember(content) { content.lineSequence() }
    val preview = remember(content) { lineSeq.take(4).joinToString("\n") }
    val isLong = remember(content) { lineSeq.drop(4).any() }

    if (expanded && content.length > MAX_INLINE_CHARS) {
        // 超大文本：固定高度滚动查看，不做整段一次性布局
        Column {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = false },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.tool_ui_collapse),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Text(
        text = if (expanded) content else preview,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
    )
    if (isLong) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.tool_ui_collapse else R.string.tool_ui_expand
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 展开后仍内联渲染的内容上限：超过则改用固定高度滚动区 */
private const val MAX_INLINE_CHARS = 8 * 1024

/**
 * 工具入参/输出的结构化展示：
 * - JSON 对象/数组 → 按字段逐行渲染，字段名清晰标注
 * - 长字符串值（Markdown/HTML/文本）→ 用 [MarkdownBlock] 完整渲染，不截断
 * - 无法解析为 JSON 的纯文本 → 用 [MarkdownBlock] 直接渲染
 * [jsonElement] 解析失败（或超大输出）时为 null，退回纯文本渲染原始字符串。
 */
@Composable
private fun JsonFieldView(jsonElement: JsonElement?) {
    when (jsonElement) {
        null -> Unit
        is JsonObject -> JsonObjectFieldView(jsonElement)
        is JsonArray -> JsonArrayFieldView(jsonElement)
        is JsonPrimitive -> JsonValueView(jsonElement)
        is JsonNull -> Text("null", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 对象字段展示：
 * - 短值字段（≤40 字符的原始值）→ 横排成一行/多行，减少右侧空白
 * - 长文本、嵌套对象/数组 → 每个独占一行
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JsonObjectFieldView(obj: JsonObject) {
    val (shortFields, longFields) = obj.entries.partition { (_, value) ->
        value is JsonPrimitive && isShortValue(value as JsonPrimitive)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (shortFields.isNotEmpty()) {
            JsonShortFieldsRow(shortFields)
        }
        longFields.forEach { (key, value) ->
            JsonFieldRow(label = key, value = value)
        }
    }
}

/** 短值字段横排（FlowRow 自动换行） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JsonShortFieldsRow(fields: List<Map.Entry<String, JsonElement>>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        fields.forEach { (key, value) ->
            JsonFieldValueColumn(key = key, value = value as JsonPrimitive)
        }
    }
}

/** 判断是否为短值（可直接横排显示的原始值） */
private fun isShortValue(primitive: JsonPrimitive): Boolean {
    val text = primitive.asText()
    return text.length <= 40 && !text.contains('\n')
}

/** JsonPrimitive 统一的文本取值 */
private fun JsonPrimitive.asText(): String =
    contentOrNull ?: content

/** 横排单字段：label（字段名）+ value（短值） */
@Composable
private fun JsonFieldValueColumn(key: String, value: JsonPrimitive) {
    Column {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.asText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun JsonArrayFieldView(array: JsonArray) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        array.forEachIndexed { index, value ->
            JsonFieldRow(label = "[$index]", value = value)
        }
    }
}

/** 单个字段：label（字段名）+ value（值，按类型渲染） */
@Composable
private fun JsonFieldRow(label: String, value: JsonElement) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        when (value) {
            is JsonObject, is JsonArray -> JsonFieldView(value)
            is JsonPrimitive -> JsonValueView(value)
            is JsonNull -> Text(
                text = "null",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单个值：长文本用 MarkdownBlock 完整渲染，短文本直接展示 */
@Composable
private fun JsonValueView(primitive: JsonPrimitive) {
    val text = primitive.asText()
    if (primitive.isString && text.length > 120) {
        MarkdownBlock(
            content = text,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
