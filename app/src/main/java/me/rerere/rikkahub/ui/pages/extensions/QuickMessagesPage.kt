package me.rerere.rikkahub.ui.pages.extensions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.hooks.rememberReorderUiState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem

@Composable
fun QuickMessagesPage(vm: QuickMessagesVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickMessage?>(null) }

    // 批量选择
    val selectedItems = remember { mutableStateListOf<String>() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) {
        selecting = false
        selectedItems.clear()
    }

    // 拖拽排序
    val lazyListState = rememberLazyListState()
    val hapticController = rememberHaptic()
    // 拖动排序：本地同步更新顺序，松手后一次性落盘
    val reorderableState = rememberReorderUiState(
        lazyListState = lazyListState,
        items = settings.quickMessages,
        persist = { newList -> vm.updateQuickMessagesOrder(newList) },
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            if (selectedItems.size == settings.quickMessages.size) {
                                selectedItems.clear()
                            } else {
                                selectedItems.clear()
                                selectedItems.addAll(settings.quickMessages.map { it.id.toString() })
                            }
                        }) {
                            Icon(
                                HugeIcons.CursorPointer01,
                                contentDescription = stringResource(
                                    if (selectedItems.size == settings.quickMessages.size) {
                                        R.string.skills_page_deselect_all
                                    } else {
                                        R.string.skills_page_select_all
                                    }
                                ),
                            )
                        }
                    } else {
                        IconButton(onClick = { selecting = true }) {
                            Icon(
                                HugeIcons.MoreVertical,
                                contentDescription = stringResource(R.string.skills_page_batch_select),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(12.dp) + PaddingValues(bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                state = lazyListState,
            ) {
                if (settings.quickMessages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Zap,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.quick_messages_page_empty_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.quick_messages_page_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(reorderableState.items, key = { it.id.toString() }) { quickMessage ->
                    if (selecting) {
                        QuickMessageSelectableCard(
                            quickMessage = quickMessage,
                            selected = selectedItems.contains(quickMessage.id.toString()),
                            onSelectChange = {
                                if (!selectedItems.contains(quickMessage.id.toString())) {
                                    selectedItems.add(quickMessage.id.toString())
                                } else {
                                    selectedItems.remove(quickMessage.id.toString())
                                }
                            },
                            onEdit = { editTarget = quickMessage },
                        )
                    } else {
                        ReorderableItem(
                            state = reorderableState.reorderableState,
                            key = quickMessage.id.toString(),
                        ) { isDragging ->
                            QuickMessageCard(
                                quickMessage = quickMessage,
                                onEdit = { editTarget = quickMessage },
                                onDelete = { deleteTarget = quickMessage },
                                modifier = Modifier
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    },
                                dragModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        hapticController.perform(HapticFeedbackType.GestureEnd)
                                        reorderableState.persistNow()
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            // 批量删除操作条
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                enter = slideInVertically(initialOffsetY = { it * 2 }),
                exit = slideOutVertically(targetOffsetY = { it * 2 }),
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    Tooltip(tooltip = { Text(stringResource(R.string.skills_page_batch_cancel)) }) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(
                                stringResource(
                                    if (selectedItems.size == settings.quickMessages.size) {
                                        R.string.skills_page_deselect_all
                                    } else {
                                        R.string.skills_page_select_all
                                    }
                                )
                            )
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.size == settings.quickMessages.size) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.clear()
                                    selectedItems.addAll(settings.quickMessages.map { it.id.toString() })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.skills_page_delete)) }) {
                        FilledIconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    showBatchDeleteDialog = true
                                }
                            },
                            enabled = selectedItems.isNotEmpty(),
                        ) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.skills_page_delete))
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_add_title),
            initialQuickMessage = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, content ->
                vm.addQuickMessage(title, content)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { quickMessage ->
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_edit_title),
            initialQuickMessage = quickMessage,
            onDismiss = { editTarget = null },
            onConfirm = { title, content ->
                vm.updateQuickMessage(
                    quickMessage.copy(
                        title = title,
                        content = content,
                    )
                )
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.quick_messages_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteQuickMessage(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.quick_messages_page_delete_message, deleteTarget?.title ?: ""))
    }

    RikkaConfirmDialog(
        show = showBatchDeleteDialog,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            vm.deleteQuickMessages(selectedItems.map { kotlin.uuid.Uuid.parse(it) })
            selectedItems.clear()
            selecting = false
            showBatchDeleteDialog = false
        },
        onDismiss = { showBatchDeleteDialog = false },
    ) {
        Text(stringResource(R.string.skills_page_batch_delete_message, selectedItems.size))
    }
}

@Composable
private fun QuickMessageCard(
    quickMessage: QuickMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().then(dragModifier),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Zap,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quickMessage.title.ifBlank { stringResource(R.string.quick_messages_page_untitled) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content.ifBlank { stringResource(R.string.quick_messages_page_empty_content) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickMessageSelectableCard(
    quickMessage: QuickMessage,
    selected: Boolean,
    onSelectChange: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelectChange() },
            )
            Icon(
                imageVector = HugeIcons.Zap,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quickMessage.title.ifBlank { stringResource(R.string.quick_messages_page_untitled) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content.ifBlank { stringResource(R.string.quick_messages_page_empty_content) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Edit01, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun EditQuickMessageDialog(
    title: String,
    initialQuickMessage: QuickMessage?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
) {
    var quickMessageTitle by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.title ?: "")
    }
    var quickMessageContent by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.content ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = quickMessageTitle,
                    onValueChange = { quickMessageTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_quick_message_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = quickMessageContent,
                    onValueChange = { quickMessageContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_quick_message_content)) },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(quickMessageTitle.trim(), quickMessageContent.trim()) },
                enabled = quickMessageTitle.isNotBlank() && quickMessageContent.isNotBlank(),
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}