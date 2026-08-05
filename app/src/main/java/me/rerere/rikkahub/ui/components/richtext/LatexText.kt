package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

private class LatexKey(
    val latex: String,
    val sizePx: Int,
    val color: Int,
    val background: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is LatexKey && other.latex == latex && other.sizePx == sizePx &&
            other.color == color && other.background == background

    override fun hashCode(): Int =
        latex.hashCode() * 31 + sizePx * 7 + color * 3 + background
}

/** 进程级 LaTeX drawable 缓存：滚动时新进入视口的含公式消息首帧命中缓存，
 * 避免主线程同步 build JLatexMathDrawable 掉帧（不降级、无占位）。按 bounds 面积近似内存占用。 */
private val latexDrawableCache = object : LruCache<LatexKey, JLatexMathDrawable>(256) {
    override fun sizeOf(key: LatexKey, value: JLatexMathDrawable): Int =
        value.bounds.width() * value.bounds.height() / 1024 + 1
}

/** 公式尺寸缓存（assumeLatexSize 用），按 "latex|fontSizePx" 分键 */
private val latexSizeCache = LruCache<String, Rect>(512)

/** 行内公式拆分缓存（splitLatex 用），按 latex+宽度桶+字号+颜色分键 */
private val latexSplitCache = LruCache<String, List<JLatexMathDrawable>>(128)

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    val key = "$latex|${fontSize.toInt()}"
    latexSizeCache.get(key)?.let { return it }
    val result = runCatching {
        JLatexMathDrawable.builder(latex)
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
    }.getOrElse { Rect(0, 0, 0, 0) }
    latexSizeCache.put(key, result)
    return result
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    val processed = processLatex(latex)
    val key = LatexKey(processed, fontSize.toInt(), color, background)
    latexDrawableCache.get(key)?.let { return it }
    return runCatching {
        JLatexMathDrawable.builder(processed)
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onSuccess { latexDrawableCache.put(key, it) }
        .onFailure { it.printStackTrace() }
        .getOrNull()
}

/**
 * 将一条行内公式按顶层运算符水平拆分为多段 Drawable，
 * 以便在文本流中换行，避免单体公式过长被挤出屏幕。
 * 拆分失败时返回空列表，调用方需自行回退。
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSize: Float,
    color: Int
): List<JLatexMathDrawable> {
    val processed = processLatex(latex)
    val key = "$processed|${(maxWidthPx / 20f).toInt()}|${fontSize.toInt()}|$color"
    latexSplitCache.get(key)?.let { return it }
    return runCatching {
        JLatexMathSplitter.split(processed, maxWidthPx, fontSize, color)
    }.onSuccess { latexSplitCache.put(key, it) }
        .onFailure { it.printStackTrace() }
        .getOrElse { emptyList() }
}

@Composable
fun LatexDrawable(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp()
            )
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    return when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
}
