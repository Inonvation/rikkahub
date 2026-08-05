package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
import me.rerere.rikkahub.ui.pages.study.wrapBareLatex
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/** Markdown 预览模式 */
private enum class MarkdownPreviewMode {
    SOURCE,
    RENDER,
    STRUCTURE,
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
    onLinkClick: LinkClickHandler? = null,
    imagePathResolver: (suspend (String) -> String?)? = null,
) {
    var mode by rememberSaveable {
        mutableStateOf(
            if (previewByDefault) MarkdownPreviewMode.RENDER else MarkdownPreviewMode.SOURCE
        )
    }
    val modes = if (jsonStructure) {
        listOf(MarkdownPreviewMode.SOURCE, MarkdownPreviewMode.RENDER, MarkdownPreviewMode.STRUCTURE)
    } else {
        listOf(MarkdownPreviewMode.SOURCE, MarkdownPreviewMode.RENDER)
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
                val raw = state.text.toString()
                // 仅当需要双链/图片预处理（信任文件夹笔记）时才走「后台解析 + 加载动画」路径；
                // 其它调用方（工作区等）保持原有即时渲染，避免引入多余转圈与后台线程。
                val needsPreprocess = onLinkClick != null || imagePathResolver != null
                if (needsPreprocess) {
                    // 链接跳转前先把 [[双链]] 预处理成内部链接；图片相对路径解析成可加载 URI。
                    // 全部耗时预处理放后台线程，期间显示加载动画——避免大文本的正则转换/解析
                    // 卡住主线程、连加载动画都冻结。
                    var prepared by remember(raw) { mutableStateOf<String?>(null) }
                    LaunchedEffect(raw) {
                        prepared = withContext(Dispatchers.Default) {
                            val converted = convertWikilinksToNoteLinks(raw)
                            val withImages = replaceImageSrcs(converted, imagePathResolver)
                            warmMarkdownCache(wrapBareLatex(withImages))
                            withImages
                        }
                    }
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
                                StudyMarkdownBlock(content = content, onLinkClick = onLinkClick)
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
 */
private fun convertWikilinksToNoteLinks(content: String): String =
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
