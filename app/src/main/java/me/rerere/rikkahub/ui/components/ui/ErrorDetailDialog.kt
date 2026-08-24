package me.rerere.rikkahub.ui.components.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.utils.toExplainedError

/**
 * 错误详情弹窗：把报错翻译成通俗原因 + 建议，同时展示原始错误原文，带复制按钮。
 * 点击 ErrorCard 上的「查看详情」时弹出。
 */
@Composable
fun ErrorDetailDialog(
    error: ChatError,
    onDismiss: () -> Unit,
) {
    val explained = remember(error) { error.error.toExplainedError() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hapticController = rememberHaptic()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title ?: "错误详情") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("可能原因")
                Text(
                    text = explained.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )

                explained.suggestion?.let { suggestion ->
                    SectionLabel("建议")
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SectionLabel("原始错误")
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = explained.rawMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                hapticController.lightTap()
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(
                            clipData = ClipData.newPlainText("Error", explained.rawMessage)
                        )
                    )
                }
            }) {
                Text("复制错误")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                hapticController.lightTap()
                onDismiss()
            }) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
