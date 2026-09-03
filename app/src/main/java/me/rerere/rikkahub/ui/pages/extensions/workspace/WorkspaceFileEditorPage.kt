package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownPreviewSwitcher
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 *
 * 未保存保护: [baseline] 记录"磁盘上的最后内容"(加载完成或保存成功后更新), 当前文本与其不一致
 * 即视为脏; 脏状态下系统返回键先弹三选对话框(保存并退出/放弃/留下), 防止误触返回静默丢失修改。
 * 文本状态由 rememberTextFieldState 内部以 rememberSaveable + TextFieldState.Saver 兜底,
 * 旋转/进程重建由系统负责恢复, 这里不需要重复做 Saver。
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    // JSON 文件启用「结构」树状预览模式
    val isJson = fileName.substringAfterLast('.', "").lowercase() == "json"
    // HTML 文件启用「HTML」渲染预览模式（WebView）
    val isHtml = fileName.substringAfterLast('.', "").lowercase() in setOf("html", "htm")
    // HTML 内相对资源（图片/CSS/JS）的解析基准目录；仅 FILES 区可用
    var htmlBaseUrl by remember { mutableStateOf<String?>(null) }

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 磁盘内容基准: 加载完成 / 保存成功后更新; null = 尚未就绪(不可判定脏)
    var baseline by remember { mutableStateOf<String?>(null) }
    val dirty = editable && baseline != null && textState.text.toString() != baseline
    var showExitConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            if (isHtml && area == WorkspaceStorageArea.FILES) {
                repository.fileDirPath(id, area, path)?.let { dirPath ->
                    htmlBaseUrl = "file://$dirPath/"
                }
            }
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            baseline = content
            loading = false
        }.onFailure {
            loadError = it.message ?: "读取文件失败"
            loading = false
        }
    }

    /** 统一保存入口: 成功写盘后同步 [baseline] 并回调 [onSaved] (供"保存并退出"链路复用) */
    fun requestSave(onSaved: () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                repository.writeText(
                    id = id,
                    path = path,
                    text = textState.text.toString(),
                    overwrite = true,
                )
            }.onSuccess {
                baseline = textState.text.toString()
                toaster.show("已保存", type = ToastType.Success)
                onSaved()
            }.onFailure {
                toaster.show(it.message ?: "保存失败", type = ToastType.Error)
            }
            saving = false
        }
    }

    // 返回保护: 仅在 FILES 可编辑文件且内容被改动时拦截
    BackHandler(enabled = dirty) {
        showExitConfirm = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    // 脏状态下返回箭头同样先弹确认, 与系统返回键(BackHandler)行为一致
                    BackButton(
                        onClick = {
                            if (dirty) showExitConfirm = true else navController.popBackStack()
                        },
                    )
                },
                actions = {
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = { requestSave {} },
                            enabled = !saving && dirty,
                        ) {
                            Text("Save")
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> MarkdownPreviewSwitcher(
                state = textState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                sourceEditable = editable,
                jsonStructure = isJson,
                htmlMode = isHtml,
                htmlBaseUrl = htmlBaseUrl,
            )
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("未保存的更改") },
            text = {
                Column {
                    Text("是否保存对 ${fileName} 的修改？")
                    Text(
                        text = "点击对话框外或返回键可回到编辑器继续修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (saving) {
                        Text(
                            text = "正在保存…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        requestSave { navController.popBackStack() }
                    },
                    enabled = !saving,
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        navController.popBackStack()
                    },
                ) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
