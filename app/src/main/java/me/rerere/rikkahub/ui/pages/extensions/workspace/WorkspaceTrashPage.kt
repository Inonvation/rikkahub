package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区回收站管理页: 列出当前工作区某一存储区(FILES/LINUX) `.trash` 内的文件,
 * 支持恢复到原路径或彻底删除。
 *
 * 回收站按存储区分区(`<area>/..trash`), 页面用 SegmentedButton 切换分区查看;
 * 所有操作走 WorkspaceRepository 已有的 listTrash/restoreFile/deleteTrashFile,
 * 不做任何数据层改动。
 */
@Composable
fun WorkspaceTrashPage(id: String) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf(WorkspaceStorageArea.FILES) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    fun load(currentArea: WorkspaceStorageArea) {
        scope.launch {
            loading = true
            loadError = null
            runCatching { repository.listTrash(id, currentArea) }
                // 快速切换分区时旧请求可能晚回: 只接受仍属当前分区的结果, 避免覆盖新分区列表
                .onSuccess { list -> if (currentArea == area) entries = list }
                .onFailure { e -> if (currentArea == area) loadError = e.message ?: "加载回收站失败" }
            if (currentArea == area) loading = false
        }
    }

    // 首次进入与切换分区时加载
    LaunchedEffect(area) { load(area) }

    /** 恢复到原路径: 恢复操作低风险(冲突自动改名、原目录重建), 直接执行并刷新 */
    fun restore(entry: WorkspaceFileEntry) {
        scope.launch {
            runCatching { repository.restoreFile(id, area, entry.path) }
                .onSuccess { ok ->
                    if (ok) {
                        toaster.show("已恢复到原路径", type = ToastType.Success)
                        load(area)
                    } else {
                        toaster.show("恢复失败: 文件已不存在", type = ToastType.Error)
                        load(area)
                    }
                }
                .onFailure { e ->
                    toaster.show(e.message ?: "恢复失败", type = ToastType.Error)
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 分区切换: 回收站按 FILES / LINUX 分区存储, 与文件页区域一致
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                WorkspaceStorageArea.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = area == item,
                        onClick = { area = item },
                        shape = SegmentedButtonDefaults.itemShape(index, WorkspaceStorageArea.entries.size),
                    ) {
                        Text(if (item == WorkspaceStorageArea.FILES) "文件区" else "系统区")
                    }
                }
            }

            when {
                loading && entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                loadError != null -> Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "回收站是空的",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        TrashEntryCard(
                            entry = entry,
                            onRestore = { restore(entry) },
                            onDelete = { deleteTarget = entry },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "彻底删除",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                scope.launch {
                    runCatching { repository.deleteTrashFile(id, area, target.path) }
                        .onSuccess { ok ->
                            if (ok) {
                                toaster.show("已删除", type = ToastType.Success)
                            } else {
                                toaster.show("删除失败: 文件已不存在", type = ToastType.Error)
                            }
                            load(area)
                        }
                        .onFailure { e ->
                            toaster.show(e.message ?: "删除失败", type = ToastType.Error)
                        }
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text("彻底删除后无法恢复，确定删除 ${target.path} 吗？")
        }
    }
}

@Composable
private fun TrashEntryCard(
    entry: WorkspaceFileEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRestore),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("恢复") },
                        leadingIcon = { Icon(HugeIcons.ArrowTurnBackward, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("彻底删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
