package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.content.Intent
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.time.Clock

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

/** Markdown 实时解析的最大文本长度；超过则降级为纯文本，避免流式期间每 chunk 全量重解析。 */
private const val MARKDOWN_PARSE_MAX_CHARS = 100_000

private val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

// 预处理markdown内容
private fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            val formula = matchResult.groupValues[1]
                .trim()
                .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
            "$$" + formula + "$$"
        }
    }

    return result
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MarkdownBlock(
                    content = "Hi there!", modifier = Modifier.background(Color.Red)
                )
                MarkdownBlock(
                    content = """
                    ### 🌍 This is Markdown Test This Markdown Test
                    1. How many roads must a man walk down
                        * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                        * by opposing end them.
                            * How many times must a man look up, Before he can see the sky?
                            * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                    2. How many times must a man look up, Before he can see the sky?

                    * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                    * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                    4. For in that sleep of death what dreams may come [citation](1)

                    This is Markdown Test, This <br/> is Markdown Test.
                    ha<br/>ha

                    ***
                    This is Markdown Test, This is Markdown Test.

                    | Name | Age | Address | Email | Job | Homepage |
                    | ---- | --- | ------- | ----- | --- | -------- |
                    | John | 25  | New York | john@example.com | Software Engineer | john.com |
                    | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                    ## HTML Escaping
                    This is a &gt;  test
                """.trimIndent()
                )
            }
        }
    }
}

private data class MarkdownParseResult(
    val preprocessed: String,
    val astTree: ASTNode,
    val hasHtml: Boolean,
)

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

private fun parseMarkdown(content: String): MarkdownParseResult {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtml())
}

/**
 * 进程级 markdown 解析结果缓存（LRU，线程安全）。
 * 快速滚动时多条消息同时首次进入视口、在主线程同步解析 AST（IntelliJ 解析器单次 3-26ms）
 * 是掉帧的直接原因。此缓存配合 [warmMarkdownCache]（ChatList 滚动时预取即将进入视口的消息）
 * 让消息真正进入视口时命中缓存、不再阻塞主线程，且无渲染变化。
 * sizeOf 以文本长度近似 AST 占用（与内容长度正相关），maxSize 单位 KB。
 */
private val markdownParseCache = object : LruCache<String, MarkdownParseResult>(4 * 1024) {
    override fun sizeOf(key: String, value: MarkdownParseResult): Int {
        return (key.length + value.preprocessed.length) / 1024 + 1
    }
}

/** markdown / LaTeX 语法起始字符：含任一字符的文本需走 AST 解析；纯文本跳过解析以省固定开销 */
private val MARKDOWN_SYNTAX_CHARS = "`*_#[]()!|~\\\$<>+-=:"

/** 是否含 markdown/LaTeX 语法起始符（决定是否需要 AST 解析） */
private fun String.containsMarkdownSyntax(): Boolean = any { it in MARKDOWN_SYNTAX_CHARS }

/** 预解析指定文本并写入缓存（供聊天列表滚动时预取即将进入视口的消息 markdown）；
 * 返回 true 表示该文本含 HTML（调用方可据此继续预热 MarkdownNew 的 HTML 生成缓存） */
internal fun warmMarkdownCache(content: String): Boolean {
    if (content.length > MARKDOWN_PARSE_MAX_CHARS) return false
    val result = markdownParseCache.get(content) ?: parseMarkdown(content).also { markdownParseCache.put(content, it) }
    return result.hasHtml
}

/**
 * 内部笔记链接前缀：双链/内部链接预处理成 `rikkahub-note/目标` 的相对路径形式（不是 `note:` 协议），
 * 因为 Markdown 解析器开 `useSafeLinks` 会过滤非标准协议链接。由 [LinkClickHandler] 接管跳转。
 */
private const val NOTE_LINK_PREFIX = "rikkahub-note/"

/** 链接点击处理器；返回 true 表示已处理（拦截默认的系统浏览器打开） */
fun interface LinkClickHandler {
    fun onLinkClick(dest: String): Boolean
}

