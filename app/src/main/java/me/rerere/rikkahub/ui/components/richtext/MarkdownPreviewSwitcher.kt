package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
import me.rerere.rikkahub.ui.pages.study.wrapBareLatex
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/** Markdown 预览模式 */
private enum class MarkdownPreviewMode {
    SOURCE,
    RENDER,
    STRUCTURE,
    HTML,
}

/** JSON 结构预览的最大文本长度，超过则提示过大、回退源码。 */
private const val JSON_STRUCTURE_MAX_CHARS = 2 * 1024 * 1024

/**
 * 渲染态 markdown 解析/排版的最大文本长度，超过则降级为惰性纯文本（LazyColumn 按行渲染）。
 * 与 MarkdownBlock 的解析降级阈值一致：超长文本一次性解析/排版会冻结主线程。
 */
private const val LARGE_RENDER_CHARS = 100_000

/**
 * Markdown「源码 / 渲染 / 结构」切换组件.
 *
 * 源码态 = 只读 monospace TextField（可传 [sourceEditable] 放开编辑）;
 * 渲染态 = 复用学习工具统一的 [StudyMarkdownBlock]（内部已做裸 LaTeX 包裹）;
 * 结构态 = 仅对 JSON 文件启用（[jsonStructure] = true）的树状预览，格式错误/超大文件有友好提示。
 * HTML 态 = 仅当 [htmlMode] = true 时启用（此时模式为「源码 / HTML」，不再显示渲染态），
 * 用 WebView 渲染 HTML 内容；[htmlBaseUrl] 传文件所在目录的绝对路径（file://）时，
 * HTML 内相对路径的图片/CSS/JS 可正常加载，否则回退到虚拟域名。
 * 切换即时生效, **默认源码态**（打开大文件不卡顿，避免先渲染）. [state] 由调用方持有,
 * 便于保留光标/编辑与后续保存. 传 [previewByDefault]=true 时默认进入渲染态（适合笔记预览场景）.
 */
