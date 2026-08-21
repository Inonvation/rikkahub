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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.export.CustomModeSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.model.effectivePolicy
import me.rerere.rikkahub.data.model.restrictedCapabilities
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
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.explainErrorText
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/** 全局默认能力模式设置页，位于「默认模型和提示词」设置项下方入口。 */
@Composable
fun SettingModePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var editingMode by remember { mutableStateOf<ModeEditState?>(null) }
    var exportCustom by remember { mutableStateOf<CustomModeConfig?>(null) }
    val navController = LocalNavController.current
    val newModeStarterPrompt = stringResource(R.string.setting_mode_page_new_mode_starter)

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
                        supportingContent = if (settings.defaultMode == null) {
                            {
                                Text(
                                    text = stringResource(R.string.setting_mode_page_follow_assistant_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                        trailingContent = {
                            Text(
                                text = modeRefDisplayName(
                                    settings.defaultMode,
                                    settings.customModes,
                                    settings.builtinModeOverrides,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                }
            }
            if (!settings.init) {
                item {
                    Card(
                        onClick = {
                            navigateToChatPage(
                                navigator = navController,
                                chatId = Uuid.random(),
                                initText = newModeStarterPrompt.base64Encode(),
                                mode = ModeRefs.builtin(ChatMode.CREATIVE),
                            )
                        },
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.setting_mode_page_new_mode_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.setting_mode_page_new_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                ModeAssembleGroup(
                    settings = settings,
                    onImport = { importer.importFromFile() },
                    onEditCustom = { editingMode = ModeEditState.custom(it) },
                    onEditBuiltin = { mode, policy, name, description ->
                        editingMode = ModeEditState.builtin(
                            mode = mode,
                            policy = policy,
                            name = name,
                            description = description,
                        )
                    },
                    onExportCustom = { exportCustom = it },
                    onExportBuiltin = { exportCustom = it },
                    onDelete = { vm.requestDeleteCustomMode(it) },
                    onDuplicateCustom = { vm.duplicateCustomMode(it) },
                    onDuplicateBuiltin = { vm.duplicateCustomMode(it) },
                    onResetBuiltin = { vm.resetBuiltinMode(it) },
                    onMove = { from, to -> moveCustomMode(settings, vm, from, to) },
                )
            }
            item {
                Card(
                    onClick = { navController.navigate(Screen.ManagementDashboard) },
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HugeIcons.ServerStack01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_page_management_console),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.setting_page_console_bottom_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModePickerSheet(
            selectedRef = settings.defaultMode,
            customModes = settings.customModes,
            builtinModeOverrides = settings.builtinModeOverrides,
            showFollowGlobal = true,
            onSelect = { ref ->
                showPicker = false
                vm.updateSettings(settings.copy(defaultMode = ref))
            },
            onDismiss = { showPicker = false },
        )
    }

    editingMode?.let { state ->
        ModeEditSheet(
            state = state,
            existingModes = settings.customModes,
            onDismiss = { editingMode = null },
            onSaveCustom = { edited ->
                editingMode = null
                vm.upsertCustomMode(edited)
            },
            onSaveBuiltin = { mode, policy ->
                editingMode = null
                vm.upsertBuiltinMode(mode, policy)
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

/** 模式说明 + 组装内容清单：内置四模式与自定义模式共用同一卡片样式，支持编辑/复制/导出/排序/删除。 */
@Composable
private fun ModeAssembleGroup(
    settings: Settings,
    onImport: () -> Unit,
    onEditCustom: (CustomModeConfig) -> Unit,
    onEditBuiltin: (ChatMode, ChatModePolicy, String, String) -> Unit,
    onExportCustom: (CustomModeConfig) -> Unit,
    onExportBuiltin: (CustomModeConfig) -> Unit,
    onDelete: (CustomModeConfig) -> Unit,
    onDuplicateCustom: (CustomModeConfig) -> Unit,
    onDuplicateBuiltin: (CustomModeConfig) -> Unit,
    onResetBuiltin: (ChatMode) -> Unit,
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
            val modified = mode in settings.builtinModeOverrides
            val policy = mode.effectivePolicy(settings)
            val name = mode.displayName()
            val description = mode.description()
            val builtinConfig = CustomModeConfig(
                name = name,
                description = description,
                policy = policy,
            )
            ModeDetailCard(
                rememberKey = mode.name,
                title = name,
                description = description,
                capabilities = policy.capabilities,
                restricted = policy.restrictedCapabilities(settings),
                tag = if (modified) {
                    stringResource(R.string.setting_mode_page_preset_modified)
                } else {
                    stringResource(R.string.setting_mode_page_preset)
                },
                tagColor = if (modified) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                actions = {
                    if (modified) {
                        IconButton(
                            onClick = { onResetBuiltin(mode) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = stringResource(R.string.setting_mode_page_reset),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onEditBuiltin(mode, policy, name, description) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Edit01,
                            contentDescription = stringResource(R.string.setting_mode_page_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { onDuplicateBuiltin(builtinConfig) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.setting_mode_page_duplicate),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { onExportBuiltin(builtinConfig) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileDownload,
                            contentDescription = stringResource(R.string.setting_mode_page_export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
        settings.customModes.forEachIndexed { index, custom ->
            ModeDetailCard(
                rememberKey = custom.id,
                title = custom.name.ifBlank { custom.id },
                description = custom.description,
                capabilities = custom.policy.capabilities,
                restricted = custom.policy.restrictedCapabilities(settings),
                tag = stringResource(R.string.setting_mode_page_custom),
                tagColor = MaterialTheme.colorScheme.primary,
                actions = {
                    if (index > 0) {
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowUp01,
                                contentDescription = stringResource(R.string.setting_mode_page_move_up),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (index < settings.customModes.lastIndex) {
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = stringResource(R.string.setting_mode_page_move_down),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onEditCustom(custom) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Edit01,
                            contentDescription = stringResource(R.string.setting_mode_page_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { onDuplicateCustom(custom) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.setting_mode_page_duplicate),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { onExportCustom(custom) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileDownload,
                            contentDescription = stringResource(R.string.setting_mode_page_export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { onDelete(custom) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_mode_page_delete_custom),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

/** 模式卡片：预设与自定义共用同一展示和操作样式。 */
@Composable
private fun ModeDetailCard(
    rememberKey: String,
    title: String,
    description: String,
    capabilities: Set<Capability>,
    restricted: Set<Capability> = emptySet(),
    tag: String,
    tagColor: Color,
    actions: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(rememberKey) { mutableStateOf(false) }
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
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = tagColor,
                )
                Spacer(Modifier.width(8.dp))
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
            if (restricted.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.setting_mode_page_restricted_hint,
                        restricted.sortedBy { it.name }.joinToString("、") { it.note() },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

/** 编辑弹层状态：自定义模式可改名/描述，预设模式固定名称与描述，只编辑能力开关。 */
private data class ModeEditState(
    val custom: CustomModeConfig?,
    val builtin: ChatMode?,
    val policy: ChatModePolicy?,
    val builtinName: String,
    val builtinDescription: String,
) {
    companion object {
        fun custom(custom: CustomModeConfig) = ModeEditState(
            custom = custom,
            builtin = null,
            policy = null,
            builtinName = "",
            builtinDescription = "",
        )

        fun builtin(mode: ChatMode, policy: ChatModePolicy, name: String, description: String) = ModeEditState(
            custom = null,
            builtin = mode,
            policy = policy,
            builtinName = name,
            builtinDescription = description,
        )
    }
}

/** 模式编辑弹层：名称、描述和逐项能力开关，预设与自定义共用。 */
@Composable
private fun ModeEditSheet(
    state: ModeEditState,
    existingModes: List<CustomModeConfig>,
    onDismiss: () -> Unit,
    onSaveCustom: (CustomModeConfig) -> Unit,
    onSaveBuiltin: (ChatMode, ChatModePolicy) -> Unit,
) {
    val custom = state.custom
    val builtin = state.builtin
    var name by remember(state) { mutableStateOf(custom?.name.orEmpty()) }
    var nameError by remember(state) { mutableStateOf<String?>(null) }
    var description by remember(state) { mutableStateOf(custom?.description.orEmpty()) }
    var capabilities by remember(state) {
        mutableStateOf(custom?.policy?.capabilities ?: state.policy?.capabilities ?: emptySet())
    }
    var behaviorProfile by remember(state) {
        mutableStateOf(
            (custom?.policy ?: state.policy)?.behaviorProfile ?: AgentBehaviorProfile.STANDARD
        )
    }
    val builtinNames = ChatMode.entries.flatMap { listOf(it.name, it.displayName()) }
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
                text = if (builtin != null) {
                    stringResource(R.string.setting_mode_page_edit_preset_title)
                } else {
                    stringResource(R.string.setting_mode_page_edit_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (builtin != null) {
                Text(
                    text = stringResource(R.string.setting_mode_page_builtin_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (custom != null) {
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
            } else {
                OutlinedTextField(
                    value = state.builtinName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.setting_mode_page_mode_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.builtinDescription,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.setting_mode_page_mode_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(R.string.setting_mode_page_behavior),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val behaviorProfiles = listOf(
                    AgentBehaviorProfile.STANDARD,
                    AgentBehaviorProfile.WORKSPACE,
                    AgentBehaviorProfile.MANAGEMENT,
                    AgentBehaviorProfile.MINIMAL,
                )
                behaviorProfiles.forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = behaviorProfile == profile,
                        onClick = { behaviorProfile = profile },
                        shape = SegmentedButtonDefaults.itemShape(index, behaviorProfiles.size),
                    ) {
                        Text(
                            text = when (profile) {
                                AgentBehaviorProfile.STANDARD -> stringResource(R.string.mode_behavior_standard)
                                AgentBehaviorProfile.WORKSPACE -> stringResource(R.string.mode_behavior_workspace)
                                AgentBehaviorProfile.MANAGEMENT -> stringResource(R.string.mode_behavior_management)
                                AgentBehaviorProfile.MINIMAL -> stringResource(R.string.mode_behavior_minimal)
                                AgentBehaviorProfile.LEGACY -> ""
                            }
                        )
                    }
                }
            }
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
                            capabilities = when {
                                checked && cap == Capability.SKILL_ADMIN ->
                                    capabilities + Capability.SKILL_ADMIN + Capability.SKILL_USE

                                checked && cap == Capability.MCP_ADMIN ->
                                    capabilities + Capability.MCP_ADMIN + Capability.MCP_USE

                                checked -> capabilities + cap
                                else -> capabilities - cap
                            }
                        },
                    )
                }
            }
            val dependencyWarning = buildString {
                if (Capability.SKILL_ADMIN in capabilities && Capability.SKILL_USE !in capabilities) {
                    appendLine("SKILL_ADMIN 需要同时开启 SKILL_USE")
                }
                if (Capability.MCP_ADMIN in capabilities && Capability.MCP_USE !in capabilities) {
                    append("MCP_ADMIN 需要同时开启 MCP_USE")
                }
            }.trim()
            if (dependencyWarning.isNotEmpty()) {
                Text(
                    text = dependencyWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                        if (custom != null) {
                            val finalName = name.trim().ifBlank { custom.name.ifBlank { custom.id } }
                            val duplicateCustom = existingModes.any {
                                it.id != custom.id && it.name.equals(finalName, ignoreCase = true)
                            }
                            val duplicateBuiltin =
                                !custom.name.equals(finalName, ignoreCase = true) &&
                                    builtinNames.any { it.equals(finalName, ignoreCase = true) }
                            if (duplicateCustom || duplicateBuiltin) {
                                nameError = duplicateNameError
                                return@TextButton
                            }
                            onSaveCustom(
                                custom.copy(
                                    name = finalName,
                                    description = description.trim(),
                                    policy = ChatModePolicy(
                                        capabilities = capabilities,
                                        behaviorProfileOverride = behaviorProfile,
                                    ),
                                )
                            )
                        } else {
                            builtin?.let {
                                onSaveBuiltin(
                                    it,
                                    ChatModePolicy(
                                        capabilities = capabilities,
                                        behaviorProfileOverride = behaviorProfile,
                                    ),
                                )
                            }
                        }
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
