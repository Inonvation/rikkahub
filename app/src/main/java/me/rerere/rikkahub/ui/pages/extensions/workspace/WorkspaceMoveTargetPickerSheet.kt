package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 移动目标目录选择器：在当前存储区内按目录树导航，选择目标目录后回调其相对路径（"" = 根目录）。
 *
 * 对目录源，禁用其自身及其子目录作为目标（避免把目录移进自己）。
 */
@Composable
fun WorkspaceMoveTargetPickerSheet(
    workspaceId: String,
    area: WorkspaceStorageArea,
    sources: List<WorkspaceFileEntry>,
    onSelectTarget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    var browsePath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val hapticController = rememberHaptic()

    LaunchedEffect(browsePath) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                workspaceRepository.listFiles(workspaceId, area, browsePath)
            }
            entries = result.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            entries = emptyList()
            loading = false
        }
    }

    // 目录源自身及其子目录不可作为目标
    fun isForbidden(dirPath: String): Boolean = sources.any { source ->
        source.isDirectory && (source.path == dirPath || dirPath.startsWith(source.path + "/"))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "移动到…",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "选中的 ${sources.size} 项将移动到所选目录下（保持名称）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    enabled = browsePath.isNotBlank(),
                    onClick = {
                        hapticController.lightTap()
                        browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
                    },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                }
                Text(
                    text = if (browsePath.isBlank()) "根目录 /" else browsePath,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
            ) {
                val dirs = entries.filter { it.isDirectory }
                items(dirs, key = { it.path }) { dir ->
                    val forbidden = isForbidden(dir.path)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = dir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (forbidden) {
                            {
                                Text(
                                    text = "不能移动到自身或其子目录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            null
                        },
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .alpha(if (forbidden) 0.45f else 1f)
                            .clickable(enabled = !forbidden) {
                                hapticController.lightTap()
                                browsePath = dir.path
                            },
                    )
                }

                if (!loading && dirs.isEmpty()) {
                    item {
                        Text(
                            text = "该目录下没有子目录",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    hapticController.lightTap()
                    onDismiss()
                }) {
                    Text("取消")
                }
                FilledTonalButton(onClick = {
                    hapticController.lightTap()
                    onSelectTarget(browsePath)
                    onDismiss()
                }) {
                    Text("移动到此")
                }
            }
        }
    }
}
