package me.rerere.rikkahub.ui.pages.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun KnowledgeBasesPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<KnowledgeBasesVM>()
    val bases = vm.bases
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showCreateDialog by remember { mutableStateOf(false) }

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
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        vm.reorderBases(from.index, to.index)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_knowledge_bases)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            if (selectedItems.size == bases.size) {
                                selectedItems.clear()
                            } else {
                                selectedItems.clear()
                                selectedItems.addAll(bases.map { it.id })
                            }
                        }) {
                            Icon(
                                HugeIcons.CursorPointer01,
                                contentDescription = stringResource(
                                    if (selectedItems.size == bases.size) {
                                        R.string.skills_page_deselect_all
                                    } else {
                                        R.string.skills_page_select_all
                                    }
                                ),
                            )
                        }
                    } else {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(HugeIcons.Add01, contentDescription = null)
                        }
                        IconButton(onClick = { selecting = true }) {
                            Icon(
                                HugeIcons.MoreVertical,
                                contentDescription = stringResource(R.string.skills_page_batch_select),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (bases.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setting_page_knowledge_bases),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { showCreateDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = null)
                        Text(stringResource(R.string.knowledge_page_create_base))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding + PaddingValues(12.dp) + PaddingValues(bottom = 72.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    state = lazyListState,
                ) {
                    items(bases, key = { it.id }) { base ->
                        if (selecting) {
                            KnowledgeBaseSelectableCard(
                                base = base,
                                selected = selectedItems.contains(base.id),
                                onSelectChange = {
                                    if (!selectedItems.contains(base.id)) {
                                        selectedItems.add(base.id)
                                    } else {
                                        selectedItems.remove(base.id)
                                    }
                                },
                                onClick = { navController.navigate(Screen.KnowledgeBaseDetail(base.id)) },
                            )
                        } else {
                            ReorderableItem(
                                state = reorderableState,
                                key = base.id,
                            ) { isDragging ->
                                KnowledgeBaseCard(
                                    base = base,
                                    onClick = { navController.navigate(Screen.KnowledgeBaseDetail(base.id)) },
                                    modifier = Modifier
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                hapticController.perform(HapticFeedbackType.GestureEnd)
                                                vm.persistOrder()
                                            }
                                        )
                                        .graphicsLayer {
                                            if (isDragging) {
                                                scaleX = 1.05f
                                                scaleY = 1.05f
                                            }
                                        },
                                )
                            }
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
                                    if (selectedItems.size == bases.size) {
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
                                if (selectedItems.size == bases.size) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.clear()
                                    selectedItems.addAll(bases.map { it.id })
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

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.knowledge_page_create_base)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.knowledge_page_create_base_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch {
                                val id = vm.createBase(name.trim())
                                if (id != null) {
                                    navController.navigate(Screen.KnowledgeBaseDetail(id))
                                    showCreateDialog = false
                                } else {
                                    toaster.show("已存在同名知识库", type = ToastType.Error)
                                }
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.knowledge_page_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    RikkaConfirmDialog(
        show = showBatchDeleteDialog,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            vm.deleteBases(selectedItems.toList())
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
private fun KnowledgeBaseCard(
    base: me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    base.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Tag {
                    Text("${base.documentCount} 文档")
                }
                if (base.chunkCount > 0) {
                    Tag { Text("${base.chunkCount} chunks") }
                }
            }
            if (base.description.isNotBlank()) {
                Text(
                    text = base.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun KnowledgeBaseSelectableCard(
    base: me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount,
    selected: Boolean,
    onSelectChange: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelectChange() },
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        base.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Tag {
                        Text("${base.documentCount} 文档")
                    }
                    if (base.chunkCount > 0) {
                        Tag { Text("${base.chunkCount} chunks") }
                    }
                }
                if (base.description.isNotBlank()) {
                    Text(
                        text = base.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}