private val LocalLinkClick = staticCompositionLocalOf<LinkClickHandler?> { null }

/** 任务列表点击回调：参数为任务行完整文本（含 `- [ ]` 前缀），由上层翻转勾选并保存 */
private val LocalOnToggleTask = staticCompositionLocalOf<((String) -> Unit)?> { null }

@OptIn(FlowPreview::class)
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    onLinkClick: LinkClickHandler? = null,
    onToggleTask: ((String) -> Unit)? = null,
) {
    // 超大文本降级为纯文本：流式期间每 chunk 都全量重解析成本过高，极端长文本不做实时解析。
    // 阈值以下保留正常 Markdown 渲染。
    if (content.length > MARKDOWN_PARSE_MAX_CHARS) {
        Text(
            text = content,
            modifier = modifier,
            style = style,
        )
        return
    }

    // 纯文本快速路径：不含 markdown/LaTeX 语法起始符则直接渲染文本，
    // 跳过 AST 解析（IntelliJ parser 有固定 3-5ms 开销，纯文本消息无需解析，
    // 避免快速滚动时每条纯文本消息首显都带一次固定卡顿）
    if (!content.containsMarkdownSyntax()) {
        ProvideTextStyle(style) {
            Text(
                text = content,
                modifier = modifier.padding(horizontal = 4.dp),
            )
        }
        return
    }

    // 首帧优先用进程级缓存（滚动预取已写入则直接命中，不阻塞主线程）；未命中时同步解析并缓存
    var (data, setData) = remember {
        mutableStateOf(markdownParseCache.get(content) ?: parseMarkdown(content).also { markdownParseCache.put(content, it) })
    }

    // 监听内容变化，重新解析AST树
    // 后台线程解析AST树防掉帧。流式输出每 chunk 都变一次 content，去掉 debounce：
    // 每 chunk 即时解析即时上屏，避免「冻结→停顿后整段蹦出」的卡顿体验。
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .mapLatest { parseMarkdown(it) }
            .catch { exception -> exception.printStackTrace() }
            .flowOn(Dispatchers.Default)
            .collect { setData(it) }
    }

    CompositionLocalProvider(
        LocalLinkClick provides onLinkClick,
        LocalOnToggleTask provides onToggleTask,
    ) {
        // 需要内部笔记链接跳转（onLinkClick 非空）时强制走 MarkdownNode 路径：
        // MarkdownNew 走 LinkAnnotation，不支持 note 链接回调；HTML 内容降级用 MarkdownNode 渲染。
        if (data.hasHtml && onLinkClick == null) {
            MarkdownNew(
                content = content,
                modifier = modifier,
                style = style,
                onClickCitation = onClickCitation,
            )
        } else {
            ProvideTextStyle(style) {
                Column(
                    modifier = modifier.padding(horizontal = 4.dp)
                ) {
                    data.astTree.children.fastForEach { child ->
                        MarkdownNode(
                            node = child, content = data.preprocessed, onClickCitation = onClickCitation
                        )
                    }
                }
            }
        }
    }
}

object HeaderStyle {
    private const val LINE_HEIGHT_RATIO = 1.25f

    fun fromLevel(level: Int, fontSizeRatio: Float): TextStyle {
        val fontSize = when (level) {
            1 -> 24.sp
            2 -> 22.sp
            3 -> 20.sp
            4 -> 18.sp
            5 -> 16.sp
            else -> 14.sp
        } * fontSizeRatio

        return TextStyle(
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT_RATIO,
        )
    }

    fun verticalPadding(level: Int) = when (level) {
        1 -> 16.dp
        2 -> 14.dp
        3 -> 12.dp
        4 -> 10.dp
        5 -> 8.dp
        else -> 6.dp
    }

    fun fromMarkdownType(type: IElementType, fontSizeRatio: Float): TextStyle = fromLevel(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> 6
        },
        fontSizeRatio = fontSizeRatio,
    )

    fun verticalPadding(type: IElementType) = verticalPadding(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            else -> 6
        }
    )
}

