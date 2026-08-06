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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import me.rerere.rikkahub.ui.components.richtext.convertWikilinksToNoteLinks
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
fun TrustedFolderFileEditorPage(projectId: String, path: String, dest: String? = null) {
    val repository = koinInject<TrustedFolderRepository>()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 实际文件路径：双链跳转时由 dest 解析而来，保存/图片解析都用它
    var actualPath by remember { mutableStateOf(path) }
    val fileName = actualPath.substringAfterLast('/').ifBlank { path.substringAfterLast('/').ifBlank { "笔记" } }
    val isJson = fileName.substringAfterLast('.', "").lowercase() == "json"

    // 已加载标记（saveable）：从双链/返回回到同一 entry 时跳过重新加载，保留未保存编辑
    var loadedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(path, dest) {
        if (loadedOnce) {
            // 返回同一条目：保留当前 textState（含未保存编辑），只结束加载态
            loading = false
            return@LaunchedEffect
        }
        loadedOnce = true
        loading = true
        loadError = null
        runCatching {
            // 双链跳转：先解析目标笔记路径（索引构建若发生，在转圈期间于 IO 线程完成，不卡跳转）
            val resolved = if (dest != null) {
                withContext(Dispatchers.IO) { repository.resolveNotePath(dest, projectId) }
            } else path
            actualPath = resolved ?: path
            if (dest != null && resolved == null) {
                val pretty = runCatching { java.net.URLDecoder.decode(dest, "UTF-8") }.getOrDefault(dest)
                throw IllegalStateException("找不到笔记：$pretty")
            }
            withContext(Dispatchers.IO) { repository.readText(actualPath, projectId) }
        }
            .onSuccess {
                textState.setTextAndPlaceCursorAtEnd(it)
                loading = false
            }
            .onFailure {
                loadError = it.message ?: "读取文件失败"
                loading = false
            }
    }

    // 后台预热笔记索引，加速本会话后续双链跳转（失败静默，回退惰性构建）。
    // 放 IO 线程：索引构建是文件扫描，避免占满 Default 池、让渲染预处理排队。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repository.prewarmNoteIndex(projectId) }
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
                                            relPath = actualPath,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                            projectId = projectId,
                                        )
                                    }.onSuccess {
                                        toaster.show("已保存", type = ToastType.Success)
                                        scope.launch { repository.recordRecentFile(projectId, actualPath) }
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
                // 点击双链/内部链接：立即跳转，目标笔记在目标页内解析加载（转圈期间完成，不卡跳转）
                onLinkClick = { dest ->
                    navController.navigate(Screen.TrustedFolderEditor(projectId, "", dest = dest))
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
                        val noteDir = actualPath.substringBeforeLast('/', missingDelimiterValue = "")
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
                // 点击任务待办：翻转 `[ ]`↔`[x]` 并写盘（Obsidian 风格即改即存）。
                // 先立即更新源文本（视觉即时响应），再后台保存；保存失败回滚。
                onToggleTask = { taskLine ->
                    val current = textState.text.toString()
                    val normalizedTask = taskLine.trim()
                    val newText = current.lineSequence().joinToString("\n") { line ->
                        val lineTrimmed = line.trim()
                        // 源行也做双链归一后比较：任务行含 [[双链]] 时，源行与预处理后的 taskLine 才能对上
                        val isTaskLine = lineTrimmed.contains("[ ]") || lineTrimmed.contains("[x]") ||
                            lineTrimmed.contains("[X]")
                        val matched = isTaskLine && convertWikilinksToNoteLinks(lineTrimmed) == normalizedTask
                        if (matched) {
                            when {
                                lineTrimmed.contains("[ ]") -> line.replaceFirst("[ ]", "[x]")
                                else -> line.replaceFirst(Regex("""\[[xX]\]"""), "[ ]")
                            }
                        } else line
                    }
                    if (newText != current) {
                        textState.edit { replace(0, current.length, newText) }
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.writeText(actualPath, newText, overwrite = true, projectId = projectId)
                                }
                            }.onFailure {
                                // 只在文本仍等于 newText 时回滚，避免覆盖用户在写盘等待期间的后续编辑
                                if (textState.text.toString() == newText) {
                                    textState.edit { replace(0, newText.length, current) }
                                }
                                toaster.show(it.message ?: "保存失败", type = ToastType.Error)
                            }
                        }
                    }
                },
            )
        }
    }
}
