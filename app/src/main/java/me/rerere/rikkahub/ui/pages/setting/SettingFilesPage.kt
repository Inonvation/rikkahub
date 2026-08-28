package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)
    val batchDeletePartialToast = stringResource(R.string.setting_files_page_batch_delete_partial)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var sourceFilter by remember { mutableStateOf<String?>(null) } // null=全部, chat=聊天附件, avatar=头像
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingBatchDelete by remember { mutableStateOf<List<ManagedFileEntity>?>(null) }
    var showCleanSheet by remember { mutableStateOf(false) }
    var selectedCleanRange by remember { mutableStateOf(CleanRange.DAYS_7) }
    // 选择模式：null 表示未开启
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    val files by filesManager.observe(selectedFolder, sourceFilter).collectAsState(initial = emptyList())

    // 选择模式下文件夹切换时清理选择
    val isSelecting = selection != null
    val selectedIds = selection.orEmpty()

    fun exitSelection() {
        selection = null
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            toaster.show(if (ok) deletedToast else deleteFailedToast)
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    // 批量删除确认
    if (pendingBatchDelete != null) {
        val targets = pendingBatchDelete!!
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_batch_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.setting_files_page_batch_delete_confirmation,
                        targets.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBatchDelete = null
                        scope.launch {
                            val okCount = filesManager.deleteByIds(
                                targets.map { it.id },
                                deleteFromDisk = true
                            )
                            if (okCount == targets.size) {
                                toaster.show(deletedToast)
                            } else if (okCount > 0) {
                                toaster.show(String.format(batchDeletePartialToast, okCount))
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            exitSelection()
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showCleanSheet = false },
            sheetState = sheetState,
        ) {
            CleanFilesSheet(
                selectedRange = selectedCleanRange,
                onRangeSelected = { selectedCleanRange = it },
                onClean = {
                    showCleanSheet = false
                    scope.launch {
                        val ok = selectedCleanRange.days?.let { days ->
                            filesManager.deleteOlderThan(
                                folder = selectedFolder,
                                cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong()),
                            )
                        } ?: filesManager.deleteAll(selectedFolder)
                        toaster.show(if (ok) cleanedToast else cleanFailedToast)
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    if (isSelecting) {
                        Text(stringResource(R.string.setting_files_page_selected_count, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.setting_files_page_title))
                    }
                },
                navigationIcon = {
                    if (isSelecting) {
                        // 选择模式：返回=退出选择
                        IconButton(onClick = { exitSelection() }) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.setting_files_page_exit_selection)
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (isSelecting) {
                        // 选择模式：全选 / 删除
                        IconButton(
                            onClick = {
                                selection = if (selectedIds.size == files.size) {
                                    emptySet()
                                } else {
                                    files.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.CheckmarkCircle01,
                                contentDescription = stringResource(R.string.setting_files_page_select_all)
                            )
                        }
                        IconButton(
                            onClick = {
                                val targets = files.filter { it.id in selectedIds }
                                if (targets.isNotEmpty()) {
                                    pendingBatchDelete = targets
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_files_page_batch_delete)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { showCleanSheet = true },
                            enabled = files.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Clean,
                                contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = {
                    selectedFolder = it
                    exitSelection()
                }
            )

            SourceFilterRow(
                selected = sourceFilter,
                onSelect = {
                    sourceFilter = it
                    exitSelection()
                }
            )

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Adaptive(minSize = 160.dp)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            selected = isSelecting && file.id in selectedIds,
                            isSelecting = isSelecting,
                            onToggleSelect = {
                                val current = selection.orEmpty().toMutableSet()
                                if (!current.add(file.id)) {
                                    current.remove(file.id)
                                }
                                selection = current
                            },
                            onLongPressSelect = {
                                selection = setOf(file.id)
                            },
                            onDelete = { pendingDelete = file },
                        )
                    }
                }
            }

            // 底部批量操作栏：选择模式下显示
            if (isSelecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { exitSelection() }) {
                        Text(stringResource(R.string.setting_files_page_cancel_action))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val targets = files.filter { it.id in selectedIds }
                        if (targets.isNotEmpty()) pendingBatchDelete = targets
                    }, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(R.string.setting_files_page_batch_delete))
                    }
                }
            }
        }
    }
}

private enum class CleanRange(val days: Int?) {
    DAYS_7(7),
    DAYS_14(14),
    DAYS_30(30),
    ALL(null),
}

@Composable
private fun CleanFilesSheet(
    selectedRange: CleanRange,
    onRangeSelected: (CleanRange) -> Unit,
    onClean: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_files_page_clean_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.setting_files_page_clean_range_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        CleanRange.entries.forEach { range ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRangeSelected(range) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                )
                Text(
                    text = range.days?.let {
                        stringResource(R.string.setting_files_page_clean_older_than_days, it)
                    } ?: stringResource(R.string.setting_files_page_clean_all),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        TextButton(
            onClick = onClean,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.setting_files_page_clean_action))
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.SKILLS -> stringResource(R.string.setting_files_page_folder_skills)
    FileFolders.FONTS -> stringResource(R.string.setting_files_page_folder_fonts)
    else -> folder
}

/** 来源筛选：全部 / 聊天附件 / 头像 */
@Composable
private fun SourceFilterRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val options = listOf<String?>(null, "chat", "avatar")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { source ->
            FilterChip(
                selected = selected == source,
                onClick = { onSelect(source) },
                label = {
                    Text(
                        when (source) {
                            null -> stringResource(R.string.setting_files_page_source_all)
                            "chat" -> stringResource(R.string.setting_files_page_source_chat)
                            "avatar" -> stringResource(R.string.setting_files_page_source_avatar)
                            else -> source
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    selected: Boolean,
    isSelecting: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (isSelecting) {
                    modifier
                        .border(
                            BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                }
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(onClick = onToggleSelect)
                } else {
                    modifier
                        .clickable(
                            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                            indication = null,
                            onClick = onLongPressSelect
                        )
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                CustomColors.listItemColors.containerColor
            }
        )
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 选择角标：选择模式下左上角复选框
                if (isSelecting) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                } else {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
