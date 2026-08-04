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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
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
 * Markdown「源码 / 渲染 / 结构」切换组件.
 *
 * 源码态 = 只读 monospace TextField（可传 [sourceEditable] 放开编辑）;
 * 渲染态 = 复用学习工具统一的 [StudyMarkdownBlock]（内部已做裸 LaTeX 包裹）;
 * 结构态 = 仅对 JSON 文件启用（[jsonStructure] = true）的树状预览，格式错误/超大文件有友好提示。
 * 切换即时生效, **默认源码态**（打开大文件不卡顿，避免先渲染）. [state] 由调用方持有,
 * 便于保留光标/编辑与后续保存.
 */
@Composable
fun MarkdownPreviewSwitcher(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    sourceEditable: Boolean = false,
    jsonStructure: Boolean = false,
) {
    var mode by rememberSaveable { mutableStateOf(MarkdownPreviewMode.SOURCE) }
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
