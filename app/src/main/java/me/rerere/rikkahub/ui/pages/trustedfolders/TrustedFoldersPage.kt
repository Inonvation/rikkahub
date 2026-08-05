package me.rerere.rikkahub.ui.pages.trustedfolders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.ToggleOff
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderProject
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 信任文件夹项目页：项目列表 + 添加（系统文件选择器）+ 激活切换 + 删除 + 审批开关设置。
 * 审批设置独立于主设置页，按需求放在本界面内。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedFoldersPage() {
    val vm = koinViewModel<TrustedFoldersVM>()
    val repository = koinInject<TrustedFolderRepository>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TrustedFolderProject?>(null) }
    var deleteTarget by remember { mutableStateOf<TrustedFolderProject?>(null) }
    var activateTarget by remember { mutableStateOf<TrustedFolderProject?>(null) }
    var deactivateTarget by remember { mutableStateOf<TrustedFolderProject?>(null) }

    // 系统文件选择器：选一个目录 → 持久授权 → 弹出项目名输入
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            toaster.show("无法持久授权，请重试", type = ToastType.Error)
            return@rememberLauncherForActivityResult
        }
        pendingUri = uri
        pendingName = treeDisplayName(context, uri) ?: ""
        showAddDialog = true
    }

    LaunchedEffect(Unit) {
        vm.message.collect { toaster.show(it) }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("信任文件夹")
                        Text("AI 可读写你信任的真实文件夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(HugeIcons.FolderAdd, "添加信任文件夹")
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.projects.isEmpty()) {
                item {
                    Text(
                        text = "还没有信任的文件夹。点击右上角 + 选择一个手机上的文件夹（如 Obsidian 笔记库），AI 就可在你确认后读写它。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                    )
                }
            } else {
                // 项目按激活状态分区：激活的在上（当前项目），其余在下（其他项目），分隔线区分
                val activeProjects = settings.projects.filter { it.id == settings.activeProjectId }
                val otherProjects = settings.projects.filter { it.id != settings.activeProjectId }
                val projectItem: @Composable (TrustedFolderProject) -> Unit = { project ->
                    val isActive = project.id == settings.activeProjectId
                    ProjectCard(
                        project = project,
                        isActive = isActive,
                        isAuthorized = remember(project.treeUri) { repository.isAuthorized(project) },
                        onClick = {
                            navController.navigate(Screen.TrustedFolderDetail(project.id, ""))
                        },
                        onActivate = { activateTarget = project },
                        onDeactivate = { deactivateTarget = project },
                        onRename = { renameTarget = project },
                        onDelete = { deleteTarget = project },
                    )
                }

                if (activeProjects.isNotEmpty()) {
                    item {
                        Text(
                            text = "当前项目",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    items(activeProjects, key = { it.id }) { projectItem(it) }
                }
                if (otherProjects.isNotEmpty()) {
                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    item {
                        Text(
                            text = "其他项目",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp),
                        )
                    }
                    items(otherProjects, key = { it.id }) { projectItem(it) }
                }
            }
        }
    }

    if (showAddDialog && pendingUri != null) {
        AddProjectDialog(
            initialName = pendingName,
            onDismiss = { showAddDialog = false; pendingUri = null },
            onConfirm = { name ->
                vm.addProject(name, pendingUri.toString())
                showAddDialog = false
                pendingUri = null
            },
        )
    }

    renameTarget?.let { target ->
        RenameProjectDialog(
            project = target,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.renameProject(target.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "删除项目",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                vm.removeProject(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            text = {
                Text("确定移除项目「${target.name}」吗？此操作只移除 App 中的映射，不会删除手机上的文件夹内容。")
            },
        )
    }

    activateTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "激活项目",
            confirmText = "激活",
            dismissText = "取消",
            onConfirm = {
                vm.setActive(target.id)
                activateTarget = null
            },
            onDismiss = { activateTarget = null },
            text = {
                Text("将「${target.name}」设为激活项目？激活后 AI 将读写该文件夹。同一时间只能激活一个项目。")
            },
        )
    }

    deactivateTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "取消激活",
            confirmText = "取消激活",
            dismissText = "返回",
            onConfirm = {
                vm.setActive(null)
                deactivateTarget = null
            },
            onDismiss = { deactivateTarget = null },
            text = {
                Text("取消激活「${target.name}」？取消后 AI 将无法读写该文件夹，直到你重新激活。")
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    project: TrustedFolderProject,
    isActive: Boolean,
    isAuthorized: Boolean,
    onClick: () -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧统一文件夹图标：激活态由卡片底色 + 副标题文案体现，不额外放对勾
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isActive && isAuthorized -> "当前激活 · AI 可读写 · 点击进入"
                        isActive -> "当前激活 · 授权已失效，请重新信任"
                        isAuthorized -> "未激活 · 点 ⋮ 激活 · 点击卡片进入"
                        else -> "授权已失效，请删除后重新添加"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAuthorized) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 已激活：取消激活按钮保持在卡片右侧（主题色圆底对勾），点击弹二次确认
            if (isActive) {
                IconButton(onClick = onDeactivate) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.CheckmarkCircle01,
                            contentDescription = "取消激活",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            // 三点菜单：激活（仅未激活时，位于重命名之上）/ 重命名 / 删除
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!isActive) {
                        DropdownMenuItem(
                            text = { Text("激活") },
                            leadingIcon = { Icon(HugeIcons.ToggleOff, null) },
                            onClick = { menuExpanded = false; onActivate() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProjectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("信任该文件夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("项目名称") },
                placeholder = { Text("如：Obsidian 笔记") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("信任") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RenameProjectDialog(
    project: TrustedFolderProject,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名项目") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("项目名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 从 tree URI 读取系统展示的目录名（如 Obsidian 库名），失败返回 null */
private fun treeDisplayName(context: Context, uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
    context.contentResolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()