@Composable
fun MarkdownPreviewSwitcher(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    sourceEditable: Boolean = false,
    jsonStructure: Boolean = false,
    previewByDefault: Boolean = false,
    htmlMode: Boolean = false,
    htmlBaseUrl: String? = null,
    onLinkClick: LinkClickHandler? = null,
    imagePathResolver: (suspend (String) -> String?)? = null,
    noteEmbedResolver: (suspend (String) -> String?)? = null,
    onToggleTask: ((String) -> Unit)? = null,
) {
    var mode by rememberSaveable {
        mutableStateOf(
            // HTML 模式默认源码态（WebView 渲染大文件可能卡）；其余保持原有默认行为
            if (previewByDefault && !htmlMode) MarkdownPreviewMode.RENDER else MarkdownPreviewMode.SOURCE
        )
    }
    val modes = when {
        htmlMode -> listOf(MarkdownPreviewMode.SOURCE, MarkdownPreviewMode.HTML)
        jsonStructure -> listOf(MarkdownPreviewMode.SOURCE, MarkdownPreviewMode.RENDER, MarkdownPreviewMode.STRUCTURE)
        else -> listOf(MarkdownPreviewMode.SOURCE, MarkdownPreviewMode.RENDER)
    }

    // 渲染预处理缓存：内容变化时后台重新计算并**保留旧值继续显示**（无感刷新），
    // 避免任务点击/编辑时整页转圈重算。仅在页面重新组合时重置为首帧转圈。
    val needsPreprocess = onLinkClick != null || imagePathResolver != null || noteEmbedResolver != null
    val raw = state.text.toString()
    var prepared by remember { mutableStateOf<String?>(null) }
    var preparedFrontmatter by remember { mutableStateOf<List<FrontmatterProperty>>(emptyList()) }
    // 图片路径解析结果缓存：内容小改动（如任务切换）重新预处理时，避免对每张图重复 IO 解析，显著加快
    val imageResolveCache = remember { android.util.LruCache<String, String?>(256) }
    val cachedImageResolver: (suspend (String) -> String?)? = imagePathResolver?.let { orig ->
        // 解析失败的图片（null）不缓存：LruCache.put 传 null 值会抛 NPE 崩溃
        { src: String -> imageResolveCache.get(src) ?: orig(src)?.also { imageResolveCache.put(src, it) } }
    }
    LaunchedEffect(raw, needsPreprocess) {
        if (needsPreprocess) {
            val (body, frontmatter) = withContext(Dispatchers.Default) {
                // Obsidian 预处理：frontmatter 解析（渲染属性面板）/ %%注释%% / 脚注 / ![[笔记嵌入]]，再走双链/图片转换
                val (props, rest) = parseFrontmatter(raw)
                var t = rest
                t = stripComments(t)
                // 行内脚注 `^[内容]` → `[^n]` 引用 + 脚注定义，统一由 extractFootnotes 提取到文末
                val (withInlineRefs, inlineDefs) = convertInlineFootnotes(t)
                t = withInlineRefs
                val (noFootnotes, footnotes) = extractFootnotes(t)
                t = expandNoteEmbeds(noFootnotes, noteEmbedResolver, expanding = emptySet(), depth = 0)
                // 脚注按编号从小到大排列（行内脚注 + 命名脚注混用时顺序可能乱）
                val allFootnotes = (inlineDefs + footnotes.lines())
                    .filter { it.isNotBlank() }
                    .sortedBy { footnoteNumber(it) }
                    .joinToString("\n")
                if (allFootnotes.isNotEmpty()) {
                    t = "$t\n\n---\n**脚注**\n\n$allFootnotes"
                }
                val converted = convertWikilinksToNoteLinks(t)
                val withImages = replaceImageSrcs(converted, cachedImageResolver)
                warmMarkdownCache(wrapBareLatex(withImages))
                withImages to props
            }
            prepared = body
            preparedFrontmatter = frontmatter
        } else {
            prepared = null
            preparedFrontmatter = emptyList()
        }
    }

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            modes.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { mode = item },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        when (item) {
                            MarkdownPreviewMode.SOURCE -> "源码"
                            MarkdownPreviewMode.RENDER -> "渲染"
                            MarkdownPreviewMode.STRUCTURE -> "结构"
                            MarkdownPreviewMode.HTML -> "HTML"
                        }
                    )
                }
            }
        }

        when (mode) {
            MarkdownPreviewMode.SOURCE -> TextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                readOnly = !sourceEditable,
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )

            MarkdownPreviewMode.RENDER -> {
                if (needsPreprocess) {
                    // 预处理结果已由顶层缓存（内容不变则跨模式切换复用），此处只消费
                    val content = prepared
                    if (content != null) {
                        if (content.length > LARGE_RENDER_CHARS) {
                            // 超大文本：惰性按行渲染，避免一次性排版整个大文本冻结主线程
                            val lines = content.lineSequence().toList()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                items(count = lines.size) { index ->
                                    Text(
                                        text = lines[index],
                                        style = LocalTextStyle.current,
                                        modifier = Modifier.padding(vertical = 1.dp),
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                            ) {
                                if (preparedFrontmatter.isNotEmpty()) {
                                    PropertiesPanel(preparedFrontmatter)
                                    Spacer(Modifier.height(8.dp))
                                }
                                StudyMarkdownBlock(content = content, onLinkClick = onLinkClick, onToggleTask = onToggleTask)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        StudyMarkdownBlock(content = raw)
                    }
                }
            }

            MarkdownPreviewMode.STRUCTURE -> JsonStructureView(content = state.text.toString())

            MarkdownPreviewMode.HTML -> {
                // 用 WebView 渲染 HTML 内容。baseUrl 指向文件所在目录时相对资源可加载；
                // 未提供时回退虚拟域名（仅渲染 HTML 本身）。
                val webViewState = rememberWebViewState(
                    data = raw,
                    baseUrl = htmlBaseUrl ?: WEB_VIEW_BASE_URL,
                    mimeType = "text/html",
                    encoding = "UTF-8",
                    settings = {
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = true
                        javaScriptEnabled = true
                        domStorageEnabled = true
                    }
                )
                val uriHandler = LocalUriHandler.current
                // 拦截 http/https 外链丢给系统浏览器，其余（锚点/相对路径/本地协议）留在 WebView 内导航
                val externalUrlHandler: ((String) -> Boolean)? = remember(uriHandler) {
                    { url ->
                        val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
                        if (parsed != null && (parsed.scheme == "http" || parsed.scheme == "https")) {
                            uriHandler.openUri(url)
                            true
                        } else {
                            false
                        }
                    }
                }
                WebView(
                    state = webViewState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    onOpenExternalUrl = externalUrlHandler,
                )
            }
        }
    }
}

