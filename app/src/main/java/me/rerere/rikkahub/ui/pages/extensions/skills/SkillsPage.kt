package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }
    // 批量选择删除
    val selectedItems = remember { mutableStateListOf<String>() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selecting) {
        selecting = false
        selectedItems.clear()
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromName = skills.getOrNull(from.index)?.name ?: return@rememberReorderableLazyListState
        vm.reorderSkill(fromName, to.index)
    }
    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        vm.importSkillFromFile(context, uri) { success, message ->
            if (success) {
                toaster.show(context.getString(R.string.skills_page_import_success, message))
            } else {
                toaster.show(context.getString(R.string.skills_page_import_failed, message))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            if (selectedItems.size == skills.size) {
                                selectedItems.clear()
                            } else {
                                selectedItems.clear()
                                selectedItems.addAll(skills.map { it.name })
                            }
                        }) {
                            Icon(
                                HugeIcons.CursorPointer01,
                                contentDescription = stringResource(
                                    if (selectedItems.size == skills.size) {
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
            )
        },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = { showImportSheet = true }) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            state = lazyListState,
        ) {
            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Puzzle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(skills, key = { it.skillDir.absolutePath }) { skill ->
                if (selecting) {
                    SkillSelectableCard(
                        skill = skill,
                        selected = selectedItems.contains(skill.name),
                        onSelectChange = {
                            if (!selectedItems.contains(skill.name)) {
                                selectedItems.add(skill.name)
                            } else {
                                selectedItems.remove(skill.name)
                            }
                        },
                        onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                    )
                } else {
                    ReorderableItem(
                        state = reorderableState,
                        key = skill.skillDir.absolutePath,
                    ) { isDragging ->
                        SkillCard(
                            skill = skill,
                            onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                            onDelete = { deleteTarget = skill },
                            modifier = Modifier
                                .longPressDraggableHandle()
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
                    Text(stringResource(R.string.skills_page_batch_cancel))
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
                            if (selectedItems.size == skills.size) {
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
                        if (selectedItems.size == skills.size) {
                            selectedItems.clear()
                        } else {
                            selectedItems.clear()
                            selectedItems.addAll(skills.map { it.name })
                        }
                    }
                ) {
                    Icon(HugeIcons.CursorPointer01, null)
                }
            }
            Tooltip(
                tooltip = {
                    Text(stringResource(R.string.skills_page_delete))
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
                    Icon(HugeIcons.Delete01, stringResource(R.string.skills_page_delete))
                }
            }
        }
        }
    }

    if (showImportSheet) {
        SkillImportSheet(
            onDismiss = { showImportSheet = false },
            onAddManually = {
                showImportSheet = false
                showAddDialog = true
            },
            onImportFromFile = {
                showImportSheet = false
                fileImportLauncher.launch(
                    arrayOf(
                        "text/*",
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    )
                )
            },
            onImportFromGitHub = {
                showImportSheet = false
                showImportDialog = true
            },
        )
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(context.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    RikkaConfirmDialog(
        show = showBatchDeleteDialog,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val deletedNames = selectedItems.toList()
            vm.deleteSkills(deletedNames)
            selectedItems.clear()
            selecting = false
            showBatchDeleteDialog = false
        },
        onDismiss = { showBatchDeleteDialog = false },
    ) {
        Text(
            stringResource(
                R.string.skills_page_batch_delete_message,
                selectedItems.size,
            )
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Puzzle,
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
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (!skill.compatibility.isNullOrBlank()) {
                    Text(
                        text = skill.compatibility,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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
private fun SkillSelectableCard(
    skill: SkillMetadata,
    selected: Boolean,
    onSelectChange: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelectChange() },
            )
            Icon(
                imageVector = HugeIcons.Puzzle,
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
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SkillImportSheet(
    onDismiss: () -> Unit,
    onAddManually: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromGitHub: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.skills_page_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SkillImportSheetItem(
                icon = { Icon(HugeIcons.Add01, contentDescription = null) },
                text = stringResource(R.string.skills_page_add_manually),
                onClick = onAddManually,
            )
            SkillImportSheetItem(
                icon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                text = stringResource(R.string.skills_page_import_from_file),
                onClick = onImportFromFile,
            )
            SkillImportSheetItem(
                icon = { Icon(HugeIcons.Download01, contentDescription = null) },
                text = stringResource(R.string.skills_page_import_from_github),
                onClick = onImportFromGitHub,
            )
        }
    }
}

@Composable
private fun SkillImportSheetItem(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(text) },
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"...\"\n---\n\n指令内容...",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    if (nameError) Text(
                        stringResource(R.string.skills_page_name_error),
                        color = MaterialTheme.colorScheme.error
                    )
                    else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                    else Text(stringResource(R.string.skills_page_paste_hint))
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}
