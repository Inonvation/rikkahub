package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkSquare02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAppLifecycleState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.formatFileTime
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

@Composable
fun WorkspaceDetailPage(
    id: String,
    initialArea: String? = null,
    initialPath: String? = null,
    initialHighlight: String? = null,
) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = {
        parametersOf(
            id,
            initialArea?.let { runCatching { WorkspaceStorageArea.valueOf(it) }.getOrNull() },
            initialPath.orEmpty(),
            initialHighlight?.takeIf { it.isNotBlank() },
        )
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val installToolsState by vm.installToolsState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var deletePermanentTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    // 多选模式：selecting 为模式开关，selectedPaths 为已选 path 集合（相对当前存储区根）
    var selecting by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 批量操作目标与移动弹窗状态
    var batchTrashTargets by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    var batchDeleteTargets by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    var moveSources by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var showMoveTargetPicker by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createDialogDirectory by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    /**
     * 用系统"其他应用打开": 导出到 cache → FileProvider → ACTION_VIEW 选择器。
     * 任意类型文件均可走此路径(文本/图片默认有自己的内建打开, 菜单里仍提供外部打开兜底);
     * 与 onOpen 的 OTHER 分支共用同一流程, 抽出来避免三处重复。
     */
    fun openInSystemApp(entry: WorkspaceFileEntry) {
        if (entry.isDirectory) return
        vm.exportToCacheFile(entry, context.cacheDir) { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                file.extension.lowercase()
            ) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }

    fun exitSelect() {
        selecting = false
        selectedPaths = emptySet()
    }

    fun toggleSelect(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun enterSelect(path: String) {
        selecting = true
        selectedPaths = selectedPaths + path
    }

    fun toggleSelectAll() {
        selectedPaths = if (selectedPaths.size == state.entries.size) {
            emptySet()
        } else {
            state.entries.map { it.path }.toSet()
        }
    }

    // 当前目录下已选中的条目（供批量操作使用）
    val selectedEntries = state.entries.filter { it.path in selectedPaths }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }
    // 工作区备份/恢复: 备份导出 zip, 恢复导入 zip(覆盖前需确认)
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.backupTo(outputStream)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingRestoreUri = uri
    }

    BackHandler(enabled = pagerState.currentPage == 0 && state.path.isNotBlank()) {
        vm.goUp()
    }

    // 多选模式下返回键先退出多选（定义在 goUp 之后，优先级更高）
    BackHandler(enabled = selecting) { exitSelect() }
    // 切换存储区/目录时自动退出多选
    LaunchedEffect(state.area, state.path) {
        exitSelect()
    }
    // 滑动/切换到"设置"页时退出多选: 浮动操作条与"完成"按钮都只在文件页有意义,
    // 防止多选态泄漏到设置页后无退出入口
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) exitSelect()
    }

    // 从编辑器等覆盖页面返回时自动刷新文件列表(保存后大小/时间变化即时可见)。
    // 首次组合也会触发一次 refresh, 与 VM.init 幂等, 无副作用。
    val lifecycleState = rememberAppLifecycleState()
    LaunchedEffect(lifecycleState.value) {
        if (lifecycleState.value == Lifecycle.State.RESUMED) vm.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 0) {
                        if (selecting) {
                            TextButton(onClick = { exitSelect() }) {
                                Text("完成")
                            }
                        } else {
                            // 多选入口 icon 化(长按卡片同样可进入), 少占标题宽度
                            IconButton(onClick = { selecting = true }) {
                                Icon(
                                    HugeIcons.CheckmarkSquare02,
                                    contentDescription = "多选",
                                )
                            }
                            Box {
                                IconButton(onClick = { showCreateMenu = true }) {
                                    Icon(
                                        HugeIcons.Add01,
                                        contentDescription = "新建",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showCreateMenu,
                                    onDismissRequest = { showCreateMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("新建文件") },
                                        leadingIcon = {
                                            Icon(HugeIcons.File02, contentDescription = null)
                                        },
                                        onClick = {
                                            showCreateMenu = false
                                            createDialogDirectory = false
                                            showCreateDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("新建目录") },
                                        leadingIcon = {
                                            Icon(HugeIcons.Folder01, contentDescription = null)
                                        },
                                        onClick = {
                                            showCreateMenu = false
                                            createDialogDirectory = true
                                            showCreateDialog = true
                                        },
                                    )
                                    // 刷新移入新建菜单: 顶栏常驻 icon 减少(刷新低频), 保留功能入口
                                    DropdownMenuItem(
                                        text = { Text("刷新当前目录") },
                                        leadingIcon = {
                                            Icon(HugeIcons.Refresh01, contentDescription = null)
                                        },
                                        onClick = {
                                            showCreateMenu = false
                                            vm.refresh()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导入手机文件") },
                                        leadingIcon = {
                                            Icon(HugeIcons.FileImport, contentDescription = null)
                                        },
                                        onClick = {
                                            showCreateMenu = false
                                            filePicker.launch(arrayOf("*/*"))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            // bottomBar 恒为 NavigationBar: 多选操作条改为浮动层(见 content 内 AnimatedVisibility),
            // innerPadding 不再随多选状态突变 → 列表 viewport 稳定, 进出多选不跳动
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = {
                        if (selecting) exitSelect()
                        scope.launch { pagerState.animateScrollToPage(0) }
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = {
                        if (selecting) exitSelect()
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onNavigatePath = { vm.navigate(it) },
                    onResolveImage = { entry, area -> vm.resolveImageFile(entry, area) },
                    selecting = selecting,
                    selectedPaths = selectedPaths,
                    onToggleSelect = { toggleSelect(it.path) },
                    onLongPressSelect = { enterSelect(it.path) },
                    onMove = { entry ->
                        moveSources = listOf(entry)
                        showMoveTargetPicker = true
                    },
                    onRename = if (state.area == WorkspaceStorageArea.FILES) {
                        { entry -> renameTarget = entry }
                    } else null,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                    // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> openInSystemApp(entry)
                            }
                        }
                    },
                    onOpenWithSystemApp = { entry ->
                        if (!entry.isDirectory) openInSystemApp(entry)
                    },
                    onDelete = { deleteTarget = it },
                    onDeletePermanently = { deletePermanentTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )

                1 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    installToolsState = installToolsState,
                    onInstallTools = vm::installCommonTools,
                    onBackup = { backupLauncher.launch("${state.workspace?.name ?: "workspace"}_backup.zip") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    onOpenTrash = { navController.navigate(Screen.WorkspaceTrash(id)) },
                    onToolApprovalChange = vm::setToolApproval,
                )
            }
        }

            // 多选浮动操作条: 悬浮于 NavigationBar 之上, 不参与布局 → 进出多选 viewport 稳定
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                enter = slideInVertically(initialOffsetY = { it * 2 }),
                exit = slideOutVertically(targetOffsetY = { it * 2 }),
            ) {
                SelectActionBar(
                    selectedCount = selectedPaths.size,
                    totalCount = state.entries.size,
                    onSelectAll = { toggleSelectAll() },
                    onMove = {
                        moveSources = selectedEntries
                        showMoveTargetPicker = true
                    },
                    onTrash = { batchTrashTargets = selectedEntries },
                    onDelete = { batchDeleteTargets = selectedEntries },
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (showCreateDialog) {
        CreateEntryDialog(
            initialDirectory = createDialogDirectory,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, isDirectory ->
                if (isDirectory) vm.createDirectory(name) else vm.createFile(name)
                showCreateDialog = false
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                vm.rename(target, newName)
                renameTarget = null
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("恢复工作区文件") },
            text = { Text("将从备份 zip 覆盖当前 files 区内容, 现有文件将被替换。确定继续吗?") },
            confirmButton = {
                TextButton(onClick = {
                    context.contentResolver.openInputStream(uri)?.let { vm.restoreFrom(it) }
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = "移入回收站",
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text("将移入回收站，可在回收站中恢复。")
        }
    }

    deletePermanentTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = "彻底删除",
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.deletePermanently(entry)
                deletePermanentTarget = null
            },
            onDismiss = { deletePermanentTarget = null },
        ) {
            Text("彻底删除后无法恢复，确定删除 ${entry.path} 吗？")
        }
    }

    batchTrashTargets?.let { targets ->
        RikkaConfirmDialog(
            show = true,
            title = "移入回收站",
            confirmText = "移入回收站",
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.trashEntries(targets)
                batchTrashTargets = null
                exitSelect()
            },
            onDismiss = { batchTrashTargets = null },
        ) {
            Text("将选中的 ${targets.size} 项移入回收站，可在回收站中恢复。")
        }
    }

    batchDeleteTargets?.let { targets ->
        RikkaConfirmDialog(
            show = true,
            title = "彻底删除",
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.deleteEntries(targets)
                batchDeleteTargets = null
                exitSelect()
            },
            onDismiss = { batchDeleteTargets = null },
        ) {
            Text("彻底删除后无法恢复，确定删除选中的 ${targets.size} 项吗？")
        }
    }

    if (showMoveTargetPicker) {
        WorkspaceMoveTargetPickerSheet(
            workspaceId = id,
            area = state.area,
            sources = moveSources,
            onSelectTarget = { targetDir ->
                vm.moveEntries(moveSources, targetDir)
                showMoveTargetPicker = false
                exitSelect()
            },
            onDismiss = { showMoveTargetPicker = false },
        )
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    installToolsState: InstallToolsState,
    onInstallTools: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onOpenTrash: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    // rootfs 就绪后可一键安装常用工具链, 省去 AI 每次开场 apt install
                    if (rootfsReady) {
                        FilledTonalButton(
                            onClick = onInstallTools,
                            enabled = !installToolsState.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Tools, contentDescription = null)
                            Text(
                                text = if (installToolsState.running) {
                                    "安装常用工具中…"
                                } else {
                                    "安装常用工具 (python/git/curl)"
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        installToolsState.error?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "数据备份",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "备份工作区文件、目录结构与 .agent 配置，不含 rootfs 系统环境（rootfs 可重新安装，无需备份）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = onBackup,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(HugeIcons.Download01, contentDescription = null)
                            Text("备份", modifier = Modifier.padding(start = 6.dp))
                        }
                        FilledTonalButton(
                            onClick = onRestore,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                            Text("恢复", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "回收站",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "移入回收站的文件/目录保存在本工作区 .trash 内，可恢复或彻底删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onOpenTrash,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = null)
                        Text("管理回收站", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onNavigatePath: (String) -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry, WorkspaceStorageArea) -> File?,
    selecting: Boolean,
    selectedPaths: Set<String>,
    onToggleSelect: (WorkspaceFileEntry) -> Unit,
    onLongPressSelect: (WorkspaceFileEntry) -> Unit,
    onMove: (WorkspaceFileEntry) -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onOpenWithSystemApp: (WorkspaceFileEntry) -> Unit,
    onRename: ((WorkspaceFileEntry) -> Unit)? = null,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onDeletePermanently: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    val listState = rememberLazyListState()

    // 定位高亮：目录内容就绪后滚动到目标文件使其可见（前两项固定是区选择器与路径栏）
    LaunchedEffect(state.entries, state.highlightPath) {
        val highlight = state.highlightPath ?: return@LaunchedEffect
        val index = state.entries.indexOfFirst { it.name == highlight || it.path == highlight }
        if (index >= 0) listState.animateScrollToItem(index + 2)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorkspaceAreaSelector(
                    selected = state.area,
                    onSelected = onSelectArea,
                )
            }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
                onNavigate = onNavigatePath,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        // 目录加载中且无旧数据(首进/切换未命中缓存): 骨架占位, 替代空白闪烁
        if (state.loading && state.entries.isEmpty() && state.error == null) {
            items(count = SKELETON_ROW_COUNT) {
                SkeletonFileRow()
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                area = state.area,
                onResolveImage = { onResolveImage(entry, state.area) },
                highlighted = entry.name == state.highlightPath || entry.path == state.highlightPath,
                selecting = selecting,
                selected = entry.path in selectedPaths,
                onToggleSelect = { onToggleSelect(entry) },
                onLongPressSelect = { onLongPressSelect(entry) },
                onOpen = { onOpen(entry) },
                onOpenWithSystemApp = { onOpenWithSystemApp(entry) },
                onRename = onRename?.let { callback -> { callback(entry) } },
                onDelete = { onDelete(entry) },
                onDeletePermanently = { onDeletePermanently(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
                onMove = { onMove(entry) },
            )
        }
        }
        // 已有旧列表时的刷新指示: overlay 顶部细条, 不参与布局 → 列表零位移
        if (state.loading && state.entries.isNotEmpty() && state.error == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }
    }
}

/** 目录加载骨架行数: 约一屏可见量即可 */
private const val SKELETON_ROW_COUNT = 6

@Composable
private fun SkeletonFileRow() {
    // 静态灰块骨架(不做 shimmer 动画, 避免加载中的频闪观感)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val listState = rememberLazyListState()

    // 目录变化时滚到最右(当前目录), 深目录下保证当前路径可见而不是停在开头
    LaunchedEffect(path) {
        listState.animateScrollToItem(segments.size * 2)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 根目录 crumb: 非当前路径时点击回根
            item(key = "root") {
                PathCrumb(
                    text = "根目录",
                    active = segments.isEmpty(),
                    onClick = { onNavigate("") },
                )
            }
            segments.forEachIndexed { index, seg ->
                // 祖先段可直接跳转; 当前段(最后一段)高亮且不可点
                val prefix = segments.take(index + 1).joinToString("/")
                val isLast = index == segments.lastIndex
                item(key = "sep-$index") {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                item(key = prefix) {
                    PathCrumb(
                        text = seg,
                        active = isLast,
                        onClick = { onNavigate(prefix) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PathCrumb(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !active, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .widthIn(max = 160.dp),
    )
}

@Composable
private fun CreateEntryDialog(
    initialDirectory: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (name: String, isDirectory: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isDirectory by remember { mutableStateOf(initialDirectory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isDirectory,
                        onClick = { isDirectory = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("文件") }
                    SegmentedButton(
                        selected = isDirectory,
                        onClick = { isDirectory = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("目录") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isDirectory) "目录名" else "文件名") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), isDirectory) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    onResolveImage: suspend () -> File?,
    selecting: Boolean,
    selected: Boolean,
    highlighted: Boolean = false,
    onToggleSelect: () -> Unit,
    onLongPressSelect: () -> Unit,
    onOpen: () -> Unit,
    onOpenWithSystemApp: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onDeletePermanently: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) area else null,
        key2 = if (isImage) entry.path else null,
        key3 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    // 定位高亮：primaryContainer 底色渐入，超时清除后渐隐回默认色
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceBright
        },
        animationSpec = tween(durationMillis = 450),
        label = "workspaceFileHighlightContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 450),
        label = "workspaceFileHighlightContent",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                // 未进入多选时，长按进入多选并选中该项
                onLongClick = { if (!selecting) onLongPressSelect() },
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (isImage) {
                val context = LocalContext.current
                val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                    imageFile?.let {
                        ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                            .build()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    imageRequest?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 文件 = 时间|占用；文件夹 = 时间|n项（不统计占用，避免进目录卡顿）。
                    // 分隔统一用半角 |（旧版目录/文件行各用不同码位的全角竖线 丨U+4E28 / ｜U+FF5C，字体 fallback 下宽度不一致）
                    text = if (entry.isDirectory) {
                        "${entry.updatedAt.formatFileTime()} | ${entry.childCount}项"
                    } else {
                        "${entry.updatedAt.formatFileTime()} | ${entry.sizeBytes.fileSizeToString()}"
                    },
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
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("用其他应用打开") },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Upload02,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenWithSystemApp()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Edit01,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("移动到…") },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移入回收站", color = MaterialTheme.colorScheme.error) },
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
                    DropdownMenuItem(
                        text = { Text("彻底删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeletePermanently()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
) {
    // 浮动单行操作条(悬浮于 NavigationBar 上方, 不占 Scaffold innerPadding):
    // 移动保持中性色; 回收站(可恢复的软删)与彻底删除用 error 容器色提示危险;
    // "完成/退出"由顶栏承担, 这里不再重复退出按钮。
    HorizontalFloatingToolbar(expanded = true) {
        Text(
            text = "已选 $selectedCount 项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Tooltip(
            tooltip = { Text(if (selectedCount == totalCount) "取消全选" else "全选") },
        ) {
            IconButton(onClick = onSelectAll) {
                Icon(HugeIcons.CursorPointer01, contentDescription = null)
            }
        }
        Tooltip(tooltip = { Text("移动") }) {
            IconButton(onClick = onMove, enabled = selectedCount > 0) {
                Icon(HugeIcons.Folder01, contentDescription = null)
            }
        }
        Tooltip(tooltip = { Text("移入回收站") }) {
            FilledTonalIconButton(
                onClick = onTrash,
                enabled = selectedCount > 0,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(HugeIcons.Delete01, contentDescription = null)
            }
        }
        Tooltip(tooltip = { Text("彻底删除") }) {
            FilledTonalIconButton(
                onClick = onDelete,
                enabled = selectedCount > 0,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(HugeIcons.Delete02, contentDescription = null)
            }
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
