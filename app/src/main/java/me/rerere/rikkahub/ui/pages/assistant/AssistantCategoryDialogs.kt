package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.modifier.onClick
import kotlin.uuid.Uuid

/**
 * 编辑助手的分类归属：多选 chips，可就地新建分类并勾选。
 * onConfirm 返回有序 tagIds（保持原归属顺序，新建分类追加在后）与编辑后的完整分类列表。
 */
@Composable
fun AssistantEditCategoriesDialog(
    assistant: Assistant,
    categories: List<Tag>,
    onConfirm: (tagIds: List<Uuid>, categories: List<Tag>) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var selectedIds by remember { mutableStateOf(assistant.tags.toSet()) }
    var extraCategories by remember { mutableStateOf(emptyList<Tag>()) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val allCategories = categories + extraCategories
    val nameConflict = allCategories.any { it.name.equals(newName.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_edit_categories_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = assistant.name.ifBlank { defaultAssistantName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    allCategories.forEach { category ->
                        FilterChip(
                            selected = category.id in selectedIds,
                            onClick = {
                                selectedIds = if (category.id in selectedIds) {
                                    selectedIds - category.id
                                } else {
                                    selectedIds + category.id
                                }
                            },
                            label = { Text(category.name) },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { showCreate = !showCreate },
                        label = { Text(stringResource(R.string.assistant_edit_categories_create)) },
                    )
                }
                if (showCreate) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.assistant_category_new_placeholder)) },
                        singleLine = true,
                        isError = newName.isNotBlank() && nameConflict,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val trimmed = newName.trim()
                                    if (trimmed.isEmpty() || nameConflict) return@IconButton
                                    val tag = Tag(id = Uuid.random(), name = trimmed)
                                    extraCategories = extraCategories + tag
                                    selectedIds = selectedIds + tag.id
                                    newName = ""
                                    showCreate = false
                                },
                                enabled = newName.isNotBlank() && !nameConflict,
                            ) {
                                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.confirm))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 保持原归属顺序，新建分类追加在后
                    val tagIds = assistant.tags.filter { it in selectedIds } +
                        extraCategories.map { it.id }.filter { it in selectedIds }
                    onConfirm(tagIds, allCategories)
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * 把已有助手批量加入某分类；候选列表由调用方过滤（不属于该分类的助手）。
 */
@Composable
fun AssistantAddToCategoryDialog(
    categoryName: String,
    candidates: List<Assistant>,
    onConfirm: (assistantIds: List<Uuid>) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val hapticController = rememberHaptic()
    var selectedIds by remember { mutableStateOf(emptySet<Uuid>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_add_to_category_title, categoryName)) },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_add_to_category_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(candidates, key = { it.id }) { assistant ->
                        val checked = assistant.id in selectedIds
                        ListItem(
                            headlineContent = {
                                Text(assistant.name.ifBlank { defaultAssistantName })
                            },
                            leadingContent = {
                                UIAvatar(
                                    name = assistant.name.ifBlank { defaultAssistantName },
                                    value = assistant.avatar,
                                    modifier = Modifier.size(32.dp),
                                )
                            },
                            trailingContent = {
                                Checkbox(checked = checked, onCheckedChange = null)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.onClick {
                                hapticController.lightTap()
                                selectedIds = if (checked) selectedIds - assistant.id else selectedIds + assistant.id
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    hapticController.lightTap()
                    onConfirm(selectedIds.toList())
                },
                enabled = selectedIds.isNotEmpty(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