@Composable
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }

        // 段落
        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(
                node = node, content = content, modifier = modifier, onClickCitation = onClickCitation
            )
        }

        // 标题
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style = HeaderStyle.fromMarkdownType(
                type = node.type,
                fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio,
            )
            val headingPadding = HeaderStyle.verticalPadding(node.type)
            ProvideTextStyle(value = LocalTextStyle.current.merge(style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = headingPadding),
                                trim = true,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        // Checkbox（Obsidian 风格：圆角方块，勾选填主色；可点击切换勾选状态）。
        // 乐观勾选：点击瞬间本地翻转显示，不等后台重渲染（源文本更新后由解析结果对齐）。
        GFMTokenTypes.CHECK_BOX -> {
            val parsedChecked = node.getTextInNode(content).trim() in listOf("[x]", "[X]")
            val onToggle = LocalOnToggleTask.current
            var optimisticChecked by remember(parsedChecked) { mutableStateOf(parsedChecked) }
            // 任务行完整文本（含 `- [ ]` 前缀），供上层在源文本里定位并翻转
            val lineStart = content.lastIndexOf('\n', (node.startOffset - 1).coerceAtLeast(0)) + 1
            val lineEndRaw = content.indexOf('\n', node.endOffset)
            val lineEnd = if (lineEndRaw == -1) content.length else lineEndRaw
            val taskLine = content.substring(lineStart, lineEnd)
            Box(
                modifier = modifier
                    .padding(top = 2.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (optimisticChecked) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .border(
                        width = 1.dp,
                        color = if (optimisticChecked) Color.Transparent else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .then(
                        if (onToggle != null) Modifier.clickable {
                            optimisticChecked = !optimisticChecked
                            onToggle(taskLine)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (optimisticChecked) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        // 引用块（含 Obsidian callout）
        MarkdownElementTypes.BLOCK_QUOTE -> {
            val callout = tryParseCallout(node, content)
            if (callout != null) {
                CalloutCard(
                    type = callout.type,
                    title = callout.title,
                    collapsed = callout.collapsed,
                ) {
                    // IntelliJ GFM 把 `> ` 前缀解析成嵌套节点，内容取整块文本剩余行、去掉 `>` 前缀，
                    // 用纯文本渲染（callout 内容多数为简单段落/列表）
                    val remaining = node.getTextInNode(content)
                        .lineSequence()
                        .drop(1)
                        .joinToString("\n") { line -> line.trim().removePrefix(">").trim() }
                    if (remaining.isNotBlank()) {
                        Text(remaining, style = LocalTextStyle.current)
                    }
                }
            } else {
                ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                    val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = bgColor, size = size
                                )
                                drawRect(
                                    color = borderColor, size = Size(10f, size.height)
                                )
                            }
                            .padding(8.dp)) {
                        node.children.fastForEach { child ->
                            MarkdownNode(
                                node = child, content = content, onClickCitation = onClickCitation
                            )
                        }
                    }
                }
            }
        }

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)?.getTextInNode(content)
                ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            // 在组合上下文读取链接处理器，供点击回调使用（clickable 的 onClick 非组合上下文）
            val handler = LocalLinkClick.current
            // workspace 链接：组合时解析 + 捕获预览/跳转入口（clickable 回调非组合上下文）
            val wsResolved = resolveWorkspaceImage(linkDest)
            val openWsPreview = LocalOpenWorkspaceImagePreview.current
            val openWorkspaceFile = LocalOpenWorkspaceFile.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = modifier.clickable {
                    // workspace 链接：应用内打开图片预览
                    val wsUrl = wsResolved
                    if (wsUrl != null) {
                        openWsPreview(wsUrl)
                        return@clickable
                    }
                    // workspace 非图片链接：应用内打开文件/定位目录，不走系统浏览器
                    if (isWorkspaceLink(linkDest)) {
                        openWorkspaceFile(linkDest)
                        return@clickable
                    }
                    // note: 前缀的内部笔记链接（双链/内部路径预处理产物）交给回调接管，而非系统浏览器
                    val internal = linkDest.removePrefix(NOTE_LINK_PREFIX).takeIf { it != linkDest }
                    if (internal != null && handler != null && handler.onLinkClick(internal)) {
                        return@clickable
                    }
                    val intent = Intent(Intent.ACTION_VIEW, linkDest.toUri())
                    context.startActivity(intent)
                })
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        // GFM 特殊元素
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val rawUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            // 工作区图片约定：AI 正文写 ![描述](/workspace/相对路径)，解析成沙箱内实际文件显示；
            // 非工作区路径或解析失败保持原样（走 Coil 默认加载逻辑）
            val imageUrl = resolveWorkspaceImage(rawUrl) ?: rawUrl
            Column(
                modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 这里可以使用Coil等图片加载库加载图片
                ZoomableAsyncImage(
                    model = imageUrl,
                    contentDescription = altText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            }
        }

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathInline(
                    formula, modifier = modifier.padding(horizontal = 1.dp),
                    fontSize = LocalTextStyle.current.fontSize
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier.padding(horizontal = 1.dp)
                )
            }
        }

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathBlock(
                    formula, modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    fontSize = LocalTextStyle.current.fontSize
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code, fontFamily = JetbrainsMono, modifier = modifier
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            Text(
                text = code,
                modifier = modifier,
            )
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
            // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val code = content.substring(
                codeContentStartOffset, codeContentEndOffset
            ).trimIndent()

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val hasEnd = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null

            HighlightCodeBlock(
                code = code,
                language = language,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                completeCodeBlock = hasEnd
            )
        }

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
            SimpleHtmlBlock(
                html = text, modifier = modifier
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }
    }
}

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier.padding(start = (level * 8).dp)
    ) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level
                )
            }
        }
    }
}