/** JSON 结构视图：解析成功后用 JsonTree 渲染，格式错误或过大时给出友好提示。 */
@Composable
private fun JsonStructureView(content: String) {
    val json = remember(content) {
        if (content.length > JSON_STRUCTURE_MAX_CHARS) {
            JsonStructureResult.TooLarge
        } else {
            runCatching { Json.parseToJsonElement(content) }
                .fold(onSuccess = { JsonStructureResult.Parsed(it) }, onFailure = { JsonStructureResult.Invalid })
        }
    }

    when (json) {
        is JsonStructureResult.Parsed -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            JsonTree(json = json.element)
        }

        is JsonStructureResult.Invalid -> Text(
            text = "JSON 格式错误，无法显示结构。可切换回「源码」查看原文。",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )

        is JsonStructureResult.TooLarge -> Text(
            text = "文件过大，结构预览仅支持 ${JSON_STRUCTURE_MAX_CHARS / 1024 / 1024}MB 以内的 JSON。可切换回「源码」查看。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Obsidian 风格 frontmatter 属性面板：卡片样式，key 灰色 + value；数组值渲染为 chip。
 * 显示在渲染态正文上方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesPanel(properties: List<FrontmatterProperty>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "属性",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        properties.forEach { prop ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prop.key,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                if (prop.isList) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        prop.value.split(',').filter { it.isNotEmpty() }.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = prop.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private sealed interface JsonStructureResult {
    data class Parsed(val element: JsonElement) : JsonStructureResult
    data object Invalid : JsonStructureResult
    data object TooLarge : JsonStructureResult
}

// 双链语法 `[[...]]`；用负向前瞻排除图片 wikilink `![[...]]`（那是图片嵌入，不是笔记链接）
private val WIKILINK_REGEX = Regex("""(?<!\!)\[\[([^\[\]]+?)\]\]""")

/** Obsidian 图片嵌入 wikilink：`![[文件名.jpg]]`（可带 `|别名`、`#章节`） */
private val WIKILINK_IMAGE_REGEX = Regex("""!\[\[([^\[\]]+)\]\]""")

/**
 * 把 Obsidian 双链预处理成 Markdown 链接，目标用 `rikkahub-note/目标` 的相对路径前缀标记
 * （不是 `note:` 协议——解析器 `useSafeLinks` 会过滤非标准协议链接）。由 [LinkClickHandler] 接管跳转。
 * `[[目标]]`、`[[目标|别名]]`、`[[目标#章节]]`、`[[目标^块]]` 都支持；目标会去掉 .md 后缀。
 * internal：编辑器页任务行匹配时也用它做同样的归一。
 */
internal fun convertWikilinksToNoteLinks(content: String): String =
    WIKILINK_REGEX.replace(content) { m ->
        val inner = m.groupValues[1]
        val parts = inner.split('|', limit = 2)
        val targetRaw = parts[0].trim()
        val alias = parts.getOrNull(1)?.trim()
        // 去掉 #章节 / ^块引用，得到笔记文件目标
        val fileTarget = targetRaw
            .substringBefore('#')
            .substringBefore('^')
            .trim()
            .removeSuffix(".md")
            .removeSuffix(".markdown")
        if (fileTarget.isEmpty()) {
            m.value
        } else {
            val display = alias ?: inner.trim()
            "[$display](rikkahub-note/${encodeNoteTarget(fileTarget)})"
        }
    }

/** 目标路径编码：保留斜杠，空格/特殊字符 percent-encode（GFM 链接 destination 不能含未转义空格） */
private fun encodeNoteTarget(target: String): String =
    target.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

/** Markdown 图片语法：`![alt](src)`。src 允许空格（Obsidian 常见 `My Image.png`），仅排除右括号 */
private val IMAGE_MARKDOWN_REGEX = Regex("""(!\[[^\]]*\])\(([^)]+)\)""")

/**
 * 把笔记里的相对图片路径替换成可加载的 URI（如 content://），使 `![](images/a.png)` 能直接显示。
 * 已带协议（http/https/content/file）的 src 原样保留；resolver 返回 null（解析失败）时也保留原样。
 * 相对当前笔记目录的路径由调用方在 resolver 内拼装，这里不做基准假设。
 */
private suspend fun replaceImageSrcs(
    content: String,
    resolver: (suspend (String) -> String?)?,
): String {
    if (resolver == null) return content
    // 归一 Obsidian 图片 wikilink `![[文件]]` → `![alt](目标)`，统一走下方图片路径解析
    val normalized = WIKILINK_IMAGE_REGEX.replace(content) { m ->
        val inner = m.groupValues[1].trim()
        val parts = inner.split('|', limit = 2)
        val target = parts[0].trim().substringBefore('#').substringBefore('^').trim()
        val alias = parts.getOrNull(1)?.trim()
        if (target.isEmpty()) m.value else "![${alias ?: target}]($target)"
    }
    val out = StringBuilder()
    var last = 0
    for (m in IMAGE_MARKDOWN_REGEX.findAll(normalized)) {
        out.append(normalized, last, m.range.first)
        val prefix = m.groupValues[1]
        val src = m.groupValues[2].trim()
        if (src.startsWith("http://") || src.startsWith("https://") ||
            src.startsWith("content://") || src.startsWith("file://")
        ) {
            out.append(m.value)
        } else {
            val resolved = resolver(src)
            out.append(if (resolved == null) m.value else "$prefix($resolved)")
        }
        last = m.range.last + 1
    }
    out.append(normalized, last, normalized.length)
    return out.toString()
}

// ---- Obsidian 预处理（仅信任文件夹预览走 needsPreprocess 分支时生效） ----

/** frontmatter 属性：key + 显示值；isList 用于渲染成 chip 而非纯文本 */
internal data class FrontmatterProperty(
    val key: String,
    val value: String,
    val isList: Boolean = false,
)

/**
 * 解析 YAML frontmatter（Obsidian 属性子集），返回 (属性列表, 去掉 frontmatter 的正文)：
 * - 仅当内容以 `---` 开头、且找到第二个 `---` 行为 frontmatter
 * - 支持 `key: value` 标量、`key: [a, b]` 内联数组、`key:` 后跟缩进 `- item` 块列表
 * - 无 frontmatter / 解析失败 → 空列表 + 原文
 */
internal fun parseFrontmatter(content: String): Pair<List<FrontmatterProperty>, String> {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("---")) return emptyList<FrontmatterProperty>() to content
    val lines = trimmed.lineSequence().toList()
    if (lines.size < 2) return emptyList<FrontmatterProperty>() to content
    var end = -1
    for (i in 1 until lines.size) {
        if (lines[i].trim() == "---") { end = i; break }
    }
    if (end < 0) return emptyList<FrontmatterProperty>() to content
    val properties = parseYamlProperties(lines.subList(1, end))
    val body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
    return properties to body
}

/** 解析 frontmatter 内容（不含 `---` 围栏）：支持标量、内联数组、块列表 */
private fun parseYamlProperties(fmLines: List<String>): List<FrontmatterProperty> {
    val result = mutableListOf<FrontmatterProperty>()
    var i = 0
    while (i < fmLines.size) {
        val line = fmLines[i].trim()
        i++
        if (line.isEmpty() || line.startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim()
        var value = line.substring(colon + 1).trim()
        // 内联数组 [a, b]
        if (value.startsWith("[")) {
            val items = value.removeSurrounding("[", "]").split(',')
                .map { unquote(it.trim()) }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                result += FrontmatterProperty(key, items.joinToString(","), isList = true)
            }
            continue
        }
        // 块列表：`key:` 后跟缩进的 `- item`
        if (value.isEmpty() && i < fmLines.size) {
            val listItems = mutableListOf<String>()
            while (i < fmLines.size && fmLines[i].trimStart().startsWith("- ")) {
                listItems += unquote(fmLines[i].trim().removePrefix("-").trim())
                i++
            }
            if (listItems.isNotEmpty()) {
                result += FrontmatterProperty(key, listItems.joinToString(","), isList = true)
                continue
            }
        }
        // 空值键跳过
        if (value.isEmpty()) continue
        result += FrontmatterProperty(key, unquote(value))
    }
    return result
}

/** 去掉简单单/双引号 */
private fun unquote(s: String): String =
    if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\''))))
        s.substring(1, s.length - 1)
    else s

