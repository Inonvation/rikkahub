package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownPreviewSwitcher
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File

/**
 * 信任文件夹文本文件编辑/预览页（基于当前激活项目）。
 * 复用工作区的 MarkdownPreviewSwitcher：支持 Markdown 预览切换、JSON 结构树状预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedFolderFileEditorPage(projectId: String, path: String) {
    val repository = koinInject<TrustedFolderRepository>()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val isJson = fileName.substringAfterLast('.', "").lowercase() == "json"

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        loading = true
        loadError = null
        runCatching { repository.readText(path, projectId) }
            .onSuccess {
                textState.setTextAndPlaceCursorAtEnd(it)
                loading = false
            }
            .onFailure {
                loadError = it.message ?: "读取文件失败"
                loading = false
            }
    }

    // 后台预热笔记索引，加速本会话后续双链跳转（失败静默，回退惰性构建）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { repository.prewarmNoteIndex(projectId) }
    }

    // 离开页面时清理图片降级产生的 cache 临时文件，避免长期累积
    DisposableEffect(Unit) {
        onDispose {
            runCatching { File(context.cacheDir, "tf_note_images").deleteRecursively() }
        }
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
                navigationIcon = { BackButton() },
                actions = {
                    if (!loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            relPath = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                            projectId = projectId,
                                        )
                                    }.onSuccess {
                                        toaster.show("已保存", type = ToastType.Success)
                                        scope.launch { repository.recordRecentFile(projectId, path) }
                                    }.onFailure {
                                        toaster.show(it.message ?: "保存失败", type = ToastType.Error)
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text("保存")
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
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> MarkdownPreviewSwitcher(
                state = textState,
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
                sourceEditable = true,
                jsonStructure = isJson,
                previewByDefault = true,
                // 点击双链/内部链接：解析目标笔记并跳转（回到该笔记的渲染预览）
                onLinkClick = { dest ->
                    scope.launch {
                        val target = runCatching { repository.resolveNotePath(dest, projectId) }.getOrNull()
                        if (target != null) {
                            navController.navigate(Screen.TrustedFolderEditor(projectId, target))
                        } else {
                            // 显示解码后的笔记名（dest 可能被 percent-encode），方便用户核对
                            val pretty = runCatching { java.net.URLDecoder.decode(dest, "UTF-8") }.getOrDefault(dest)
                            toaster.show("找不到笔记：$pretty", type = ToastType.Error)
                        }
                    }
                    true
                },
                // 笔记内相对图片路径 → 可加载 URI：优先 content://（ContentResolver 校验可读），
                // 不可读时降级为读 bytes 写 cache 临时文件返回 file://（Coil 对 file:// 稳定支持）
                imagePathResolver = { rawRel ->
                    // 清理 `./`、`/` 前缀并 URL 解码（markdown 里可能写 %20 这类转义）
                    val rel = runCatching { java.net.URLDecoder.decode(rawRel.trim(), "UTF-8") }
                        .getOrDefault(rawRel.trim())
                        .removePrefix("./")
                        .removePrefix("/")
                        .removePrefix(".\\")
                    if (rel.isBlank()) {
                        null
                    } else {
                        val noteDir = path.substringBeforeLast('/', missingDelimiterValue = "")
                        val relCandidates = if (noteDir.isEmpty()) listOf(rel) else listOf("$noteDir/$rel", rel)
                        // Obsidian 附件可能放在任意目录：先按文件名全局搜索，命中作为最高优先级候选
                        val globalPath = runCatching { repository.resolveImagePath(rel, projectId) }.getOrNull()
                        val candidates = if (globalPath != null) listOf(globalPath) + relCandidates else relCandidates
                        // 优先 content://：能被 ContentResolver 读取才直接用
                        val viaContentUri = candidates
                            .firstNotNullOfOrNull { c -> runCatching { repository.contentUri(c, projectId) }.getOrNull() }
                            ?.let { uri ->
                                val readable = runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { true }
                                }.getOrDefault(false)
                                if (readable == true) uri.toString() else null
                            }
                        // 降级：读 bytes 写入 cache 临时文件
                        viaContentUri ?: runCatching {
                            val bytes = candidates.firstNotNullOfOrNull { c ->
                                runCatching { repository.readBytes(c, projectId) }.getOrNull()
                            }
                            if (bytes == null) {
                                null
                            } else {
                                val dir = File(context.cacheDir, "tf_note_images").apply { mkdirs() }
                                val digest = java.security.MessageDigest.getInstance("MD5")
                                    .digest(rel.toByteArray())
                                    .joinToString("") { "%02x".format(it) }
                                val cacheFile = File(dir, digest.take(8) + "_" + rel.substringAfterLast('/').take(60))
                                // 文件写放 IO 线程，避免占用后台 CPU 线程
                                withContext(Dispatchers.IO) { cacheFile.writeBytes(bytes) }
                                cacheFile.toURI().toString()
                            }
                        }.getOrNull()
                    }
                },
                // 笔记嵌入 ![[Note]]：读取目标笔记内容在预览中内联展开（非图片）
                noteEmbedResolver = { note ->
                    val p = runCatching { repository.resolveNotePath(note, projectId) }.getOrNull()
                    p?.let { runCatching { repository.readText(it, projectId) }.getOrNull() }
                },
            )
        }
    }
}
