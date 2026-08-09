package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.rerere.rikkahub.data.sync.SyncItemStatus
import me.rerere.rikkahub.data.sync.SyncPreview
import me.rerere.rikkahub.data.sync.SyncPreviewItem
import me.rerere.rikkahub.data.sync.SyncProgress
import me.rerere.rikkahub.data.sync.SyncProgressItem

/**
 * 同步差异确认弹窗：分组展示「将上传 / 将从云端更新 / 将删除 / 冲突」清单，
 * 用户确认后才真正执行同步。
 *
 * 用 LazyColumn 惰性渲染：差异项可能上千，一次性创建全部 UI 节点会卡死主线程（ANR），
 * 因此只渲染可见项，且每类最多展示 [MAX_VISIBLE_ITEMS] 条，超出折叠为「还有 N 项」。
 */
@Composable
fun SyncPreviewDialog(
    preview: SyncPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步预览") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                item {
                    Text("检测到 ${preview.totalCount} 项差异，共约 ${formatSize(preview.totalSize)}，确认后开始同步：")
                    Spacer(Modifier.height(8.dp))
                }

                if (preview.uploads.isNotEmpty()) {
                    previewSection("将上传到云端 ↑", preview.uploads)
                }
                if (preview.updates.isNotEmpty()) {
                    previewSection("将从云端更新到本地 ↓", preview.updates)
                }
                if (preview.deletions.isNotEmpty()) {
                    previewSection("将删除云端文件 ✕", preview.deletions)
                }
                if (preview.conflicts.isNotEmpty()) {
                    previewSection(
                        title = "冲突（将保留副本）⚠",
                        items = preview.conflicts,
                        note = "双端都改过，同步时会自动保留冲突副本",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认同步") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private const val MAX_VISIBLE_ITEMS = 50

private fun LazyListScope.previewSection(
    title: String,
    items: List<SyncPreviewItem>,
    note: String? = null,
) {
    item {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = "$title（共 ${items.size} 项）",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
    }

    items(items.take(MAX_VISIBLE_ITEMS), key = { it.relPath }) { item ->
        Text(
            text = "• ${item.label}${formatSize(item.size)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (items.size > MAX_VISIBLE_ITEMS) {
        item {
            Text(
                text = "… 还有 ${items.size - MAX_VISIBLE_ITEMS} 项未显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (note != null) {
        item {
            Column {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 字节数格式化为用户可读的 " (12.3 KB)"；未知/为 0 时不显示。 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    return when {
        bytes >= 1024 * 1024 -> " (${String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)} MB)"
        bytes >= 1024 -> " (${String.format(Locale.US, "%.1f", bytes / 1024.0)} KB)"
        else -> " ($bytes B)"
    }
}

/**
 * 同步执行进度弹窗：确认同步后展示，逐项显示每个同步单元的实时状态
 * （等待 ○ / 同步中 转圈 / 已完成 ✓ / 失败 ✕），顶部进度条 + 计数。
 *
 * - 进行中：可「取消同步」或「后台运行」（收起弹窗，同步继续跑）
 * - 结束后：显示成功/失败/取消结果，点「完成」关闭
 */
@Composable
fun SyncProgressDialog(
    progress: SyncProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    !progress.finished -> "正在同步…"
                    progress.success == true -> "同步完成"
                    progress.success == false -> "同步失败"
                    else -> "同步已取消"
                }
            )
        },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已完成 ${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress.error?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 惰性渲染：大同步可能有上千项，只渲染可见项避免卡死主线程
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(progress.items, key = { it.relPath }) { item ->
                        SyncProgressRow(
                            item = item,
                            status = progress.statusByPath[item.relPath] ?: SyncItemStatus.WAITING,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (progress.finished) {
                TextButton(onClick = onDismiss) { Text("完成") }
            } else {
                TextButton(onClick = onCancel) { Text("取消同步") }
            }
        },
        dismissButton = {
            // 进行中允许收起到后台继续；结束后只剩「完成」
            if (!progress.finished) {
                TextButton(onClick = onDismiss) { Text("后台运行") }
            }
        },
    )
}

@Composable
private fun SyncProgressRow(item: SyncProgressItem, status: SyncItemStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            SyncItemStatus.DONE -> Text("✓", color = MaterialTheme.colorScheme.primary)
            SyncItemStatus.FAILED -> Text("✕", color = MaterialTheme.colorScheme.error)
            SyncItemStatus.IN_PROGRESS -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            SyncItemStatus.WAITING -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = item.label + formatSize(item.size),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == SyncItemStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}
