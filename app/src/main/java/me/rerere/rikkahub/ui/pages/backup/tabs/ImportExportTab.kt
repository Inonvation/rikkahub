package me.rerere.rikkahub.ui.pages.backup.tabs

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    // 选中文件后、确认框弹出前的"拷贝+解析预览"阶段，用于展示进度框避免黑屏
    var isAnalyzing by remember { mutableStateOf(false) }
    val backupProgress by vm.backupProgress.collectAsStateWithLifecycle()

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 二次确认：选中文件后不立即恢复，等用户确认（导入会覆盖本地数据）
    var pendingImportType by remember { mutableStateOf<String?>(null) }
    var pendingImportFile by remember { mutableStateOf<File?>(null) }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件
                    val exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    val outputStream = context.contentResolver.openOutputStream(targetUri)
                        ?: throw java.io.IOException("无法打开导出目标位置（openOutputStream 返回 null）")
                    outputStream.use { out ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(out)
                        }
                    }

                    // 清理临时文件
                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                vm.backupProgress.value = null
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 选择文件后立即复制到临时文件并解析预览，确认对话框展示后可直接恢复
        uri?.let { sourceUri ->
            val type = importType
            scope.launch {
                // 先给出进度反馈，再在 IO 线程做拷贝+解析，避免大备份包阻塞主线程导致黑屏
                isAnalyzing = true
                vm.backupProgress.value = context.getString(R.string.backup_page_analyzing_file)
                val extension = when (type) {
                    "local", "cherry" -> "zip"
                    "chatbox" -> "json"
                    else -> "bin"
                }
                val tempFile =
                    File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.$extension")

                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        vm.analyzeBackupPreview(tempFile, type)
                    }
                    pendingImportFile = tempFile
                    pendingImportType = type
                }.onFailure { e ->
                    e.printStackTrace()
                    tempFile.delete()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isAnalyzing = false
                vm.backupProgress.value = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    // 导入二次确认（导入会覆盖本地数据）
    val pendingFile = pendingImportFile
    val previewText by vm.backupPreview.collectAsStateWithLifecycle()
    if (pendingFile != null) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.backup_page_restore_now),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                pendingImportFile = null
                vm.backupPreview.value = null
                scope.launch {
                    isRestoring = true
                    runCatching {
                        when (pendingImportType ?: "local") {
                            "local" -> {
                                // 本地备份导入：直接用已复制的临时文件恢复
                                vm.restoreFromLocalFile(pendingFile)
                            }

                            "chatbox" -> {
                                // Chatbox导入：直接用已复制的临时文件恢复
                                vm.restoreFromChatBox(pendingFile)
                            }

                            "cherry" -> {
                                // Cherry Studio导入：直接用已复制的临时文件恢复
                                vm.restoreFromCherryStudio(pendingFile)
                            }
                        }

                        toaster.show(
                            context.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success
                        )
                        onShowRestartDialog()
                    }.onFailure { e ->
                        e.printStackTrace()
                        toaster.show(
                            context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                            type = ToastType.Error
                        )
                    }
                    // 清理临时文件
                    pendingFile.delete()
                    vm.backupProgress.value = null
                    isRestoring = false
                }
            },
            onDismiss = {
                pendingImportFile = null
                pendingImportType = null
                vm.backupPreview.value = null
                pendingFile.delete()
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    previewText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("导入将覆盖当前本地数据（含聊天记录与设置），且不可撤销。确定继续？")
                }
            }
        )
    }

    // 导入/导出进度窗口：展示当前阶段的大致进度（含选中文件后的"解析预览"阶段）
    val currentProgress = backupProgress
    if (currentProgress != null && (isExporting || isRestoring || isAnalyzing)) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    when {
                        isExporting -> stringResource(R.string.backup_page_exporting)
                        isAnalyzing -> stringResource(R.string.backup_page_analyzing_file)
                        else -> stringResource(R.string.backup_page_importing)
                    }
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(currentProgress)
                    CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
                }
            },
            confirmButton = {}
        )
    }
}
