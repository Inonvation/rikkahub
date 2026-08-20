package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.export.CustomModeSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.ui.components.ai.ModePickerSheet
import me.rerere.rikkahub.ui.components.ai.description
import me.rerere.rikkahub.ui.components.ai.displayName
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.ai.note
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.explainErrorText
import org.koin.androidx.compose.koinViewModel

/** 全局默认能力模式设置页，位于「默认模型和提示词」设置项下方入口。 */
@Composable
fun SettingModePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var editingCustom by remember { mutableStateOf<CustomModeConfig?>(null) }
    var exportCustom by remember { mutableStateOf<CustomModeConfig?>(null) }

    val toaster = LocalToaster.current
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(CustomModeSerializer) { result ->
        result.onSuccess { imported ->
            vm.importCustomMode(imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(explainErrorText(error.message)), type = ToastType.Error)
        }
    }

    SettingScaffold(
        title = stringResource(R.string.setting_mode_page_title),
        loading = settings.init,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                IosGroup {
                    item(
                        onClick = { showPicker = true },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_mode)) },
                        trailingContent = {
                            Text(
                                text = modeRefDisplayName(settings.defaultMode, settings.customModes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                }
            }
            item {
                ModeAssembleGroup(
                    settings = settings,
                    onImport = { importer.importFromFile() },
                    onEdit = { editingCustom = it },
                    onExport = { exportCustom = it },
                    onDelete = { vm.requestDeleteCustomMode(it) },
                    onDuplicate = { vm.duplicateCustomMode(it) },
                    onMove = { from, to -> moveCustomMode(settings, vm, from, to) },
                )
            }
        }
    }

    if (showPicker) {
        ModePickerSheet(
            selectedRef = settings.defaultMode,
            customModes = settings.customModes,
            showFollowGlobal = true,
            onSelect = { ref ->
                showPicker = false
                vm.updateSettings(settings.copy(defaultMode = ref))
            },
            onDismiss = { showPicker = false },
        )
    }

    editingCustom?.let { custom ->
        CustomModeEditSheet(
            custom = custom,
            existingModes = settings.customModes,
            onDismiss = { editingCustom = null },
            onSave = { edited ->
                editingCustom = null
                vm.upsertCustomMode(edited)
            },
        )
    }

    exportCustom?.let { custom ->
        val exporter = rememberExporter(custom, CustomModeSerializer)
        ExportDialog(
            exporter = exporter,
            onDismiss = { exportCustom = null },
        )
    }

    pendingDelete?.let { request ->
        DeleteCustomModeDialog(
            request = request,
            onConfirm = { vm.confirmDeleteCustomMode() },
            onDismiss = { vm.cancelDeleteCustomMode() },
        )
    }
}

private fun moveCustomMode(settings: Settings, vm: SettingVM, from: Int, to: Int) {
    val modes = settings.customModes.toMutableList()
    if (from !in modes.indices || to !in modes.indices) return
    val item = modes.removeAt(from)
    modes.add(to, item)
    vm.updateSettings(settings.copy(customModes = modes))
}

/** 模式说明 + 组装内容清单：内置四模式 + 管理模式生成的自定义模式（可编辑/复制/导出/删除/排序）。 */
@Composable
private fun ModeAssembleGroup(
    settings: Settings,
    onImport: () -> Unit,
    onEdit: (CustomModeConfig) -> Unit,
    onExport: (CustomModeConfig) -> Unit,
    onDelete: (CustomModeConfig) -> Unit,
    onDuplicate: (CustomModeConfig) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_mode_page_group_intro),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 4.dp),
            )
            TextButton(onClick = onImport) {
                Icon(
                    imageVector = HugeIcons.FileImport,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.setting_mode_page_import))
            }
        }
        ChatMode.entries.forEach { mode ->
            ModeDetailCard(
                title = mode.displayName(),
                description = mode.description(),
                capabilities = mode.policy().capabilities,
            )
        }
        settings.customModes.forEachIndexed { index, custom ->
            CustomModeDetailCard(
                custom = custom,
                onEdit = { onEdit(custom) },
                onDuplicate = { onDuplicate(custom) },
                onExport = { onExport(custom) },
                onDelete = { onDelete(custom) },
                canMoveUp = index > 0,
                canMoveDown = index < settings.customModes.lastIndex,
                onMoveUp = { onMove(index, index - 1) },
                onMoveDown = { onMove(index, index + 1) },
            )
        }
    }
}

