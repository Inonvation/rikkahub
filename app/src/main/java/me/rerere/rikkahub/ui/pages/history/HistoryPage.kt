package me.rerere.rikkahub.ui.pages.history;

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    // 多选删除
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) {
        selecting = false
        selectedItems.clear()
    }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.history_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selecting) {
                        IconButton(
                            onClick = {
                                if (selectedItems.size == conversations.size) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.clear()
                                    selectedItems.addAll(conversations.map { it.id })
                                }
                            }
                        ) {
                            Icon(
                                HugeIcons.CursorPointer01,
                                contentDescription = stringResource(
                                    if (selectedItems.size == conversations.size) {
                                        R.string.history_page_deselect_all
                                    } else {
                                        R.string.history_page_select_all
                                    }
                                )
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        IconButton(
                            onClick = { selecting = true }
                        ) {
                            Icon(
                                HugeIcons.MoreVertical,
                                contentDescription = stringResource(R.string.history_page_batch_select)
                            )
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                if (selecting) {
                    ConversationItem(
                        conversation = conversation,
                        selecting = true,
                        selected = conversation.id in selectedItems,
                        onSelectChange = {
                            if (conversation.id in selectedItems) {
                                selectedItems.remove(conversation.id)
                            } else {
                                selectedItems.add(conversation.id)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                } else {
                    SwipeableConversationItem(
                        conversation = conversation,
                        onClick = {
                            navigateToChatPage(navController, conversation.id)
                        },
                        onDelete = {
                            scope.launch {
                                // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                                val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                                vm.deleteConversation(conversation)
                                val result = snackbarHostState.showSnackbar(
                                    message = snackMessageDeleted,
                                    actionLabel = snackMessageUndo,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.restoreConversation(fullConversation)
                                }
                            }
                        },
                        onTogglePin = { vm.togglePinStatus(conversation.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                }
            }
        }
    }

    // 批量删除操作条
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
    ) {
        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it * 2 }),
            exit = slideOutVertically(targetOffsetY = { it * 2 }),
        ) {
            HorizontalFloatingToolbar(expanded = true) {
                Tooltip(
                    tooltip = {
                        Text(stringResource(R.string.history_page_cancel))
                    }
                ) {
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
                                if (selectedItems.size == conversations.size) {
                                    R.string.history_page_deselect_all
                                } else {
                                    R.string.history_page_select_all
                                }
                            )
                        )
                    }
                ) {
                    IconButton(
                        onClick = {
                            if (selectedItems.size == conversations.size) {
                                selectedItems.clear()
                            } else {
                                selectedItems.clear()
                                selectedItems.addAll(conversations.map { it.id })
                            }
                        }
                    ) {
                        Icon(HugeIcons.CursorPointer01, null)
                    }
                }
                Tooltip(
                    tooltip = {
                        Text(stringResource(R.string.history_page_delete))
                    }
                ) {
                    FilledIconButton(
                        onClick = {
                            if (selectedItems.isNotEmpty()) {
                                showBatchDeleteDialog = true
                            }
                        },
                        enabled = selectedItems.isNotEmpty(),
                    ) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.history_page_delete))
                    }
                }
            }
        }
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_selected_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_selected_confirmation, selectedItems.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = conversations.filter { it.id in selectedItems }
                        showBatchDeleteDialog = false
                        if (toDelete.isEmpty()) {
                            selectedItems.clear()
                            selecting = false
                        } else {
                            scope.launch {
                                // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                                val fullConversations = toDelete.mapNotNull { vm.getFullConversation(it.id) }
                                vm.deleteConversations(fullConversations)
                                selectedItems.clear()
                                selecting = false
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(
                                        R.string.history_page_conversations_deleted,
                                        fullConversations.size
                                    ),
                                    actionLabel = snackMessageUndo,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    fullConversations.forEach { vm.restoreConversation(it) }
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatchDeleteDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelectChange: () -> Unit = {},
) {
    Surface(
        onClick = if (selecting) onSelectChange else onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier
    ) {
        ListItem(
            leadingContent = if (selecting) {
                {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onSelectChange() }
                    )
                }
            } else {
                null
            },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = if (selecting) {
                null
            } else {
                {
                    IconButton(
                        onClick = onTogglePin
                    ) {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                                R.string.history_page_pin
                            )
                        )
                    }
                }
            }
        )
    }
}