/** 图片扩展名集合：笔记嵌入中带图片扩展名的目标交给图片解析，不展开 */
private val EMBED_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

/** Obsidian 笔记嵌入：`![[目标]]`（目标可带 `|别名`、`#章节`、`.md` 后缀） */
private val NOTE_EMBED_REGEX = Regex("""!\[\[([^\[\]]+?)\]\]""")

/** 删除 YAML frontmatter：仅当内容以 `---` 开头时，删到第二个 `---` 行 */
internal fun stripFrontmatter(content: String): String {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("---")) return content
    val lines = trimmed.lineSequence().toList()
    if (lines.size < 2) return content
    var end = -1
    for (i in 1 until lines.size) {
        if (lines[i].trim() == "---") { end = i; break }
    }
    if (end < 0) return content
    return lines.drop(end + 1).joinToString("\n").trimStart('\n')
}

/** 删除 `%%注释%%`（可跨行）。注释内可能有其它标记，必须先于其它转换执行 */
internal fun stripComments(content: String): String =
    content.replace(Regex("%%[\\s\\S]*?%%"), "")

/**
 * 提取脚注定义 `[^id]: 内容`：从正文删除定义行，返回 (正文, 定义区文本)。
 * 正文里的 `[^id]` 引用保留，由渲染层渲染成上标。
 */
