package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
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
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, context.tool.toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = if (part.text.length > 1_000_000) {
                                    // 超大输出跳过 JSON 美化，避免构建整棵 JsonElement 内存翻倍 OOM
                                    part.text
                                } else {
                                    runCatching {
                                        JsonInstantPretty.encodeToString(
                                            JsonInstant.parseToJsonElement(part.text)
                                        )
                                    }.getOrElse { part.text }
                                },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
