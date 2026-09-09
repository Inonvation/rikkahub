package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import kotlin.uuid.Uuid

/**
 * 分类单选输入：一个助手至多属于一个分类（null = 未分类）。
 * 展示当前选中分类 chip（点击取消），+ 弹窗里单选其它分类或新建并选中。
 */
@Composable
fun TagsInput(
    value: Uuid?,
    tags: List<Tag>,
    modifier: Modifier = Modifier,
    onValueChange: (categoryId: Uuid?, tags: List<Tag>) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val hapticController = rememberHaptic()

    // 当前选中的分类
    val selectedTag = tags.find { it.id == value }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        // 显示当前选中的分类（可点击取消归属）
        if (selectedTag != null) {
            InputChip(onClick = {}, label = {
                Text(selectedTag.name)
            }, selected = true, trailingIcon = {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            hapticController.lightTap()
                            onValueChange(null, tags)
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            })
        }

        // 添加/修改按钮
        Surface(
            shape = CircleShape,
            tonalElevation = 2.dp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { hapticController.lightTap(); showAddDialog = true }) {
            Icon(
                imageVector = HugeIcons.Add01,
                contentDescription = stringResource(R.string.add),
                modifier = Modifier
                    .padding(6.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    // 选择分类对话框
    if (showAddDialog) {
        var tagName by remember { mutableStateOf("") }
        var showError by remember { mutableStateOf(false) }

        // 除当前已选分类外均可单选
        val selectableTags = tags.filter { it.id != value }

        AlertDialog(onDismissRequest = {
            showAddDialog = false
            tagName = ""
            showError = false
        }, title = {
            Text(stringResource(R.string.tag_input_dialog_title))
        }, text = {
            Column {
                // 显示现有分类列表（可单选）
                if (selectableTags.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.tag_input_dialog_existing_tags),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        selectableTags.forEach { tag ->
                            InputChip(
                                onClick = {
                                    hapticController.lightTap()
                                    onValueChange(tag.id, tags)
                                    showAddDialog = false
                                    tagName = ""
                                    showError = false
                                }, label = { Text(tag.name) }, selected = false
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.tag_input_dialog_create_new),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 输入新分类名称
                OutlinedTextField(
                    value = tagName,
                    onValueChange = {
                        tagName = it
                        showError = false
                    },
                    label = { Text(stringResource(R.string.tag_input_dialog_label)) },
                    placeholder = { Text(stringResource(R.string.tag_input_dialog_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = showError
                )

                // 显示错误信息
                if (showError) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tag_input_dialog_tag_exists),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }, confirmButton = {
            TextButton(
                onClick = {
                    hapticController.lightTap()
                    if (tagName.isNotBlank()) {
                        val trimmedName = tagName.trim()
                        // 检查是否已存在同名分类
                        val existingTag =
                            tags.find { it.name.equals(trimmedName, ignoreCase = true) }
                        if (existingTag != null) {
                            // 如果存在同名分类，显示错误信息
                            showError = true
                        } else {
                            // 创建新分类并选中
                            val newTag = Tag(id = Uuid.random(), name = trimmedName)
                            onValueChange(newTag.id, tags + newTag)
                            showAddDialog = false
                            tagName = ""
                            showError = false
                        }
                    }
                }, enabled = tagName.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        }, dismissButton = {
            TextButton(
                onClick = {
                    showAddDialog = false
                    tagName = ""
                    showError = false
                }) {
                Text(stringResource(R.string.cancel))
            }
        })
    }
}