internal fun extractFootnotes(content: String): Pair<String, String> {
    val footnoteLine = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")
    val definitions = mutableListOf<String>()
    val kept = mutableListOf<String>()
    content.lineSequence().forEach { line ->
        val m = footnoteLine.matchEntire(line.trim())
        if (m != null) {
            // `]` 后插空格：避免渲染层把整行当成 LINK_DEFINITION 吞掉；`[^1]` 由渲染层渲染成上标
            definitions += "[^${m.groupValues[1]}] ：${m.groupValues[2]}"
        } else {
            kept += line
        }
    }
    return kept.joinToString("\n") to definitions.joinToString("\n")
}

/**
 * 行内脚注 `^[内容]`：按出现顺序转成 `[^n]` 引用，内容加入脚注定义列表（与命名脚注共用序号，
 * 从正文已有最大脚注号 +1 起排，避免冲突）。生成的定义由 [extractFootnotes] 统一提取到文末。
 */
internal fun convertInlineFootnotes(content: String): Pair<String, List<String>> {
    val inlineFootnoteRegex = Regex("""\^\[([^\]]+)\]""")
    val definitions = mutableListOf<String>()
    val existingIds = Regex("""\[\^(\d+)\]""").findAll(content)
        .mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
    var next = (existingIds.maxOrNull() ?: 0) + 1
    val out = inlineFootnoteRegex.replace(content) { m ->
        val id = next++
        definitions += "[^$id] ：${m.groupValues[1].trim()}"
        "[^$id]"
    }
    return out to definitions
}

/** 提取脚注行的编号 `[^n]`，用于按编号排序；无编号排最后 */
internal fun footnoteNumber(line: String): Int =
    Regex("""\[\^(\d+)\]""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

/**
 * 展开 Obsidian 笔记嵌入 `![[Note]]`（非图片）：读取目标笔记内容替换进来。
 * 递归展开嵌入内容里的再嵌入；[expanding] 防循环（A↔B 互嵌），[depth] 限制嵌套深度。
 * 展开的笔记内容同样剥 frontmatter / 注释；其双链/图片由后续整体转换统一处理。
 */
internal suspend fun expandNoteEmbeds(
    content: String,
    resolver: (suspend (String) -> String?)?,
    expanding: Set<String>,
    depth: Int,
): String {
    if (resolver == null || depth > 2) return content
    var result = content
    val matches = NOTE_EMBED_REGEX.findAll(content).toList()
    for (m in matches.asReversed()) {
        val inner = m.groupValues[1].trim()
        val target = inner
            .substringBefore('|').substringBefore('#').substringBefore('^').trim()
            .removeSuffix(".md").removeSuffix(".markdown")
        if (target.isEmpty()) continue
        // 带图片扩展名的目标是图片嵌入，交给 replaceImageSrcs，不在这里展开
        val ext = target.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext.lowercase() in EMBED_IMAGE_EXTENSIONS) continue
        if (target in expanding) continue
        val noteContent = resolver(target) ?: continue
        val cleaned = stripFrontmatter(stripComments(noteContent))
        val expanded = expandNoteEmbeds(cleaned, resolver, expanding + target, depth + 1)
        result = result.replace(m.value, "\n\n$expanded\n\n")
    }
    return result
}