@Composable
private fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode, content: String, bulletText: String, onClickCitation: (String) -> Unit = {}, level: Int
) {
    Column {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    directContent.fastForEach { contentChild ->
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            onClickCitation = onClickCitation,
                            listLevel = level,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList, content = content, onClickCitation = onClickCitation, listLevel = level + 1 // 增加层级
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
private fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
) {
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, onClickCitation = onClickCitation
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
    }
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val latexColorArgb = LocalContentColor.current.toArgb()
    // 链接点击处理器（MarkdownBlock 注入）：双链/内部笔记链接跳转等；
    // workspace 链接（/workspace/ 或 workspace://）应用内打开图片预览（链接分支通过 onLinkClick 回调进来）。
    // CompositionLocal 需在组合上下文读取，remember 只缓存闭包
    val wsResolver = LocalWorkspaceImageResolver.current
    val openWsPreview = LocalOpenWorkspaceImagePreview.current
    val openWorkspaceFile = LocalOpenWorkspaceFile.current
    val noteHandler = LocalLinkClick.current
    val linkHandler: ((String) -> Boolean)? = remember(wsResolver, openWsPreview, openWorkspaceFile, noteHandler) {
        { dest: String ->
            val wsUrl = resolveWorkspaceImage(dest, wsResolver)
            if (wsUrl != null) {
                openWsPreview(wsUrl)
                true
            } else if (isWorkspaceLink(dest)) {
                // workspace 非图片链接：应用内打开文件/定位目录，不走系统浏览器
                openWorkspaceFile(dest)
                true
            } else {
                noteHandler?.onLinkClick(dest) ?: false
            }
        }
    }
    // 段落是否含可点击链接：决定是否给整段 Text 挂 clickable，
    // 否则 AnnotatedString 里的 LinkAnnotation 不响应点击（链接点了没反应）。
    val hasClickableLink = remember(node) {
        node.findChildOfTypeRecursive(
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.AUTOLINK,
            GFMTokenTypes.GFM_AUTOLINK,
        ) != null
    }
    val annotatedString = remember(content, enableLatexRendering, latexColorArgb, linkHandler) {
        buildAnnotatedString {
            node.children.fastForEach { child ->
                appendMarkdownNodeContent(
                    node = child,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    onClickCitation = onClickCitation,
                    style = textStyle,
                    density = density,
                    trim = trim,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onLinkClick = linkHandler,
                )
            }
        }
    }
    // 普通段落（无图片/块级数学）直接渲染单 Text：FlowRow 对单个 child 是多于的布局开销
    // （FlowRow 的测量是 O(n²) 换行计算），长 markdown 消息会渲染大量段落、测量成本累加是滚动掉帧主因。
    Text(
        text = annotatedString,
        // 段落含链接时挂 clickable（无波纹）：让 LinkAnnotation 可点击。
        // URL 链接自动打开浏览器，note 链接走上面 Clickable 的回调 onLinkClick 跳转。
        modifier = modifier.then(
            if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
            else Modifier
        ).then(
            if (hasClickableLink) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
            } else {
                Modifier
            }
        ),
        inlineContent = inlineContents,
        softWrap = true,
        overflow = TextOverflow.Visible,
        style = LocalTextStyle.current.copy(
            lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
        )
    )
}

