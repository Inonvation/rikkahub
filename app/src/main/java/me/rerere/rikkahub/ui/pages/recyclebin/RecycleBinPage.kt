package me.rerere.rikkahub.ui.pages.recyclebin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.RestoreBin
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 待确认永久删除的目标 */
private sealed interface PendingDelete {
    data class Chat(val id: Long) : PendingDelete
    data class Vocabulary(val id: String) : PendingDelete
    data class Note(val id: String) : PendingDelete
    data class WrongQuestion(val id: String) : PendingDelete
    data class KnowledgeCard(val id: String) : PendingDelete
    data class Workspace(val item: WorkspaceTrashItem) : PendingDelete
}

@Composable
fun RecycleBinPage(vm: RecycleBinVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // —— 聊天附件 ——
                item {
                    SectionHeader("聊天附件")
                }
                if (state.chatFiles.isEmpty()) {
                    item { EmptyHint("暂无已删除的聊天附件") }
                } else {
                    items(state.chatFiles, key = { "chat:${it.id}" }) { entity ->
                        TrashItemRow(
                            title = entity.displayName,
                            subtitle = "${entity.sizeBytes.fileSizeToString()} · ${entity.relativePath}",
                            onRestore = { vm.restoreChatFile(entity.id) },
                            onDelete = { pendingDelete = PendingDelete.Chat(entity.id) },
                        )
                    }
                }

                // —— 学习归档 ——
                item {
                    SectionHeader("学习归档")
                }
                val studyEmpty = state.vocabularies.isEmpty() && state.notes.isEmpty() &&
                    state.wrongQuestions.isEmpty() && state.knowledgeCards.isEmpty()
                if (studyEmpty) {
                    item { EmptyHint("暂无归档的学习内容") }
                } else {
                    if (state.vocabularies.isNotEmpty()) {
                        item { SubHeader("生词") }
                        items(state.vocabularies, key = { "vocab:${it.id}" }) { item ->
                            TrashItemRow(
                                title = item.word.ifBlank { "生词" },
                                subtitle = "生词",
                                onRestore = { vm.restoreVocabulary(item.id) },
                                onDelete = { pendingDelete = PendingDelete.Vocabulary(item.id) },
                            )
                        }
                    }
                    if (state.notes.isNotEmpty()) {
                        item { SubHeader("笔记") }
                        items(state.notes, key = { "note:${it.id}" }) { item ->
                            TrashItemRow(
                                title = item.title.ifBlank { "无标题" },
                                subtitle = "笔记 · ${item.category}",
                                onRestore = { vm.restoreNote(item.id) },
                                onDelete = { pendingDelete = PendingDelete.Note(item.id) },
                            )
                        }
                    }
                    if (state.wrongQuestions.isNotEmpty()) {
                        item { SubHeader("错题本") }
                        items(state.wrongQuestions, key = { "wrong:${it.id}" }) { item ->
                            TrashItemRow(
                                title = item.title.ifBlank { "错题" },
                                subtitle = "错题 · ${item.subject}",
                                onRestore = { vm.restoreWrongQuestion(item.id) },
                                onDelete = { pendingDelete = PendingDelete.WrongQuestion(item.id) },
                            )
                        }
                    }
                    if (state.knowledgeCards.isNotEmpty()) {
                        item { SubHeader("知识点卡片") }
                        items(state.knowledgeCards, key = { "card:${it.id}" }) { item ->
                            TrashItemRow(
                                title = item.concept.ifBlank { "知识点" },
                                subtitle = "卡片 · ${item.subject}",
                                onRestore = { vm.restoreKnowledgeCard(item.id) },
                                onDelete = { pendingDelete = PendingDelete.KnowledgeCard(item.id) },
                            )
                        }
                    }
                }

                // —— 工作区文件 ——
                item {
                    SectionHeader("工作区文件")
                }
                if (state.workspaceFiles.isEmpty()) {
                    item { EmptyHint("暂无已删除的工作区文件") }
                } else {
                    items(state.workspaceFiles, key = { "ws:${it.workspaceId}:${it.area}:${it.entry.path}" }) { item ->
                        TrashItemRow(
                            title = item.entry.name,
                            subtitle = "${item.workspaceName} · ${item.entry.path}",
                            onRestore = { vm.restoreWorkspaceFile(item) },
                            onDelete = { pendingDelete = PendingDelete.Workspace(item) },
                        )
                    }
                }
            }
        }
    }

    // 永久删除确认
    pendingDelete?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "永久删除",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                when (target) {
                    is PendingDelete.Chat -> vm.deleteChatFilePermanently(target.id)
                    is PendingDelete.Vocabulary -> vm.deleteVocabularyPermanently(target.id)
                    is PendingDelete.Note -> vm.deleteNotePermanently(target.id)
                    is PendingDelete.WrongQuestion -> vm.deleteWrongQuestionPermanently(target.id)
                    is PendingDelete.KnowledgeCard -> vm.deleteKnowledgeCardPermanently(target.id)
                    is PendingDelete.Workspace -> vm.deleteWorkspaceFilePermanently(target.item)
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        ) {
            Text("删除后无法恢复，确定永久删除吗？")
        }
    }

    // 操作失败提示
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("操作失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissError) {
                    Text("确定")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SubHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun TrashItemRow(
    title: String,
    subtitle: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore) {
                Icon(
                    HugeIcons.RestoreBin,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("恢复", modifier = Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = "永久删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
