package me.rerere.rikkahub.ui.pages.study

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUpRight01
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share03
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount
import me.rerere.rikkahub.data.DocumentProcessor
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.db.fts.KnowledgeChunkFtsManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.Screen
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

/**
 * 详情弹窗右上角的多功能入口：复制 / 预览 / 导出 / 分享 / 跳转到对话 / 导入到知识库。
 */
@Composable
fun StudyDetailActions(
    title: String,
    content: String,
    sourceConversationId: String = "",
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val colorScheme = androidx.compose.material3.MaterialTheme.colorScheme
    val knowledgeManager: KnowledgeManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val providerManager: ProviderManager = koinInject()
    val ftsManager: KnowledgeChunkFtsManager = koinInject()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showBasePicker by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0f) }

    Box(modifier = Modifier.onSizeChanged { anchorHeight = it.height }) {
        IconButton(onClick = { expanded = !expanded }, modifier = modifier) {
            Icon(HugeIcons.MoreVertical, "更多操作", tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        }

        if (expanded) {
            LaunchedEffect(Unit) { scale.snapTo(0f); scale.animateTo(1f, tween(180)) }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, anchorHeight),
                // 点击 anchor 时 Popup 的 dismiss 会与 IconButton 的 onClick 竞争：
                // dismiss 先设 expanded=false，onClick 再 toggle 会误判成 true。
                // 用 launch 延迟 dismiss，让 onClick 先基于原始状态完成切换。
                onDismissRequest = { scope.launch { expanded = false } },
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(200.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            transformOrigin = TransformOrigin(1f, 0f) // 右上角为缩放原点
                        },
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        StudyMenuActionItem(
                            icon = { Icon(HugeIcons.Copy01, null) },
                            label = "复制内容",
                        ) {
                            expanded = false
                            context.writeClipboardText(content)
                            toaster.show("已复制", type = ToastType.Success)
                        }
                        StudyMenuActionItem(
                            icon = { Icon(HugeIcons.Eye, null) },
                            label = "预览",
                        ) {
                            expanded = false
                            val html = buildMarkdownPreviewHtml(context, content, colorScheme)
                            val contentId = WebViewContentCache.store(context.cacheDir, html)
                            navController.navigate(Screen.WebView(contentId = contentId))
                        }
                        StudyMenuActionItem(
                            icon = { Icon(HugeIcons.Download01, null) },
                            label = "导出为 Markdown",
                        ) {
                            expanded = false
                            scope.launch { exportTextToFile(context, title, content) }
                        }
                        StudyMenuActionItem(
                            icon = { Icon(HugeIcons.Share03, null) },
                            label = "分享",
                        ) {
                            expanded = false
                            shareText(context, content)
                        }
                        if (sourceConversationId.isNotBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            StudyMenuActionItem(
                                icon = { Icon(HugeIcons.ArrowUpRight01, null) },
                                label = "跳转到对话",
                            ) {
                                expanded = false
                                runCatching { navigateToChatPage(navController, Uuid.parse(sourceConversationId)) }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        StudyMenuActionItem(
                            icon = { Icon(HugeIcons.Book04, null) },
                            label = "导入到知识库",
                        ) {
                            expanded = false
                            showBasePicker = true
                        }
                    }
                }
            }
        }
    }

    if (showBasePicker) {
        KnowledgeBasePickerDialog(
            onDismiss = { showBasePicker = false },
            onSelect = { base ->
                showBasePicker = false
                scope.launch {
                    val ok = importToKnowledgeBase(
                        context = context,
                        knowledgeManager = knowledgeManager,
                        settingsStore = settingsStore,
                        providerManager = providerManager,
                        ftsManager = ftsManager,
                        baseId = base.id,
                        title = title,
                        content = content,
                    )
                    toaster.show(
                        if (ok) "已导入到知识库「${base.name}」" else "导入失败",
                        type = if (ok) ToastType.Success else ToastType.Error,
                    )
                }
            }
        )
    }
}