/** 内置模式卡片：点击展开「组装内容」能力清单。 */
@Composable
private fun ModeDetailCard(
    title: String,
    description: String,
    capabilities: Set<Capability>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CustomColors.cardColorsOnSurfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${capabilities.size} 项能力",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "组装内容",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    capabilities.sortedBy { it.name }.forEach { cap ->
                        Text(
                            text = "- ${cap.name}  // ${cap.note()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 自定义模式卡片：展示名称/描述/组装内容，提供编辑、复制、导出、排序与删除。 */
@Composable
private fun CustomModeDetailCard(
    custom: CustomModeConfig,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var expanded by rememberSaveable(custom.id) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CustomColors.cardColorsOnSurfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = custom.name.ifBlank { custom.id },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (custom.description.isNotBlank()) {
                Text(
                    text = custom.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "组装内容（${custom.policy.capabilities.size} 项能力）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    custom.policy.capabilities.sortedBy { it.name }.forEach { cap ->
                        Text(
                            text = "- ${cap.name}  // ${cap.note()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canMoveUp) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = HugeIcons.ArrowUp01,
                            contentDescription = stringResource(R.string.setting_mode_page_move_up),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (canMoveDown) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = stringResource(R.string.setting_mode_page_move_down),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = stringResource(R.string.setting_mode_page_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.setting_mode_page_duplicate),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = HugeIcons.FileDownload,
                        contentDescription = stringResource(R.string.setting_mode_page_export),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_mode_page_delete_custom),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 自定义模式编辑弹层：名称、描述和逐项能力开关。 */
@Composable
private fun CustomModeEditSheet(
    custom: CustomModeConfig,
    existingModes: List<CustomModeConfig>,
    onDismiss: () -> Unit,
    onSave: (CustomModeConfig) -> Unit,
) {
    var name by remember(custom.id) { mutableStateOf(custom.name) }
    var nameError by remember(custom.id) { mutableStateOf<String?>(null) }
    var description by remember(custom.id) { mutableStateOf(custom.description) }
    var capabilities by remember(custom.id) { mutableStateOf(custom.policy.capabilities) }
    val duplicateNameError = stringResource(R.string.setting_mode_page_duplicate_name)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_mode_page_edit_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text(stringResource(R.string.setting_mode_page_mode_name)) },
                isError = nameError != null,
                supportingText = {
                    nameError?.let { Text(it) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.setting_mode_page_mode_desc)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.setting_mode_page_capabilities),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Capability.entries.sortedBy { it.name }.forEach { cap ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cap.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetbrainsMono),
                        )
                        Text(
                            text = cap.note(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = cap in capabilities,
                        onCheckedChange = { checked ->
                            capabilities = if (checked) capabilities + cap else capabilities - cap
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_mode_page_cancel))
                }
                TextButton(
                    onClick = {
                        val finalName = name.trim().ifBlank { custom.name.ifBlank { custom.id } }
                        if (existingModes.any { it.id != custom.id && it.name.equals(finalName, ignoreCase = true) }) {
                            nameError = duplicateNameError
                            return@TextButton
                        }
                        onSave(
                            custom.copy(
                                name = finalName,
                                description = description.trim(),
                                policy = ChatModePolicy(capabilities = capabilities),
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.setting_mode_page_save))
                }
            }
        }
    }
}

/** 删除前提示：展示引用该模式的会话数，明确删除后的回退行为。 */
@Composable
private fun DeleteCustomModeDialog(
    request: CustomModeDeleteRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    RikkaConfirmDialog(
        show = true,
        title = stringResource(R.string.setting_mode_page_delete_title),
        confirmText = stringResource(R.string.setting_mode_page_delete_custom),
        dismissText = stringResource(R.string.setting_mode_page_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        text = {
            Text(
                text = if (request.conversationCount > 0) {
                    stringResource(R.string.setting_mode_page_delete_warning, request.conversationCount)
                } else {
                    stringResource(R.string.setting_mode_page_delete_no_ref)
                }
            )
        }
    )
}
