package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Archive02
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Bulb
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileMinus
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.knowledge.KnowledgeManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.isWorkspaceImagePath
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTabletAdaptation
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.utils.explainErrorText
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val DEFAULT_VISIBLE_COUNT = 3

internal enum class FileChangeStatus {
    ADDED,
    EDITED,
    REMOVED,
}

internal data class FileChange(
    val path: String,
    val status: FileChangeStatus,
)

/**
 * 文件变更卡片展开状态的进程级存储（key = messageId，UUID 全局唯一，天然隔离不同会话/消息）。
 * LazyColumn item 划出视口后组合被回收、remember 状态丢失，滚动经过后再次进入会重置为折叠；
 * 存进程级单例让滚动后仍保持用户展开/折叠意图（与工具气泡 toolBubbleExpanded 同模式）。
 */
private val editedFilesExpanded = mutableStateMapOf<String, Boolean>()

@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
    messageId: String,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    // 同步计算（含轻量预筛 + workspace_shell 流式字段提取）：卡片首帧即正确渲染，
    // 不会出现异步补全导致的"卡片迟到出现、item 高度突变"跳动
    // 进程级缓存（key = messageId + parts 引用锚点）：item 划出视口再回来不再重复解析大 JSON
    val fileChanges = remember(messageId, parts) { extractFileChangesCached(messageId, parts) }
    if (fileChanges.isEmpty) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val knowledgeManager: KnowledgeManager = koinInject()
    val toaster = LocalToaster.current

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importPath by remember { mutableStateOf<String?>(null) }

    // changes 为工具层限量后的展示列表（每类 ≤50）；计数徽章用 totals（真实总数，无上限报告时与列表口径一致）
    val changes = fileChanges.changes
    val addedFiles = remember(changes) { changes.filter { it.status == FileChangeStatus.ADDED }.map { it.path } }
    val editedFileList = remember(changes) { changes.filter { it.status == FileChangeStatus.EDITED }.map { it.path } }
    val removedFiles = remember(changes) { changes.filter { it.status == FileChangeStatus.REMOVED }.map { it.path } }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = selectedPath.also { selectedPath = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (area, relativePath) = resolveWorkspacePath(path)
                outputStream.use { output ->
                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                }
            }
        }
    }

    var expanded by remember(messageId) { mutableStateOf(editedFilesExpanded[messageId] ?: false) }
    val haptic = rememberHaptic()

    // 删除文件已不存在，点击直接提示，不弹操作菜单
    val deletedSet = remember(removedFiles) { removedFiles.toSet() }
    val deletedMessage = stringResource(R.string.workspace_file_change_deleted)
    val onChipClick: (String) -> Unit = { path ->
        when {
            path in deletedSet -> toaster.show(deletedMessage)
            // 所有文件（含图片）统一弹操作菜单，预览方式在菜单内按文件类型区分
            else -> selectedPath = path
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.lightTap()
                        expanded = !expanded
                        editedFilesExpanded[messageId] = expanded
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(
                        R.string.workspace_file_changes,
                        fileChanges.addedTotal + fileChanges.modifiedTotal + fileChanges.removedTotal
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (addedFiles.isNotEmpty()) {
                        Text(
                            text = "+${fileChanges.addedTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = DiffAddedColor,
                        )
                    }
                    if (editedFileList.isNotEmpty()) {
                        Text(
                            text = "~${fileChanges.modifiedTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (removedFiles.isNotEmpty()) {
                        Text(
                            text = "-${fileChanges.removedTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = DiffRemovedColor,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileChangeChipGroup(
                        title = stringResource(R.string.workspace_file_change_added),
                        paths = addedFiles,
                        icon = HugeIcons.FileAdd,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onSelect = onChipClick,
                    )
                    FileChangeChipGroup(
                        title = stringResource(R.string.workspace_file_change_edited),
                        paths = editedFileList,
                        icon = HugeIcons.Edit01,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onSelect = onChipClick,
                    )
                    FileChangeChipGroup(
                        title = stringResource(R.string.workspace_file_change_removed),
                        paths = removedFiles,
                        icon = HugeIcons.FileMinus,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onSelect = onChipClick,
                    )
                }
            }
        }
    }

    if (selectedPath != null) {
        val path = selectedPath!!
        val fileName = remember(path) { path.substringAfterLast('/') }
        ModalBottomSheet(
            onDismissRequest = { selectedPath = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        if (isWorkspaceImagePath(p)) {
                            // 图片走大图预览（导出到缓存后加载），文本类文件才进编辑器
                            scope.launch {
                                runCatching {
                                    val (area, relativePath) = resolveWorkspacePath(p)
                                    val dir = File(context.cacheDir, "workspace_edited_preview").apply { mkdirs() }
                                    val file = File(dir, p.substringAfterLast('/'))
                                    file.outputStream().use { output ->
                                        workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                    }
                                    previewImagePath = file.absolutePath
                                }.onFailure {
                                    toaster.show("预览失败: ${explainErrorText(it.message)}")
                                }
                            }
                        } else {
                            val (area, relativePath) = resolveWorkspacePath(p)
                            navController.navigate(
                                Screen.WorkspaceFileEditor(workspaceId, area.name, relativePath)
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileView,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(stringResource(R.string.common_preview), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        exportLauncher.launch(p.substringAfterLast('/'))
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(stringResource(R.string.common_export), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        // 跳到文件所在目录：解析出存储区 + 相对路径，去掉文件名取父目录；
                        // 文件名作 highlight 传给详情页，落地后滚动到该文件并高亮
                        val (area, relativePath) = resolveWorkspacePath(p)
                        val dir = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                        navController.navigate(
                            Screen.WorkspaceDetail(
                                id = workspaceId,
                                area = area.name,
                                path = dir,
                                highlight = relativePath.substringAfterLast('/').takeIf { it.isNotBlank() },
                            )
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(stringResource(R.string.workspace_file_change_locate), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        scope.launch {
                            runCatching {
                                val (area, relativePath) = resolveWorkspacePath(p)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                }
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
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(stringResource(R.string.common_share), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        scope.launch {
                            runCatching {
                                val (area, relativePath) = resolveWorkspacePath(p)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                }
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
                                context.startActivity(Intent.createChooser(intent, null))
                            }.onFailure {
                                toaster.show("打开失败: ${explainErrorText(it.message)}")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Link01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text("用其他应用打开", style = MaterialTheme.typography.titleMedium)
                    }
                }
                // 知识库只收文档，图片不提供导入入口
                if (!isWorkspaceImagePath(path)) {
                    Card(
                        onClick = {
                            val p = selectedPath ?: return@Card
                            importPath = p
                            selectedPath = null
                            showImportDialog = true
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Archive02,
                                contentDescription = null,
                                modifier = Modifier.padding(4.dp),
                            )
                            Text("导入到知识库", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    // 图片文件直接预览（点击图片变更 chip 时弹出大图）
    previewImagePath?.let { path ->
        ImagePreviewDialog(
            images = listOf(path),
            onDismissRequest = { previewImagePath = null },
        )
    }

    // 知识库选择对话框
    if (showImportDialog && importPath != null) {
        val path = importPath!!
        var kbList by remember { mutableStateOf<List<me.rerere.knowledge.data.entity.KnowledgeBaseEntity>>(emptyList()) }
        var loadingKbs by remember { mutableStateOf(true) }

        // 加载知识库列表
        remember(path) {
            scope.launch {
                try {
                    kbList = knowledgeManager.baseRepository.getAll().first()
                } catch (_: Exception) { }
                loadingKbs = false
            }
        }

        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importPath = null
            },
            title = { Text("选择知识库") },
            text = {
                if (loadingKbs) {
                    Text("加载中...")
                } else if (kbList.isEmpty()) {
                    Text("暂无知识库，请先创建知识库")
                } else {
                    LazyColumn {
                        items(kbList) { base ->
                            Card(
                                onClick = {
                                    showImportDialog = false
                                    importPath = null
                                    scope.launch {
                                        importFileToKnowledgeBase(
                                            context = context,
                                            workspaceRepository = workspaceRepository,
                                            workspaceId = workspaceId,
                                            filePath = path,
                                            knowledgeManager = knowledgeManager,
                                            kbId = base.id,
                                            toaster = toaster,
                                        )
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(base.name, style = MaterialTheme.typography.titleSmall)
                                    if (base.description.isNotBlank()) {
                                        Text(
                                            base.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importPath = null
                }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun importFileToKnowledgeBase(
    context: android.content.Context,
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    filePath: String,
    knowledgeManager: KnowledgeManager,
    kbId: String,
    toaster: com.dokar.sonner.ToasterState,
) {
    try {
        val fileName = filePath.substringAfterLast('/')
        val (area, relativePath) = resolveWorkspacePath(filePath)
        val destDir = File("${context.filesDir}/knowledge/${kbId}/raw").apply { mkdirs() }
        val destFile = File(destDir, fileName)

        withContext(Dispatchers.IO) {
            destFile.outputStream().use { output ->
                workspaceRepository.exportFile(workspaceId, area, relativePath, output)
            }
        }

        val fileType = fileName.substringAfterLast('.', "").lowercase()
        knowledgeManager.documentRepository.create(
            knowledgeBaseId = kbId,
            fileName = fileName,
            fileType = fileType,
            filePath = destFile.absolutePath,
            fileSize = destFile.length(),
        )

        toaster.show("已导入「${fileName}」到知识库，请在知识库中处理该文档")
    } catch (e: Exception) {
        toaster.show("导入失败: ${explainErrorText(e.message)}")
    }
}

private fun resolveWorkspacePath(path: String): Pair<WorkspaceStorageArea, String> {
    // 统一分隔符再去尾斜杠：历史消息里可能存相对入参或 Windows 风格分隔符
    val trimmed = path.trim().replace('\\', '/').trimEnd('/')
    return when {
        trimmed == "/workspace" || trimmed.startsWith("/workspace/") ->
            WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
        // 其余绝对路径按 Rootfs 内绝对路径落 LINUX 区（/tmp 等）
        trimmed.startsWith("/") -> WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
        // 相对路径（write/edit 相对 cwd 的原始入参，输出缺失时的回落）：cwd 恒在 /workspace 下，
        // 按文件区近似定位，避免误判成 LINUX 区导致 "Path does not exist"
        else -> WorkspaceStorageArea.FILES to trimmed.trimStart('/')
    }
}

/** workspace_shell 输出中我们关心的字段；其余大字段（stdout/stderr）由 ignoreUnknownKeys 流式跳过 */
@Serializable
private data class ShellOutput(
    val addedFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
    val removedFiles: List<String> = emptyList(),
    // 工具层对每个列表限量 50 条（防批量操作撑爆 JSON），超限时补真实总数；未限量/旧消息为 null，以数组长度为准
    val addedFilesTotal: Int? = null,
    val modifiedFilesTotal: Int? = null,
    val removedFilesTotal: Int? = null,
)

/**
 * 文件变更提取结果的进程级缓存（复用 Markdown.markdownParseCache 的 LRU 模式）。
 * key = 提取类型前缀 + messageId（UIMessage.id，流式全程稳定）；陈旧性靠 value 内的 parts 引用锚点：
 * 流式每 chunk 换新 parts 引用 → stale 重提取；消息定型后引用跨视口稳定 → 命中。
 * LazyColumn item 划出视口后 remember 失效、再划回重新组合；本缓存让"每次滑过都重新解析
 * 大 JSON"变为 O(1) 命中，配合 ChatList 滚动预取（warmMessageExtractions）消除首帧同步解析。
 */
/**
 * 工作区文件变更提取结果：changes 供 chip 展示（工具层限量后为前 50 条），
 * totals 为真实总数（有限量字段时来自工具输出，否则与 changes 推导口径一致），保证计数徽章不因限量失真。
 */
internal data class FileChangesResult(
    val changes: List<FileChange>,
    val addedTotal: Int,
    val modifiedTotal: Int,
    val removedTotal: Int,
) {
    val isEmpty: Boolean get() = changes.isEmpty()
}

private data class FileChangesCacheEntry(
    val partsRef: List<UIMessagePart>,
    val result: Any,
    val sizeKb: Int,
)

private val fileChangesCache = object : LruCache<String, FileChangesCacheEntry>(4 * 1024) {
    override fun sizeOf(key: String, value: FileChangesCacheEntry): Int = value.sizeKb
}

private fun estimatePartsSizeKb(parts: List<UIMessagePart>): Int {
    var chars = 0
    parts.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
        tool.output.filterIsInstance<UIMessagePart.Text>().forEach { chars += it.text.length }
    }
    return chars / 1024 + 1
}

private fun <T : Any> cachedExtract(
    key: String,
    parts: List<UIMessagePart>,
    extract: (List<UIMessagePart>) -> T,
): T {
    // 同一 key 的提取类型恒定：命中时按 T 转型安全（trusted folder 与 workspace 用不同 key 前缀）
    fileChangesCache.get(key)?.let { e -> if (e.partsRef === parts) return e.result as T }
    val result = extract(parts)
    fileChangesCache.put(key, FileChangesCacheEntry(parts, result, estimatePartsSizeKb(parts)))
    return result
}

/** 工作区文件变更提取的缓存入口（组合/预取共用） */
internal fun extractFileChangesCached(messageId: String, parts: List<UIMessagePart>): FileChangesResult =
    cachedExtract("workspace:$messageId", parts, ::extractFileChanges)

/** 信任文件夹文件变更提取的缓存入口（组合/预取共用） */
internal fun extractTrustedFolderChangesCached(messageId: String, parts: List<UIMessagePart>): List<FileChange> =
    cachedExtract("trusted:$messageId", parts, ::extractTrustedFolderChanges)

/** 供 ChatList 滚动预取：把消息的工具提取结果写入进程级缓存（返回值仅供调用，缓存副作用在内部） */
internal fun warmMessageExtractions(messageId: String, parts: List<UIMessagePart>) {
    if (parts.none { it is UIMessagePart.Tool }) return
    extractFileChangesCached(messageId, parts)
    extractTrustedFolderChangesCached(messageId, parts)
}

/**
 * 判断工具的 JSON 输出是否携带失败标记。
 * 结构化工具（workspace_* 文件类、trusted_folder_*、study 的 save/update/delete）失败时，
 * output 是一个含 "error" 键的 JSON（见 GenerationHandler.executeTool 的异常兜底与 errorResult）。
 * 命中则说明工具实际未生效，不计入变更展示，避免"改失败却显示已改"的假阳性。
 * 注意：workspace_shell 的 stdout 是命令自由输出，可能恰好含 "error" 字样，不走此判定。
 */
private fun isToolOutputError(tool: UIMessagePart.Tool): Boolean =
    tool.output.filterIsInstance<UIMessagePart.Text>().any { text ->
        // 精确匹配 JSON 键（"error":），避免误伤正常内容里出现"error"单词的情况
        text.text.contains("\"error\"")
    }

internal fun extractFileChanges(parts: List<UIMessagePart>): FileChangesResult {
    val changes = mutableListOf<FileChange>()
    var addedTotal = 0
    var modifiedTotal = 0
    var removedTotal = 0
    // 任一 shell 调用带有限量总数（*Total）时，徽章以累计总数为准；否则与旧口径一致按去重条目数
    var sawCappedTotal = false
    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isExecuted }
        .forEach { tool ->
            when (tool.toolName) {
                "workspace_write_file", "workspace_edit_file" -> {
                    // 失败（output 含 error）不计入变更，避免假阳性
                    if (isToolOutputError(tool)) return@forEach
                    // path 优先取工具输出的 resolved 绝对路径（Rootfs 口径）：工具入参允许相对 cwd 的
                    // 相对路径，原始入参直接拿去定位/预览会误判存储区；输出缺失（流式中途）回落 input
                    val outputJson = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstNotNullOfOrNull { part ->
                            runCatching { JsonInstant.parseToJsonElement(part.text).jsonObject }.getOrNull()
                        }
                    val path = outputJson?.get("path")?.jsonPrimitive?.contentOrNull
                        ?: tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val status = if (tool.toolName == "workspace_edit_file") {
                        FileChangeStatus.EDITED
                    } else {
                        val changeStatus = outputJson?.get("changeStatus")?.jsonPrimitive?.contentOrNull
                        if (changeStatus == "edited") FileChangeStatus.EDITED else FileChangeStatus.ADDED
                    }
                    if (status == FileChangeStatus.ADDED) addedTotal++ else modifiedTotal++
                    changes.add(FileChange(path, status))
                }

                "workspace_shell" -> {
                    val text = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?: return@forEach
                    // 轻量预筛：output 不含任一变更键时直接跳过，避免对超大 stdout 做解析
                    if (text.indexOf("addedFiles") < 0 &&
                        text.indexOf("modifiedFiles") < 0 &&
                        text.indexOf("removedFiles") < 0
                    ) return@forEach
                    // 反序列化到只含三个数组字段的 data class，而非完整 JSON 树：
                    // stdout/stderr 等大字段被 ignoreUnknownKeys 流式跳过、不构建大对象，
                    // 避免滚动到该卡片时在主线程整树解析造成掉帧
                    val output = runCatching {
                        JsonInstant.decodeFromString<ShellOutput>(text)
                    }.getOrNull() ?: return@forEach
                    // 数组本身已被工具层限量（每类 50 条），总数以 *Total 字段为准，保证徽章计数真实
                    addedTotal += output.addedFilesTotal ?: output.addedFiles.size
                    modifiedTotal += output.modifiedFilesTotal ?: output.modifiedFiles.size
                    removedTotal += output.removedFilesTotal ?: output.removedFiles.size
                    if (output.addedFilesTotal != null || output.modifiedFilesTotal != null ||
                        output.removedFilesTotal != null
                    ) {
                        sawCappedTotal = true
                    }
                    output.addedFiles.forEach { path ->
                        changes.add(FileChange(path, FileChangeStatus.ADDED))
                    }
                    output.modifiedFiles.forEach { path ->
                        changes.add(FileChange(path, FileChangeStatus.EDITED))
                    }
                    output.removedFiles.forEach { path ->
                        changes.add(FileChange(path, FileChangeStatus.REMOVED))
                    }
                }
            }
        }
    // 同一消息内同一路径可能多次出现，保留最后一次状态
    val deduped = changes.reversed().distinctBy { it.path }.reversed()
    return FileChangesResult(
        changes = deduped,
        addedTotal = if (sawCappedTotal) addedTotal else deduped.count { it.status == FileChangeStatus.ADDED },
        modifiedTotal = if (sawCappedTotal) modifiedTotal else deduped.count { it.status == FileChangeStatus.EDITED },
        removedTotal = if (sawCappedTotal) removedTotal else deduped.count { it.status == FileChangeStatus.REMOVED },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileChangeChipGroup(
    title: String,
    paths: List<String>,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onSelect: (String) -> Unit,
) {
    if (paths.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // 后变更的排前面：extractFileChanges 按工具执行顺序追加（先变更在前），此处反转展示
    val visiblePaths = if (expanded) paths.asReversed() else paths.asReversed().take(DEFAULT_VISIBLE_COUNT)
    val hasMore = paths.size > DEFAULT_VISIBLE_COUNT

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            visiblePaths.forEach { path ->
                val fileName = remember(path) { path.substringAfterLast('/') }
                Surface(
                    onClick = { onSelect(path) },
                    shape = RoundedCornerShape(50),
                    color = containerColor,
                    contentColor = contentColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (LocalTabletAdaptation.current) 280.dp else 200.dp),
                        )
                    }
                }
            }
            if (hasMore && !expanded) {
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "+${paths.size - DEFAULT_VISIBLE_COUNT}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private data class StudyToolConfig(
    val toolName: String,
    val inputField: String,
    val outputField: String,
    val labelPrefix: String,
    val icon: ImageVector,
    val screen: Screen,
)

// outputField：优先从工具输出读该字段；若输出里没有（如 save_wrong_question 输出不含 title），
// 回落读输入 inputField。delete_* 的输出统一带 title（见 StudyEditTools.executeDelete）。
private val STUDY_TOOL_CONFIGS = listOf(
    StudyToolConfig("save_vocabulary", "word", "word", "生词", HugeIcons.BookOpen01, Screen.VocabularyPanel),
    StudyToolConfig("save_note", "title", "title", "笔记", HugeIcons.Note01, Screen.NotesPanel),
    StudyToolConfig("save_wrong_question", "question", "title", "错题", HugeIcons.Alert01, Screen.WrongQuestionPanel),
    StudyToolConfig("save_knowledge_card", "concept", "concept", "知识点", HugeIcons.Bulb, Screen.KnowledgeCardPanel),
    StudyToolConfig("update_vocabulary", "word", "word", "生词", HugeIcons.BookOpen01, Screen.VocabularyPanel),
    StudyToolConfig("update_note", "title", "title", "笔记", HugeIcons.Note01, Screen.NotesPanel),
    StudyToolConfig("update_wrong_question", "question", "title", "错题", HugeIcons.Alert01, Screen.WrongQuestionPanel),
    StudyToolConfig("update_knowledge_card", "concept", "concept", "知识点", HugeIcons.Bulb, Screen.KnowledgeCardPanel),
    StudyToolConfig("delete_vocabulary", "word", "title", "已删生词", HugeIcons.BookOpen01, Screen.VocabularyPanel),
    StudyToolConfig("delete_note", "title", "title", "已删笔记", HugeIcons.Note01, Screen.NotesPanel),
    StudyToolConfig("delete_wrong_question", "question", "title", "已删错题", HugeIcons.Alert01, Screen.WrongQuestionPanel),
    StudyToolConfig("delete_knowledge_card", "concept", "title", "已删知识点", HugeIcons.Bulb, Screen.KnowledgeCardPanel),
)

internal data class StudyItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val screen: Screen,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyItemsList(parts: List<UIMessagePart>) {
    val navController = LocalNavController.current
    val studyItems = remember(parts) { extractStudyItems(parts) }
    if (studyItems.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val visibleItems = if (expanded) studyItems else studyItems.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = studyItems.size > DEFAULT_VISIBLE_COUNT

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleItems.forEach { item ->
            Surface(
                onClick = { navController.navigate(item.screen) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(item.icon, null, modifier = Modifier.size(16.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (LocalTabletAdaptation.current) 280.dp else 200.dp),
                    )
                }
            }
        }
        if (hasMore && !expanded) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "+${studyItems.size - DEFAULT_VISIBLE_COUNT}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 从已执行的学习工具（save/update/delete）提取要展示的条目。
 * 提取为纯函数便于单测；组合层 StudyItemsList 直接消费其结果。
 * 规则：
 *  - 失败（output 含 error）不计入，避免"删除失败却显示已删"的假阳性；
 *  - 优先读工具输出的标记字段（saved/updated/deleted 输出带 word/title/concept），
 *    输出里没有时（如 save_wrong_question 输出不含 title）回落读输入 key 字段，
 *    这样 update 只改 content/tags 不动 title 时也能显示，delete 也能显示。
 */
internal fun extractStudyItems(parts: List<UIMessagePart>): List<StudyItem> =
    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isExecuted }
        .mapNotNull { tool ->
            val config = STUDY_TOOL_CONFIGS.find { it.toolName == tool.toolName } ?: return@mapNotNull null
            if (isToolOutputError(tool)) return@mapNotNull null
            val output = tool.output.filterIsInstance<UIMessagePart.Text>()
                .firstOrNull()?.text
                ?.let { runCatching { JsonInstant.parseToJsonElement(it).jsonObject }.getOrNull() }
            val value = output?.get(config.outputField)
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: tool.inputAsJson().jsonObject[config.inputField]
                    ?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
            val label = if (config.toolName in listOf(
                    "save_wrong_question",
                    "update_wrong_question",
                    "delete_wrong_question",
                )
            ) {
                "${config.labelPrefix}: ${value.take(20)}"
            } else {
                "${config.labelPrefix}: $value"
            }
            StudyItem(key = "${config.toolName}:$value", label = label, icon = config.icon, screen = config.screen)
        }
        .distinctBy { it.key }

/**
 * 信任文件夹工具的「文件变更」卡片：复用工作区文件变更的展示逻辑（新增/编辑/删除分类 chip），
 * 在聊天气泡下方展示 AI 对信任文件夹的真实文件改动。点击 chip 打开对应文件预览。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrustedFolderEditedFilesList(parts: List<UIMessagePart>, messageId: String) {
    val changes = remember(messageId, parts) { extractTrustedFolderChangesCached(messageId, parts) }
    if (changes.isEmpty()) return

    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    // 信任文件夹编辑文件跳转按「当前助手绑定的项目」打开（工具编辑的是绑定项目的文件），点击时解析绑定
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    var expanded by remember(messageId) { mutableStateOf(editedFilesExpanded[messageId] ?: false) }
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    // 图片变更直接预览（content:// 加载大图），其余文件跳转编辑器
    var previewImageUri by remember { mutableStateOf<String?>(null) }

    fun openChangedFile(path: String) {
        val pid = settingsStore.settingsFlow.value.getCurrentAssistant().trustedFolderProjectId ?: run {
            toaster.show("当前助手未绑定信任文件夹，无法打开编辑的文件")
            return
        }
        if (isWorkspaceImagePath(path)) {
            scope.launch {
                runCatching {
                    trustedFolderRepository.contentUri(path, pid)
                }.onSuccess { uri ->
                    if (uri != null) previewImageUri = uri.toString()
                    else toaster.show("无法预览该图片")
                }.onFailure {
                    toaster.show("预览失败: ${explainErrorText(it.message)}")
                }
            }
        } else {
            navController.navigate(Screen.TrustedFolderEditor(pid, path))
        }
    }

    val addedFiles = remember(changes) { changes.filter { it.status == FileChangeStatus.ADDED }.map { it.path } }
    val editedFiles = remember(changes) { changes.filter { it.status == FileChangeStatus.EDITED }.map { it.path } }
    val removedFiles = remember(changes) { changes.filter { it.status == FileChangeStatus.REMOVED }.map { it.path } }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.lightTap()
                        expanded = !expanded
                        editedFilesExpanded[messageId] = expanded
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "信任文件夹文件变更 ${changes.size} 项",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (addedFiles.isNotEmpty()) {
                        Text("+${addedFiles.size}", style = MaterialTheme.typography.labelSmall, color = DiffAddedColor)
                    }
                    if (editedFiles.isNotEmpty()) {
                        Text("~${editedFiles.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (removedFiles.isNotEmpty()) {
                        Text("-${removedFiles.size}", style = MaterialTheme.typography.labelSmall, color = DiffRemovedColor)
                    }
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileChangeChipGroup(
                        title = "新增",
                        paths = addedFiles,
                        icon = HugeIcons.FileAdd,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onSelect = ::openChangedFile,
                    )
                    FileChangeChipGroup(
                        title = "编辑",
                        paths = editedFiles,
                        icon = HugeIcons.Edit01,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onSelect = ::openChangedFile,
                    )
                    FileChangeChipGroup(
                        title = "删除",
                        paths = removedFiles,
                        icon = HugeIcons.FileMinus,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onSelect = { toaster.show("该文件已被删除") },
                    )
                }
            }
        }
    }

    // 图片变更直接预览（content:// 加载大图）
    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }
}

/**
 * 从已执行的 trusted_folder 工具提取文件变更（新增/编辑/删除/重命名/移动）。
 * 新增文件夹不展示（folder 本身不是文件变更，用户只关心文件级变更）；
 * 重命名/移动以新路径记为 EDITED。
 */
internal fun extractTrustedFolderChanges(parts: List<UIMessagePart>): List<FileChange> {
    val changes = mutableListOf<FileChange>()
    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isExecuted }
        .forEach { tool ->
            when (tool.toolName) {
                "trusted_folder_write" -> {
                    // 失败（output 含 error）不计入变更，避免假阳性
                    if (isToolOutputError(tool)) return@forEach
                    val path = tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val changeStatus = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?.let { text ->
                            if (text.indexOf("changeStatus") < 0) null
                            else runCatching {
                                JsonInstant.parseToJsonElement(text).jsonObject["changeStatus"]
                                    ?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                    val status = if (changeStatus == "edited") {
                        FileChangeStatus.EDITED
                    } else {
                        FileChangeStatus.ADDED
                    }
                    changes.add(FileChange(path, status))
                }

                "trusted_folder_create_folder" -> {
                    // 新增文件夹不计入文件变更展示（非文件级变更）
                }

                "trusted_folder_edit" -> {
                    // 失败（output 含 error）不计入变更，避免假阳性
                    if (isToolOutputError(tool)) return@forEach
                    val path = tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    changes.add(FileChange(path, FileChangeStatus.EDITED))
                }

                "trusted_folder_delete" -> {
                    if (isToolOutputError(tool)) return@forEach
                    val path = tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    changes.add(FileChange(path, FileChangeStatus.REMOVED))
                }

                "trusted_folder_rename" -> {
                    if (isToolOutputError(tool)) return@forEach
                    val newPath = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?.let { text ->
                            runCatching {
                                JsonInstant.parseToJsonElement(text).jsonObject["path"]
                                    ?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                    if (newPath.isNullOrBlank()) return@forEach
                    changes.add(FileChange(newPath, FileChangeStatus.EDITED))
                }

                "trusted_folder_move" -> {
                    if (isToolOutputError(tool)) return@forEach
                    val newPath = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?.let { text ->
                            runCatching {
                                JsonInstant.parseToJsonElement(text).jsonObject["path"]
                                    ?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                    if (newPath.isNullOrBlank()) return@forEach
                    changes.add(FileChange(newPath, FileChangeStatus.EDITED))
                }
            }
        }
    // 同一消息内同一路径可能多次出现，保留最后一次状态
    return changes.reversed().distinctBy { it.path }.reversed()
}