@Composable
private fun StudyMenuActionItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun KnowledgeBasePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (KnowledgeBaseWithDocumentCount) -> Unit,
) {
    val knowledgeManager: KnowledgeManager = koinInject()
    val bases by knowledgeManager.baseRepository.getAllWithDocumentCount()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入到知识库") },
        text = {
            if (bases.isEmpty()) {
                Text("还没有知识库，请先在知识库页面创建")
            } else {
                LazyColumn {
                    items(bases, key = { it.id }) { base ->
                        ListItem(
                            headlineContent = { Text(base.name) },
                            supportingContent = { Text("${base.documentCount} 个文档") },
                            leadingContent = { Icon(HugeIcons.Book04, null) },
                            modifier = Modifier.clickable { onSelect(base) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        dismissButton = null,
    )
}

private suspend fun importToKnowledgeBase(
    context: Context,
    knowledgeManager: KnowledgeManager,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    ftsManager: KnowledgeChunkFtsManager,
    baseId: String,
    title: String,
    content: String,
): Boolean {
    val fileName = "${sanitizeFileName(title.ifBlank { "study" })}-${Uuid.random().toString().take(6)}.md"
    val filePath = "${context.filesDir}/knowledge/$baseId/raw/$fileName"
    return withContext(Dispatchers.IO) {
        runCatching {
            File(filePath).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
            val doc = knowledgeManager.documentRepository.create(
                knowledgeBaseId = baseId,
                fileName = fileName,
                fileType = "md",
                filePath = filePath,
                fileSize = File(filePath).length(),
            )
            DocumentProcessor(knowledgeManager, settingsStore, providerManager, ftsManager, baseId)
                .processDocument(doc.id, filePath, "md")
            // processDocument 内部吞异常并以 failed 状态结束，需查最终 status 判断成功
            val finalDoc = knowledgeManager.documentRepository.getById(doc.id)
            finalDoc?.status == "completed" || finalDoc?.status == "processing"
        }.getOrDefault(false)
    }
}

private suspend fun exportTextToFile(context: Context, title: String, content: String) {
    val uri: Uri = withContext(Dispatchers.IO) {
        val file = File(context.appTempFolder, "${sanitizeFileName(title)}-${System.currentTimeMillis()}.md")
        file.writeText(content)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            )
        )
    }
}

private fun shareText(context: Context, text: String) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                null,
            )
        )
    }
}

private fun sanitizeFileName(name: String) =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(30).ifBlank { "study" }

// ---------- 各类型内容的 markdown 原文拼装 ----------

fun buildNoteMarkdown(note: NoteEntity): String = buildString {
    appendLine("# ${note.title}")
    appendLine()
    appendLine(note.content)
    appendLine()
    val tags = parseTags(note.tags)
    if (tags.isNotEmpty()) appendLine("**标签**: ${tags.joinToString(", ")}")
}

fun buildWrongQuestionMarkdown(q: WrongQuestionEntity): String = buildString {
    appendLine("# ${q.title.ifBlank { extractPlainText(q.question).ifBlank { q.question.take(30) } }}")
    appendLine()
    appendLine("## 题目")
    appendLine(q.question)
    if (q.answer.isNotBlank()) {
        appendLine()
        appendLine("## 答案")
        appendLine(q.answer)
    }
    if (q.solution.isNotBlank()) {
        appendLine()
        appendLine("## 解析")
        appendLine(q.solution)
    }
    val points = parseKnowledgePoints(q.knowledgePoints)
    if (points.isNotEmpty()) {
        appendLine()
        appendLine("**知识点**: ${points.joinToString(", ")}")
    }
    val tags = parseTags(q.tags)
    if (tags.isNotEmpty()) {
        appendLine()
        appendLine("**标签**: ${tags.joinToString(", ")}")
    }
}

fun buildKnowledgeCardMarkdown(card: KnowledgeCardEntity): String = buildString {
    appendLine("# ${card.concept}")
    appendLine()
    if (card.explanation.isNotBlank()) {
        appendLine("## 解释")
        appendLine(card.explanation)
    }
    if (card.memoryAid.isNotBlank()) {
        appendLine()
        appendLine("## 助记")
        appendLine(card.memoryAid)
    }
    val tags = parseTags(card.tags)
    if (tags.isNotEmpty()) {
        appendLine()
        appendLine("**标签**: ${tags.joinToString(", ")}")
    }
}

fun buildVocabularyMarkdown(word: VocabularyEntity): String = buildString {
    appendLine("# ${word.word}")
    if (word.pronunciation.isNotBlank()) appendLine("/${word.pronunciation}/")
    appendLine()
    val translations = parseTranslations(word.translations)
    if (translations.isNotEmpty()) {
        appendLine("## 释义")
        translations.forEach { appendLine("- *${it.pos}* ${it.definition}") }
    }
    val examples = parseExamples(word.examples)
    if (examples.isNotEmpty()) {
        appendLine()
        appendLine("## 例句")
        examples.forEach {
            if (it.en.isNotBlank()) appendLine("> ${it.en}")
            if (it.zh.isNotBlank()) appendLine("> ${it.zh}")
        }
    }
    if (word.mnemonic.isNotBlank()) {
        appendLine()
        appendLine("## 助记")
        appendLine(word.mnemonic)
    }
    val tags = parseTags(word.tags)
    if (tags.isNotEmpty()) {
        appendLine()
        appendLine("**标签**: ${tags.joinToString(", ")}")
    }
}
