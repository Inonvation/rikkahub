package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/** Markdown 预览模式 */
private enum class MarkdownPreviewMode {
    SOURCE,
    RENDER,
}

/**
 * Markdown「源码 / 渲染」切换组件.
 *
 * 源码态 = 只读 monospace TextField（可传 [sourceEditable] 放开编辑）;
 * 渲染态 = 复用学习工具统一的 [StudyMarkdownBlock]（内部已做裸 LaTeX 包裹）.
 * 切换即时生效, **默认源码态**（打开大文件不卡顿，避免先渲染）. [state] 由调用方持有,
 * 便于保留光标/编辑与后续保存.
 */
@Composable
fun MarkdownPreviewSwitcher(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    sourceEditable: Boolean = false,
) {
    var mode by rememberSaveable { mutableStateOf(MarkdownPreviewMode.SOURCE) }

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            MarkdownPreviewMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { mode = item },
                    shape = SegmentedButtonDefaults.itemShape(index, MarkdownPreviewMode.entries.size),
                ) {
                    Text(if (item == MarkdownPreviewMode.SOURCE) "源码" else "渲染")
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

            MarkdownPreviewMode.RENDER -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                StudyMarkdownBlock(content = state.text.toString())
            }
        }
    }
}
