package me.rerere.rikkahub.ui.pages.study

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/**
 * 详情弹窗用的 markdown + 数学公式渲染。入口先包裹裸 LaTeX（如 `解：\log_2 x=3`），
 * 使其能被 MarkdownBlock 识别为公式，再交给全局 MarkdownBlock。
 */
@Composable
fun StudyMarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    val wrapped = remember(content) { wrapBareLatex(content) }
    MarkdownBlock(wrapped, modifier, style, onClickCitation)
}

/**
 * 列表/卡片标题：纯文本摘要。公式转为紧凑文字（$x^2$ → "x^2"），不做公式样式渲染。
 */
@Composable
fun PlainTitle(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    modifier: Modifier = Modifier,
) {
    val plain = remember(text) { extractPlainText(text) }
    Text(
        text = plain,
        style = if (fontWeight != null) style.merge(fontWeight = fontWeight) else style,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}
