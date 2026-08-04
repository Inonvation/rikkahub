package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.knowledge.KnowledgeManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.context.LocalNavController
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

private enum class FileChangeStatus {
    ADDED,
    EDITED,
    REMOVED,
}

private data class FileChange(
    val path: String,
    val status: FileChangeStatus,
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    val fileChanges = remember(parts) { extractFileChanges(parts) }
    if (fileChanges.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val knowledgeManager: KnowledgeManager = koinInject()
    val toaster = LocalToaster.current

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importPath by remember { mutableStateOf<String?>(null) }

    val addedFiles = remember(fileChanges) { fileChanges.filter { it.status == FileChangeStatus.ADDED }.map { it.path } }
    val editedFileList = remember(fileChanges) { fileChanges.filter { it.status == FileChangeStatus.EDITED }.map { it.path } }
    val removedFiles = remember(fileChanges) { fileChanges.filter { it.status == FileChangeStatus.REMOVED }.map { it.path } }

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

    var expanded by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()

    // 删除文件已不存在，点击直接提示，不弹操作菜单
    val deletedSet = remember(removedFiles) { removedFiles.toSet() }
    val deletedMessage = stringResource(R.string.workspace_file_change_deleted)
    val onChipClick: (String) -> Unit = { path ->
        if (path in deletedSet) {
            toaster.show(deletedMessage)
        } else {
            selectedPath = path
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
                        addedFiles.size + editedFileList.size + removedFiles.size
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
                            text = "+${addedFiles.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = DiffAddedColor,
                        )
                    }
                    if (editedFileList.isNotEmpty()) {
                        Text(
                            text = "~${editedFileList.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (removedFiles.isNotEmpty()) {
                        Text(
                            text = "-${removedFiles.size}",
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
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
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
                        val (area, relativePath) = resolveWorkspacePath(p)
                        navController.navigate(
                            Screen.WorkspaceFileEditor(workspaceId, area.name, relativePath)
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
                        // 跳到文件所在目录：解析出存储区 + 相对路径，去掉文件名取父目录
                        val (area, relativePath) = resolveWorkspacePath(p)
                        val dir = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                        navController.navigate(
                            Screen.WorkspaceDetail(
                                id = workspaceId,
                                area = area.name,
                                path = dir,
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
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private fun extractFileChanges(parts: List<UIMessagePart>): List<FileChange> {
    val changes = mutableListOf<FileChange>()
    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isExecuted }
        .forEach { tool ->
            when (tool.toolName) {
                "workspace_write_file" -> {
                    val path = tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val status = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?.let { text ->
                            runCatching {
                                JsonInstant.parseToJsonElement(text).jsonObject["changeStatus"]
                                    ?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                    changes.add(
                        FileChange(
                            path = path,
                            status = if (status == "edited") FileChangeStatus.EDITED else FileChangeStatus.ADDED,
                        )
                    )
                }

                "workspace_edit_file" -> {
                    val path = tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    changes.add(FileChange(path, FileChangeStatus.EDITED))
                }

                "workspace_shell" -> {
                    val output = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .firstOrNull()?.text
                        ?.let { text ->
                            runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull()
                        }
                        ?: return@forEach
                    output["addedFiles"]?.jsonArray?.forEach { element ->
                        element.jsonPrimitive.contentOrNull?.let { path ->
                            changes.add(FileChange(path, FileChangeStatus.ADDED))
                        }
                    }
                    output["modifiedFiles"]?.jsonArray?.forEach { element ->
                        element.jsonPrimitive.contentOrNull?.let { path ->
                            changes.add(FileChange(path, FileChangeStatus.EDITED))
                        }
                    }
                    output["removedFiles"]?.jsonArray?.forEach { element ->
                        element.jsonPrimitive.contentOrNull?.let { path ->
                            changes.add(FileChange(path, FileChangeStatus.REMOVED))
                        }
                    }
                }
            }
        }
    // 同一消息内同一路径可能多次出现，保留最后一次状态
    return changes.reversed().distinctBy { it.path }.reversed()
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
    val visiblePaths = if (expanded) paths else paths.take(DEFAULT_VISIBLE_COUNT)
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
                            modifier = Modifier.widthIn(max = 200.dp),
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
    val labelPrefix: String,
    val icon: ImageVector,
    val screen: Screen,
)

private val STUDY_TOOL_CONFIGS = listOf(
    StudyToolConfig("save_vocabulary", "word", "生词", HugeIcons.BookOpen01, Screen.VocabularyPanel),
    StudyToolConfig("save_note", "title", "笔记", HugeIcons.Note01, Screen.NotesPanel),
    StudyToolConfig("save_wrong_question", "question", "错题", HugeIcons.Alert01, Screen.WrongQuestionPanel),
    StudyToolConfig("save_knowledge_card", "concept", "知识点", HugeIcons.Bulb, Screen.KnowledgeCardPanel),
    StudyToolConfig("update_vocabulary", "word", "生词", HugeIcons.BookOpen01, Screen.VocabularyPanel),
    StudyToolConfig("update_note", "title", "笔记", HugeIcons.Note01, Screen.NotesPanel),
    StudyToolConfig("update_wrong_question", "question", "错题", HugeIcons.Alert01, Screen.WrongQuestionPanel),
    StudyToolConfig("update_knowledge_card", "concept", "知识点", HugeIcons.Bulb, Screen.KnowledgeCardPanel),
)

private data class StudyItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val screen: Screen,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyItemsList(parts: List<UIMessagePart>) {
    val navController = LocalNavController.current
    val studyItems = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted }
            .mapNotNull { tool ->
                val config = STUDY_TOOL_CONFIGS.find { it.toolName == tool.toolName } ?: return@mapNotNull null
                val value = tool.inputAsJson().jsonObject[config.inputField]
                    ?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val label = if (config.toolName in listOf("save_wrong_question", "update_wrong_question")) {
                    "${config.labelPrefix}: ${value.take(20)}"
                } else {
                    "${config.labelPrefix}: $value"
                }
                StudyItem(key = "${config.toolName}:$value", label = label, icon = config.icon, screen = config.screen)
            }
            .distinctBy { it.key }
    }
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
                        modifier = Modifier.widthIn(max = 200.dp),
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