@Composable
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    // 提取表格的标题行和数据行
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }

    // 计算列数（从标题行获取）
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    // 检查是否有足够的列来显示表格
    if (columnCount == 0) return

    // 提取表头单元格文本
    val headerCells =
        headerNode?.children?.filter { it.type == GFMTokenTypes.CELL }?.map { it.getTextInNode(content).trim() }
            ?: emptyList()

    // 提取所有行的数据
    val rows = rowNodes.map { rowNode ->
        rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
    }

    // 创建表头composable列表
    val headers = List(columnCount) { columnIndex ->
        @Composable {
            MarkdownBlock(
                content = if (columnIndex < headerCells.size) headerCells[columnIndex] else "",
                // 透传链接处理器：否则内层 MarkdownBlock 用 LocalLinkClick=null 覆盖外层，单元格链接点不动
                onLinkClick = LocalLinkClick.current,
            )
        }
    }

    // 创建行数据composable列表
    val rowComposables = rows.map { rowData ->
        List(columnCount) { columnIndex ->
            @Composable {
                MarkdownBlock(
                    content = if (columnIndex < rowData.size) rowData[columnIndex] else "",
                    onLinkClick = LocalLinkClick.current,
                )
            }
        }
    }

    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 表格原始markdown文本（用于复制）和CSV内容（用于下载）
    val tableMarkdown = remember(node, content) { node.getTextInNode(content).trim() }
    val tableCsv = remember(headerCells, rows) { buildTableCsv(headerCells, rows) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 渲染表格卡片（工具栏 + 表格）
    Column(
        modifier = modifier
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表格",
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableMarkdown)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            createDocumentLauncher.launch(
                                "table_${
                                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                }.csv"
                            )
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        DataTable(
            headers = headers,
            rows = rowComposables,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(headerCells: List<String>, rows: List<List<String>>): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(headerCells.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean = true,
    latexColorArgb: Int = 0,
    onClickCitation: (String) -> Unit = {},
    onLinkClick: ((String) -> Boolean)? = null,
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(LinkAnnotation.Url(link)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).let {
                if (trim) {
                    it.trim()
                } else {
                    it
                }.replace(BREAK_LINE_REGEX, "\n")
            }
            appendObsidianInline(text, colorScheme)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation,
                        onLinkClick = onLinkClick
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation,
                        onLinkClick = onLinkClick
                    )
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation,
                        onLinkClick = onLinkClick
                    )
                }
            }
        }

        // 短引用链接：`[^id]` 脚注引用渲染成蓝色上标序号（去掉 `[^`、`]`，右上角小字）；其它原样保留方括号
        node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val label = node.getTextInNode(content)
            if (label.startsWith("[^")) {
                val id = label.removePrefix("[^").removeSuffix("]")
                withStyle(
                    SpanStyle(
                        color = colorScheme.primary,
                        fontSize = 9.sp,
                        baselineShift = BaselineShift.Superscript,
                    )
                ) {
                    append(id)
                }
            } else {
                append(label)
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    inlineContents.putIfAbsent(
                        "citation:$linkDest", InlineTextContent(
                            placeholder = Placeholder(
                                width = (domain.length * 7).sp,
                                height = 1.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ), children = {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            onClickCitation(id.trim())
                                        }
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                    contentAlignment = Alignment.Center) {
                                    Text(
                                        text = domain,
                                        modifier = Modifier.wrapContentSize(),
                                        style = TextStyle(
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            fontFamily = JetbrainsMono,
                                            color = colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Thin
                                        ),
                                    )
                                }
                            })
                    )
                    appendInlineContent("citation:$linkDest")
                }
            } else if (linkDest.startsWith(NOTE_LINK_PREFIX) && onLinkClick != null) {
                // 内部笔记链接（双链/内部路径预处理产物）：用 Clickable 链接回调 onLinkClick 跳转。
                // 需配合段落 Text 的 clickable modifier，否则 LinkAnnotation 不响应点击。
                val target = linkDest.removePrefix(NOTE_LINK_PREFIX)
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "note:$target",
                        linkInteractionListener = { onLinkClick(target) },
                    )
                ) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            } else if (isWorkspaceLink(linkDest) && onLinkClick != null) {
                // workspace 链接（/workspace/ 或 workspace://）：用 Clickable 链接回调 onLinkClick，
                // 由 Paragraph 注入的处理器解析成 file:// 后应用内预览。
                // 需配合段落 Text 的 clickable modifier，否则 LinkAnnotation 不响应点击。
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "ws:$linkDest",
                        linkInteractionListener = { onLinkClick(linkDest) },
                    )
                ) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            } else {
                withLink(LinkAnnotation.Url(linkDest)) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                withLink(LinkAnnotation.Url(link.getTextInNode(content))) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.getTextInNode(content))
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = JetbrainsMono,
                    fontSize = 0.9.em,
                    color = colorScheme.primary,
                )
            ) {
                append(' ')
                append(code)
                append(' ')
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            if (enableLatexRendering) {
                val fontSizePx = with(density) { style.fontSize.toPx() }
                // 将过长的行内公式按顶层运算符水平拆分为多段，每段最大宽度限制为字号的两倍，
                // 使其能在文本流中换行，避免单体公式超出可用宽度被挤出屏幕
                val drawables = splitLatex(
                    latex = formula,
                    maxWidthPx = fontSizePx * 4f,
                    fontSize = fontSizePx,
                    color = latexColorArgb,
                )
                if (drawables.isEmpty()) {
                    // 拆分失败时回退为单体内联渲染
                    appendInlineContent(formula, "[Latex]")
                    val (width, height) = with(density) {
                        assumeLatexSize(
                            latex = formula, fontSize = fontSizePx
                        ).let {
                            it.width().toSp() to it.height().toSp()
                        }
                    }
                    inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                        placeholder = Placeholder(
                            width = width,
                            height = height,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                        ), children = {
                            MathInline(
                                latex = formula, modifier = Modifier
                            )
                        })
                    )
                } else {
                    drawables.forEachIndexed { index, drawable ->
                        // 段间插入零宽空格，提供换行点
                        if (index > 0) append('\u200B')
                        val key = "latex:${formula.hashCode()}:$index"
                        appendInlineContent(key, "[Latex]")
                        val (width, height) = with(density) {
                            drawable.bounds.width().toSp() to drawable.bounds.height().toSp()
                        }
                        inlineContents.putIfAbsent(
                            key, InlineTextContent(
                                placeholder = Placeholder(
                                    width = width,
                                    height = height,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                                ), children = {
                                    LatexDrawable(drawable = drawable)
                                })
                        )
                    }
                }
            } else {
                // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 0.95.em,
                    )
                ) {
                    append(formula)
                }
            }
        }

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation,
                    onLinkClick = onLinkClick
                )
            }
        }
    }
}

private fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.getTextInNode(text: String, type: IElementType): String {
    var startOffset = -1
    var endOffset = -1
    children.fastForEach {
        if (it.type == type) {
            if (startOffset == -1) {
                startOffset = it.startOffset
            }
            endOffset = it.endOffset
        }
    }
    if (startOffset == -1 || endOffset == -1) {
        return ""
    }
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun ASTNode.traverseChildren(
    action: (ASTNode) -> Unit
) {
    children.fastForEach { child ->
        action(child)
        child.traverseChildren(action)
    }
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    // 从头裁剪
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    // 从尾裁剪
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}

// ---- Obsidian 行内格式：==高亮== / #tag / [^id] 脚注引用 ----

/** 高亮底色（Obsidian 风格淡黄，半透明） */
private val HIGHLIGHT_BG = Color(0x66FFEB3B)

/** 行内 Obsidian 格式：`==高亮==`、`#tag`（含层级 `/`，排除 `#1`）、`[^id]` 脚注引用 */
private val OBSIDIAN_INLINE_REGEX = Regex(
    """(==.+?==|(?<![\w#])#(?![0-9])[\p{L}\p{N}_][\p{L}\p{N}_/-]*|\[\^[^\]]+\])"""
)

/** 在纯文本叶子中识别 Obsidian 行内格式并加对应样式（普通段直接追加） */
private fun AnnotatedString.Builder.appendObsidianInline(
    text: String,
    colorScheme: ColorScheme,
) {
    var last = 0
    for (m in OBSIDIAN_INLINE_REGEX.findAll(text)) {
        append(text, last, m.range.first)
        val token = m.value
        when {
            token.length >= 4 && token.startsWith("==") && token.endsWith("==") -> {
                // ==高亮==
                withStyle(SpanStyle(background = HIGHLIGHT_BG)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("[^") -> {
                // 脚注引用 [^id]：渲染为上标序号（去掉 `[^`、`]` 只留数字，右上角小字）
                val id = token.removePrefix("[^").removeSuffix("]")
                withStyle(
                    SpanStyle(
                        color = colorScheme.primary,
                        fontSize = 9.sp,
                        baselineShift = BaselineShift.Superscript,
                    )
                ) {
                    append(id)
                }
            }
            token.startsWith("#") -> {
                // #tag 徽章（底色 + 主题色文字）
                withStyle(
                    SpanStyle(
                        background = colorScheme.primaryContainer,
                        color = colorScheme.primary,
                    )
                ) {
                    append(token)
                }
            }
            else -> append(token)
        }
        last = m.range.last + 1
    }
    append(text, last, text.length)
}

// ---- Obsidian callout 头解析 ----

/** callout 头：`[!type]`、`[!type]-`（默认折叠）、`[!type]+`、`[!type] 标题` */
private val CALLOUT_HEADER_REGEX = Regex("""\[!([^\]]+)\]\s*([+-]?)\s*(.*)""")

private data class ParsedCallout(
    val type: String,
    val title: String,
    val collapsed: Boolean,
)

/** 解析引用块首行是否为 Obsidian callout 头（`> [!type]`，可带折叠标记/标题） */
private fun tryParseCallout(node: ASTNode, content: String): ParsedCallout? {
    // IntelliJ GFM 把 `> ` 前缀解析成嵌套 BLOCK_QUOTE 节点，实际头行文本是 `> [!type] ...`
    val firstLine = node.getTextInNode(content).lineSequence().first().trim()
    val cleaned = firstLine.removePrefix(">").trim()
    val m = CALLOUT_HEADER_REGEX.matchEntire(cleaned) ?: return null
    val type = m.groupValues[1].trim()
    if (type.isEmpty()) return null
    val title = m.groupValues[3].trim().ifEmpty {
        type.replaceFirstChar { it.uppercase() }
    }
    return ParsedCallout(
        type = type,
        title = title,
        collapsed = m.groupValues[2] == "-",
    )
}
