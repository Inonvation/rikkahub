package me.rerere.highlight

import android.util.LruCache
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

private const val MAX_CODE_LENGTH = 4096

/**
 * 高亮 token 结果的进程级缓存（LRU，线程安全；HighlightEngine 每次调用创建独立 Run 状态）。
 * 滚动时消息 item 划出视口再回来会重新组合，对同一大代码块每次同步高亮是主线程掉帧主因；
 * 命中缓存后 O(1) 复用，配合 [CodeHighlightText] 的后台高亮消除首帧阻塞。
 * key = language + code，value 为解析出的 [HighlightToken] 列表（不绑颜色，渲染时再套用 palette）。
 * sizeOf 按 key 长度（KB）近似 token 结果占用。
 */
internal val codeHighlightTokensCache = object : LruCache<String, List<HighlightToken>>(4 * 1024) {
    override fun sizeOf(key: String, value: List<HighlightToken>): Int = key.length / 1024 + 1
}

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current
    // 超大代码走纯文本降级，不缓存
    val cacheKey = if (code.length <= MAX_CODE_LENGTH) "$language\u0000$code" else null
    // 高亮结果与其对应的代码快照。流式输出期间 code 每 chunk 一变，cacheKey 随之变化，
    // 若每 chunk 都把 tokens 重置为空再等后台高亮，整个代码块会反复「纯文本→上色」闪烁；
    // 因此跨 chunk 保留旧结果作兜底：新代码在前缀上扩展时先复用旧 tokens（已着色部分不回退），
    // 覆盖不到的剩余部分（含新增尾部）按纯文本追加上屏，等新高亮就绪后整体上色。
    var tokenized by remember {
        mutableStateOf(
            cacheKey?.let { key -> codeHighlightTokensCache.get(key)?.let { TokenizedResult(code, it) } }
        )
    }
    // 生成序号：流式期间 LaunchedEffect 每 chunk 重启，若旧 effect 被取消但高亮引擎未响应取消，
    // 仍可能比新 effect 晚写入结果；序号用于丢弃过期写入。
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheKey) {
        val key = cacheKey ?: return@LaunchedEffect
        val job = ++generation
        codeHighlightTokensCache.get(key)?.let { cached ->
            if (job == generation) {
                tokenized = TokenizedResult(code, cached)
            }
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            highlighter.highlight(code, language)
        }
        if (job == generation) {
            codeHighlightTokensCache.put(key, result)
            tokenized = TokenizedResult(code, result)
        }
    }
    val annotatedString = remember(cacheKey, tokenized, colors) {
        buildCodeHighlightAnnotatedString(code, tokenized, colors)
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        // 禁用连字/上下文替换：代码里 `!=`、`->` 等被字体连字渲染成箭头/不等号会误导阅读，
        // 且按字符宽度选中/复制会与视觉不符（上游 7b92f89e 同语义）。
        style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/**
 * 高亮结果 + 产出它的代码快照。流式期间旧结果需要保留作前缀兜底，
 * 不能只存 token 列表（否则无法判断 tokens 与当前 code 的对应关系）。
 */
internal data class TokenizedResult(
    val code: String,
    val tokens: List<HighlightToken>,
)

/**
 * 组装代码高亮的 AnnotatedString：
 * - 无高亮结果时整体按纯文本渲染；
 * - tokens 对应的代码是当前 code 的前缀（流式追加）时，旧 tokens 对共同前缀仍有效，
 *   先按旧 tokens 上色，覆盖不到的剩余部分按纯文本追加——已着色部分不闪回纯色，等高亮
 *   就绪后整体上色；追加位置取自 tokens 实际覆盖长度（而非旧代码长度），
 *   避免语法解析中途截断导致尾部丢失；
 * - code 与 tokens 的代码不一致（重新生成/编辑替换了内容）时退回纯文本，等待新高亮。
 */
internal fun buildCodeHighlightAnnotatedString(
    code: String,
    tokenized: TokenizedResult?,
    colors: HighlightTextColorPalette,
): AnnotatedString {
    val current = tokenized ?: return AnnotatedString(code)
    if (!code.startsWith(current.code)) return AnnotatedString(code)
    return buildAnnotatedString {
        var covered = 0
        current.tokens.forEach { token ->
            buildHighlightText(token, colors)
            covered += token.content.length
        }
        if (covered < code.length) {
            append(code.substring(covered))
        }
    }
}
