package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderEntry
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSearchMatch
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.formatFileTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** 信任文件夹文件浏览器：列目录 + 新建/重命名/移动/删除 + 文本编辑/图片预览 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedFolderDetailPage(
    projectId: String,
    initialPath: String = "",
) {
    val vm: TrustedFolderDetailVM = koinViewModel(parameters = { parametersOf(projectId, initialPath) })
    val state by vm.state.collectAsStateWithLifecycle()
    val projectName by vm.projectName.collectAsStateWithLifecycle()
    val repository = koinInject<TrustedFolderRepository>()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var renameTarget by remember { mutableStateOf<TrustedFolderEntry?>(null) }
    var moveTarget by remember { mutableStateOf<TrustedFolderEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<TrustedFolderEntry?>(null) }
    var imagePreview by remember { mutableStateOf<TrustedFolderEntry?>(null) }
    var createFileDialog by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }

    // 搜索 + 最近访问
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TrustedFolderSearchMatch>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recentFiles by repository.recentFilesFlow(projectId).collectAsStateWithLifecycle(initialValue = emptyList())

    // 多选批量操作
    var selecting by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }

    // 目录滚动位置：进子目录/跳编辑器再返回时，恢复到离开时的位置（每个目录各存一份）。
    // 存 VM 里（VM 绑定导航栈、跳编辑器返回仍存活）；捕获触发时的 path 避免 onDispose 读到时已是新值。
    val listState = rememberLazyListState()
    val currentPath = state.path
    DisposableEffect(currentPath) {
        onDispose {
            vm.scrollPositions[currentPath] =
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    // 进入目录（含返回上级/从编辑器返回）时，等加载完成后恢复该目录位置；首次进入回顶部。
    // key 只用 path：刷新当前目录（path 不变）不触发，不会把用户滚动位置拉回顶部。
    LaunchedEffect(state.path) {
        snapshotFlow { state.loading }.first { !it }
        // error / 空目录时不渲染 LazyColumn（listState 未绑定），滚动无意义且可能抛异常
        if (state.entries.isEmpty()) return@LaunchedEffect
        // LazyColumn 可能尚未完成绑定，滚动失败时静默忽略，下次进入再恢复
        try {
            val saved = vm.scrollPositions[state.path]
            if (saved != null) {
                val maxIndex = state.entries.size - 1
                listState.scrollToItem(saved.first.coerceIn(0, maxIndex), saved.second)
            } else {
                listState.scrollToItem(0)
            }
        } catch (e: Exception) {
            // 忽略：LazyColumn 未绑定或条目尚未就绪
        }
    }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    var batchMoveDialog by remember { mutableStateOf(false) }

    val currentDirName = state.path.ifBlank { "根目录" }

    fun exitSelection() {
        selecting = false
        selectedPaths.clear()
    }

    // 选择模式：返回先退出选择；子目录：返回上级目录；根目录：保持默认退出
    BackHandler(enabled = selecting || state.path.isNotEmpty()) {
        if (selecting) exitSelection() else vm.goUp()
    }

    // 从编辑器等返回时自动刷新（编辑器可能改了文件内容）
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    // 搜索防抖：输入停顿 300ms 后执行全文搜索（只搜本项目）
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(300)
        val results = runCatching { repository.search(relPath = "", query = q, projectId = projectId) }
            .getOrDefault(emptyList())
        // 输入可能已变化，只在仍是当前关键词时落结果
        if (q == searchQuery.trim()) {
            searchResults = results
            searchLoading = false
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                // 选择模式顶栏：已选数量 + 全选 + 批量移动/删除 + 退出
                LargeTopAppBar(
                    title = { Text("已选 ${selectedPaths.size} 项") },
                    navigationIcon = {
                        FilledTonalIconButton(
                            onClick = { exitSelection() },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            ),
                        ) {
                            Icon(HugeIcons.ArrowLeft01, "退出选择")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors,
                    actions = {
                        IconButton(onClick = {
                            if (selectedPaths.size == state.entries.size) {
                                selectedPaths.clear()
                            } else {
                                selectedPaths.clear()
                                selectedPaths.addAll(state.entries.map { it.path })
                            }
                        }) {
                            Icon(
                                HugeIcons.CheckmarkCircle01,
                                if (selectedPaths.size == state.entries.size) "取消全选" else "全选",
                            )
                        }
                        IconButton(onClick = { batchMoveDialog = true }) {
                            Icon(HugeIcons.Folder01, "移动")
                        }
                        IconButton(onClick = { batchDeleteConfirm = true }) {
                            Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(
                                text = projectName ?: "信任文件夹",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(currentDirName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        // 子目录中：左上角返回上级目录；根目录：返回上一页
                        if (state.path.isNotEmpty()) {
                            FilledTonalIconButton(
                                onClick = { vm.goUp() },
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = CustomColors.listItemColors.containerColor
                                ),
                            ) {
                                Icon(HugeIcons.ArrowLeft01, "返回上级目录")
                            }
                        } else {
                            BackButton()
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors,
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(HugeIcons.Refresh01, "刷新")
                        }
                        // 新建：统一 + 图标，下拉选择新建文件 / 新建文件夹
                        Box {
                            IconButton(onClick = { showCreateMenu = true }) {
                                Icon(HugeIcons.Add01, "新建")
                            }
                            DropdownMenu(
                                expanded = showCreateMenu,
                                onDismissRequest = { showCreateMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("新建文件") },
                                    leadingIcon = { Icon(HugeIcons.FileAdd, null) },
                                    onClick = { showCreateMenu = false; createFileDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("新建文件夹") },
                                    leadingIcon = { Icon(HugeIcons.FolderAdd, null) },
                                    onClick = { showCreateMenu = false; createFolderDialog = true },
                                )
                            }
                        }
                        // 设置放最右边
                        IconButton(onClick = { navController.navigate(Screen.TrustedFolderSettings(projectId)) }) {
                            Icon(HugeIcons.Settings03, "项目设置")
                        }
                    },
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // 搜索栏：仅普通模式（多选模式下隐藏，避免干扰勾选）
                if (!selecting) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("搜索此项目…") },
                        leadingIcon = { Icon(HugeIcons.Search01, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(HugeIcons.Cancel01, "清除")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    // 最近访问（非搜索态显示）
                    if (searchQuery.isBlank() && recentFiles.isNotEmpty()) {
                        RecentFilesRow(
                            files = recentFiles,
                            onClick = { path ->
                                scope.launch { repository.recordRecentFile(projectId, path) }
                                navController.navigate(Screen.TrustedFolderEditor(projectId, path))
                            },
                            onRemove = { path ->
                                scope.launch { repository.removeRecentFile(projectId, path) }
                            },
                        )
                    }
                }
                Box(Modifier.fillMaxSize().weight(1f)) {
                    when {
                        // 搜索态：结果列表
                        !selecting && searchQuery.isNotBlank() -> SearchResultsList(
                            results = searchResults,
                            loading = searchLoading,
                            onOpen = { path ->
                                scope.launch { repository.recordRecentFile(projectId, path) }
                                navController.navigate(Screen.TrustedFolderEditor(projectId, path))
                            },
                        )

                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                        state.error != null -> Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.refresh() }) { Text("重试") }
                        }

                        state.entries.isEmpty() -> Text(
                            text = "此目录为空",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            items(state.entries, key = { it.path }) { entry ->
                                FileEntryRow(
                                    entry = entry,
                                    selecting = selecting,
                                    selected = entry.path in selectedPaths,
                                    onClick = {
                                        when {
                                            // 选择模式下点击 = 勾选/取消
                                            selecting -> if (entry.path in selectedPaths) {
                                                selectedPaths.remove(entry.path)
                                            } else {
                                                selectedPaths.add(entry.path)
                                            }
                                            entry.isDirectory -> vm.open(entry)
                                            entry.detectFileType() == TrustedFolderFileType.IMAGE -> {
                                                scope.launch { repository.recordRecentFile(projectId, entry.path) }
                                                imagePreview = entry
                                            }
                                            entry.detectFileType() == TrustedFolderFileType.TEXT -> {
                                                scope.launch { repository.recordRecentFile(projectId, entry.path) }
                                                navController.navigate(Screen.TrustedFolderEditor(projectId, entry.path))
                                            }
                                            else -> toaster.show("暂不支持预览该类型文件", type = ToastType.Warning)
                                        }
                                    },
                                    onLongPressSelect = {
                                        selecting = true
                                        if (entry.path !in selectedPaths) selectedPaths.add(entry.path)
                                    },
                                    onRename = { renameTarget = entry },
                                    onMove = { moveTarget = entry },
                                    onDelete = { deleteTarget = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (createFileDialog) {
        NameInputDialog(
            title = "新建文件",
            placeholder = "文件名，如 notes/新笔记.md",
            onDismiss = { createFileDialog = false },
            onConfirm = { name ->
                vm.createFile(name)
                createFileDialog = false
            },
        )
    }
    if (createFolderDialog) {
        NameInputDialog(
            title = "新建文件夹",
            placeholder = "文件夹名",
            onDismiss = { createFolderDialog = false },
            onConfirm = { name ->
                vm.createFolder(name)
                createFolderDialog = false
            },
        )
    }

    renameTarget?.let { target ->
        NameInputDialog(
            title = "重命名",
            initialValue = target.name,
            placeholder = "新名称",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.rename(target, name)
                renameTarget = null
            },
        )
    }

    moveTarget?.let { target ->
        TrustedFolderMoveTargetSheet(
            projectId = projectId,
            sources = listOf(target),
            onSelectTarget = { dir ->
                vm.moveTo(target, dir)
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "删除${if (target.isDirectory) "文件夹" else "文件"}",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                vm.delete(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            text = { Text("确定删除「${target.name}」吗？此操作会删除手机上的真实文件，且不可恢复。") },
        )
    }

    // 多选批量删除
    if (batchDeleteConfirm) {
        val entries = state.entries.filter { it.path in selectedPaths }
        RikkaConfirmDialog(
            show = true,
            title = "删除 ${entries.size} 项",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                vm.deleteEntries(entries)
                exitSelection()
                batchDeleteConfirm = false
            },
            onDismiss = { batchDeleteConfirm = false },
            text = { Text("确定删除选中的 ${entries.size} 项吗？此操作会删除手机上的真实文件，且不可恢复。") },
        )
    }

    // 多选批量移动
    if (batchMoveDialog) {
        val entries = state.entries.filter { it.path in selectedPaths }
        TrustedFolderMoveTargetSheet(
            projectId = projectId,
            sources = entries,
            onSelectTarget = { dir ->
                vm.moveEntries(entries, dir)
                exitSelection()
                batchMoveDialog = false
            },
            onDismiss = { batchMoveDialog = false },
        )
    }

    imagePreview?.let { entry ->
        var contentUri by remember(entry.path) { mutableStateOf<String?>(null) }
        var uriError by remember(entry.path) { mutableStateOf(false) }
        LaunchedEffect(entry.path) {
            val uri = try {
                repository.contentUri(entry.path, projectId)
            } catch (e: Exception) {
                null
            }
            contentUri = uri?.toString()
            uriError = contentUri == null
        }
        when {
            // 复用全局图片预览（缩放/翻页/保存），直接加载 content:// URI
            contentUri != null -> ImagePreviewDialog(
                images = listOf(contentUri!!),
                onDismissRequest = { imagePreview = null },
            )

            uriError -> AlertDialog(
                onDismissRequest = { imagePreview = null },
                title = { Text("无法预览图片") },
                text = { Text("图片文件不可访问或已失效。") },
                confirmButton = {
                    TextButton(onClick = { imagePreview = null }) { Text("关闭") }
                },
            )

            else -> AlertDialog(
                onDismissRequest = { imagePreview = null },
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                },
                confirmButton = {
                    TextButton(onClick = { imagePreview = null }) { Text("关闭") }
                },
            )
        }
    }
}

/** 文件条目行：图标 + 名称/元信息 + 「更多」操作菜单；长按进入多选 */
@Composable
private fun FileEntryRow(
    entry: TrustedFolderEntry,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPressSelect: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // 未进入多选时，长按进入多选并选中该项
                onLongClick = { if (!selecting) onLongPressSelect() },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Icon(
            imageVector = entry.entryIcon(),
            contentDescription = if (entry.isDirectory) "文件夹" else "文件",
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 文件 = 时间｜占用；文件夹 = 时间丨n项（不统计占用，避免进目录卡顿）
                text = if (entry.isDirectory) {
                    "${entry.updatedAt.formatFileTime()} 丨 ${entry.childCount}项"
                } else {
                    "${entry.updatedAt.formatFileTime()} ｜ ${entry.sizeBytes.fileSizeToString()}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 选择模式下用顶栏的批量操作，隐藏行内菜单
        if (!selecting) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreHorizontal, "更多操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("移动到…") },
                        leadingIcon = { Icon(HugeIcons.Folder01, null) },
                        onClick = { menuExpanded = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialValue: String = "",
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

enum class TrustedFolderFileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

fun TrustedFolderEntry.detectFileType(): TrustedFolderFileType {
    if (isDirectory) return TrustedFolderFileType.TEXT
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTENSIONS -> TrustedFolderFileType.IMAGE
        ext.isEmpty() || ext in TEXT_EXTENSIONS -> TrustedFolderFileType.TEXT
        else -> TrustedFolderFileType.OTHER
    }
}

private fun TrustedFolderEntry.entryIcon() = when {
    isDirectory -> HugeIcons.Folder01
    detectFileType() == TrustedFolderFileType.IMAGE -> HugeIcons.Image01
    else -> HugeIcons.File01
}

/** 最近访问文件横向条：标题 + 可点击 chips（文件名），点击重新打开，右侧 × 手动移除 */
@Composable
private fun RecentFilesRow(
    files: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            Text(
                text = "最近访问",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(files) { path ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onClick(path) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = path.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        IconButton(
                            onClick = { onRemove(path) },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                HugeIcons.Cancel01,
                                "从最近访问移除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果列表：加载转圈 / 空态 / 命中行列表（path + 行号 + 行内容） */
@Composable
private fun SearchResultsList(
    results: List<TrustedFolderSearchMatch>,
    loading: Boolean,
    onOpen: (String) -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "没有匹配的结果",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(results, key = { "${it.path}:${it.line}" }) { match ->
                ListItem(
                    headlineContent = {
                        Text(match.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = "第 ${match.line} 行 · ${match.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.File01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onOpen(match.path) },
                )
            }
        }
    }
}
