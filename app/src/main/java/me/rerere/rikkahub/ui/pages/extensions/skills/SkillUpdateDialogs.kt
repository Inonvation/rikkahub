package me.rerere.rikkahub.ui.pages.extensions.skills

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillUpdateManager

/** commit 摘要行：message 首行（已在解析时截断）+ 相对时间；都缺省时整行不显示。 */
@Composable
private fun CommitSummary(message: String?, time: Long?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank() && time == null) return
    Column(modifier = modifier) {
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (time != null && time > 0) {
            Text(
                text = DateUtils.getRelativeTimeSpanString(time).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 变更计数行：+N / ~N / -N，只显示非零项。 */
@Composable
private fun ChangeCounts(prepared: SkillUpdateManager.PreparedUpdate, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (prepared.added.isNotEmpty()) {
            Text(
                text = stringResource(R.string.skills_page_change_added, prepared.added.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (prepared.modified.isNotEmpty()) {
            Text(
                text = stringResource(R.string.skills_page_change_modified, prepared.modified.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (prepared.removed.isNotEmpty()) {
            Text(
                text = stringResource(R.string.skills_page_change_removed, prepared.removed.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 增量更新预览弹窗：commit 摘要 + 变更计数 + 文件清单；
 * [SkillUpdateManager.PreparedUpdate.overwriteFiles] 非空时内联覆盖警示（确认按钮变为「覆盖并更新」），
 * 取代旧的两段式覆盖确认弹窗。
 */
@Composable
fun SkillUpdatePreviewDialog(
    prepared: SkillUpdateManager.PreparedUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val hasOverwrite = prepared.overwriteFiles.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CommitSummary(prepared.commitMessage, prepared.commitTime)
                ChangeCounts(prepared)
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    prepared.added.fastForEach {
                        ChangeEntry(path = it, mark = "+", color = MaterialTheme.colorScheme.primary)
                    }
                    prepared.modified.fastForEach {
                        ChangeEntry(path = it, mark = "~", color = MaterialTheme.colorScheme.tertiary)
                    }
                    prepared.removed.fastForEach {
                        ChangeEntry(path = it, mark = "-", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (hasOverwrite) {
                    Text(
                        text = stringResource(
                            R.string.skills_page_update_overwrite_files,
                            prepared.overwriteFiles.joinToString("、"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (hasOverwrite) R.string.skills_page_update_apply_overwrite
                        else R.string.skills_page_update_apply
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ChangeEntry(path: String, mark: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mark,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 批量更新进度弹窗：显示当前进度文案，可取消（当前技能完成后停止）。 */
@Composable
fun SkillBatchUpdateProgressDialog(
    progress: String?,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.skills_page_batch_update)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
                Text(progress ?: stringResource(R.string.skills_page_downloading))
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
