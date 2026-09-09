package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Drag02
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.effectiveCategory
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.hooks.rememberReorderUiState
import sh.calvin.reorderable.ReorderableItem
import kotlin.uuid.Uuid

/** 二级管理的目标：某个分类的成员，或「其他」（未分类）成员 */
private sealed interface ManageTarget {
    data class Category(val tag: Tag) : ManageTarget
    data object Other : ManageTarget
}

/**
 * 分类管理弹层：
 * - 一级：分类拖动排序 / 重命名 / 删除 / 新建，并可进入某个分类的成员管理；
 *   另有「其他」行管理未分类助手（顺序同样可调）。
 * - 二级：该组成员列表，长按拖动调整组内顺序（顺序投影回全局 assistants 顺序），
 *   分类下还可把其它助手移入、把成员移出到「其他」。
 *
 * 删除分类只把成员退回「其他」，助手本身不受影响。
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
    onReorderMembers: (List<Uuid>) -> Unit,
    onMoveMembersToCategory: (categoryId: Uuid?, assistantIds: List<Uuid>) -> Unit,
) {
    var renamingTag by remember { mutableStateOf<Tag?>(null) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }
    var newCategoryName by remember { mutableStateOf("") }
    var manageTarget by remember { mutableStateOf<ManageTarget?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            when (val target = manageTarget) {
                null -> CategoryListPane(
                    categories = categories,
                    assistants = assistants,
                    onDismiss = onDismiss,
                    onAdd = onAdd,
                    onRequestRename = { renamingTag = it },
                    onRequestDelete = { deletingTag = it },
                    onReorder = onReorder,
                    onManage = { manageTarget = it },
                    newCategoryName = newCategoryName,
                    onNewCategoryNameChange = { newCategoryName = it },
                )

                else -> CategoryMembersPane(
                    title = when (target) {
                        is ManageTarget.Category -> target.tag.name
                        ManageTarget.Other -> stringResource(R.string.assistant_category_other)
                    },
                    members = assistants.filter {
                        it.effectiveCategory == (target as? ManageTarget.Category)?.tag?.id
                    },
                    candidates = (target as? ManageTarget.Category)?.let { current ->
                        assistants.filter { it.effectiveCategory != current.tag.id }
                    } ?: emptyList(),
                    onBack = { manageTarget = null },
                    onReorderMembers = onReorderMembers,
                    onAddMembers = { ids ->
                        (target as? ManageTarget.Category)?.let { onMoveMembersToCategory(it.tag.id, ids) }
                    },
                    onRemoveMember = { id -> onMoveMembersToCategory(null, listOf(id)) },
                )
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
                        assistants.count { it.effectiveCategory == tag.id },
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

/** 一级：分类列表（可拖排序 / 重命名 / 删除 / 新建），行尾进入成员管理 */
@Composable
private fun ColumnScope.CategoryListPane(
    categories: List<Tag>,
    assistants: List<Assistant>,
    onDismiss: () -> Unit,
    onAdd: (name: String) -> Unit,
    onRequestRename: (Tag) -> Unit,
    onRequestDelete: (Tag) -> Unit,
    onReorder: (List<Tag>) -> Unit,
    onManage: (ManageTarget) -> Unit,
    newCategoryName: String,
    onNewCategoryNameChange: (String) -> Unit,
) {
    val hapticController = rememberHaptic()
    val listState = rememberLazyListState()
    // 拖动排序：本地同步更新顺序，松手后一次性落盘
    val reorderableState = rememberReorderUiState(
        lazyListState = listState,
        items = categories,
        persist = onReorder,
    )

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
                                assistants.count { it.effectiveCategory == tag.id },
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
                            IconButton(onClick = { onManage(ManageTarget.Category(tag)) }) {
                                Icon(
                                    imageVector = HugeIcons.UserGroup,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRequestRename(tag) }) {
                                Icon(
                                    imageVector = HugeIcons.PencilEdit01,
                                    contentDescription = stringResource(R.string.assistant_category_rename),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRequestDelete(tag) }) {
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

        // 「其他」组：未分类助手，仅可管理成员顺序
        item(key = "other") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.assistant_category_other)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.assistant_category_count,
                            assistants.count { it.effectiveCategory == null },
                        )
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onManage(ManageTarget.Other) }) {
                        Icon(
                            imageVector = HugeIcons.UserGroup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
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
            onValueChange = onNewCategoryNameChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.assistant_category_new_placeholder)) },
            singleLine = true,
        )
        TextButton(
            onClick = {
                hapticController.lightTap()
                onAdd(newCategoryName)
                onNewCategoryNameChange("")
            },
            enabled = newCategoryName.isNotBlank() &&
                categories.none { it.name.equals(newCategoryName.trim(), ignoreCase = true) },
        ) {
            Text(stringResource(R.string.assistant_category_add))
        }
    }
}

/** 二级：某组成员列表，长按拖动调整组内顺序；分类下支持移入 / 移出 */
@Composable
private fun ColumnScope.CategoryMembersPane(
    title: String,
    members: List<Assistant>,
    candidates: List<Assistant>,
    onBack: () -> Unit,
    onReorderMembers: (List<Uuid>) -> Unit,
    onAddMembers: (List<Uuid>) -> Unit,
    onRemoveMember: (Uuid) -> Unit,
) {
    val hapticController = rememberHaptic()
    val listState = rememberLazyListState()
    var showAddDialog by remember { mutableStateOf(false) }
    val reorderableState = rememberReorderUiState(
        lazyListState = listState,
        items = members,
        persist = { ordered -> onReorderMembers(ordered.map { it.id }) },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = null,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
    }

    if (members.isEmpty()) {
        Text(
            text = stringResource(R.string.assistant_add_to_category_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(reorderableState.items, key = { it.id }) { member ->
                ReorderableItem(
                    state = reorderableState.reorderableState,
                    key = member.id,
                ) { isDragging ->
                    ListItem(
                        headlineContent = {
                            Text(
                                member.name.ifBlank {
                                    stringResource(R.string.assistant_page_default_assistant)
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Drag02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .scale(if (isDragging) 0.95f else 1f)
                                    .longPressDraggableHandle(
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
                            // 「其他」组（candidates 为空）不提供移出入口
                            if (candidates.isNotEmpty()) {
                                IconButton(onClick = { onRemoveMember(member.id) }) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (candidates.isNotEmpty()) {
        TextButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.assistant_add_to_category_action, title))
        }
    }

    if (showAddDialog) {
        AssistantAddToCategoryDialog(
            categoryName = title,
            candidates = candidates,
            onConfirm = {
                onAddMembers(it)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}
