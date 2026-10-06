package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull

/** eval_javascript 输出 JSON: {logs?, result} */
object JavascriptToolUI : ToolUIRenderer {
    override val toolName: String = "eval_javascript"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Code

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_javascript)

    override fun hasSummary(context: ToolUIContext): Boolean = result(context) != null

    /** 取输出里的 result 值（字符串化） */
    private fun result(context: ToolUIContext): String? {
        val obj = context.content?.jsonObjectOrNull ?: return null
        return obj["result"]?.jsonPrimitive?.contentOrNull
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val res = result(context) ?: return
        Text(
            text = res,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val logs = context.content?.jsonObjectOrNull
            ?.get("logs")?.jsonPrimitive?.contentOrNull
        val res = result(context)

        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (!logs.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_message_tool_javascript_logs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HighlightCodeBlock(
                    code = logs,
                    language = "text",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(R.string.chat_message_tool_javascript_result),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (res != null) {
                HighlightCodeBlock(
                    code = res,
                    language = "javascript",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_message_tool_javascript_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

