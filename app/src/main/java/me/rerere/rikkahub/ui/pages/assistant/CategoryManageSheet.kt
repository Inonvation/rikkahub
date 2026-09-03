package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Drag02
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.hooks.rememberReorderUiState
import sh.calvin.reorderable.ReorderableItem
import kotlin.uuid.Uuid

/**
 * 分类管理弹层：拖动排序 / 重命名 / 删除 / 新建。
 * 删除只断开助手与分类的引用，助手本身不受影响。
 */
@Composable
fun CategoryManageSheet(
    categories: List<Tag>,
    assistants: List<Assistant>,
    onDismiss: () -> Unit,
    onAdd: (name: String) -> Unit,
    onRename: (id: Uuid, newName: String) -> Unit,
    onDelete: (Tag) -> Unit,
    onReorder: (List<Tag>) -> Unit,
) {
    var renamingTag by remember { mutableStateOf<Tag?>(null) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }
    var newCategoryName by remember { mutableStateOf("") }

    val hapticController = rememberHaptic()
    val listState = rememberLazyListState()
    // 拖动排序：本地同步更新顺序，松手后一次性落盘
    val reorderableState = rememberReorderUiState(
        lazyListState = listState,
        items = categories,
        persist = onReorder,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.assistant_category_manage),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done))
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(reorderableState.items, key = { it.id }) { tag ->
                    ReorderableItem(state = reorderableState.reorderableState, key = tag.id) { isDragging ->
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.assistant_category_count,
                                        assistants.count { tag.id in it.tags },
                                    )
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Drag02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .scale(if (isDragging) 0.95f else 1f)
                                        .draggableHandle(
                                            onDragStarted = {
                                                hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                hapticController.perform(HapticFeedbackType.GestureEnd)
                                                reorderableState.persistNow()
                                            },
                                        ),
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renamingTag = tag }) {
                                        Icon(
                                            imageVector = HugeIcons.PencilEdit01,
                                            contentDescription = stringResource(R.string.assistant_category_rename),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { deletingTag = tag }) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.assistant_category_delete_title),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.assistant_category_new_placeholder)) },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        hapticController.lightTap()
                        onAdd(newCategoryName)
                        newCategoryName = ""
                    },
                    enabled = newCategoryName.isNotBlank() &&
                        categories.none { it.name.equals(newCategoryName.trim(), ignoreCase = true) },
                ) {
                    Text(stringResource(R.string.assistant_category_add))
                }
            }
        }
    }

    renamingTag?.let { tag ->
        var name by remember(tag.id) { mutableStateOf(tag.name) }
        val duplicated = categories.any { it.id != tag.id && it.name.equals(name.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { renamingTag = null },
            title = { Text(stringResource(R.string.assistant_category_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isBlank() || duplicated,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(tag.id, name)
                        renamingTag = null
                    },
                    enabled = name.isNotBlank() && !duplicated,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTag = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.assistant_category_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.assistant_category_delete_text,
                        tag.name,
                        assistants.count { tag.id in it.tags },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(tag); deletingTag = null }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
