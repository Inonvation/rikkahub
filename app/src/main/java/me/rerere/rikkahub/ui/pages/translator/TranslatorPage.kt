package me.rerere.rikkahub.ui.pages.translator

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDataTransferHorizontal
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.explainErrorText
import me.rerere.rikkahub.utils.getText
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun TranslatorPage(vm: TranslatorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val sourceLanguage by vm.sourceLanguage.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedRecordMessage = stringResource(R.string.translator_history_record_deleted)
    val undoLabel = stringResource(R.string.translator_history_undo)
    var clipboardCandidate by remember { mutableStateOf<String?>(null) }
    var showClipboardPrompt by remember { mutableStateOf(false) }

    // 历史模式状态：历史视图是翻译页内的子模式而非独立路由。
    // 原因：本项目导航是自研 Navigator（仅存 NavKey 栈），无跨 destination 的结果通道；
    // 放同页内可以天然复用同一个 TranslatorVM，点历史回填 = 直接改 VM 状态，无跨页传参。
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
    }

    // 返回键优先级：多选态先退出多选，再退历史视图
    BackHandler(enabled = showHistory && !selecting) { showHistory = false }
    BackHandler(enabled = selecting) { exitSelection() }

    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(explainErrorText(error.message), type = ToastType.Error)
        }
    }

    LaunchedEffect(Unit) {
        val clipText = clipboard.getClipEntry()?.clipData?.getText()
        if (!clipText.isNullOrBlank()) {
            clipboardCandidate = clipText
            showClipboardPrompt = true
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            if (showHistory) {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.translator_history_title))
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selecting) exitSelection() else showHistory = false
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowLeft01,
                                contentDescription = stringResource(R.string.translator_history_back)
                            )
                        }
                    },
                    actions = {
                        if (!selecting && records.isNotEmpty()) {
                            IconButton(
                                onClick = { selecting = true }
                            ) {
                                Icon(
                                    HugeIcons.MoreVertical,
                                    contentDescription = stringResource(R.string.translator_history_batch_select)
                                )
                            }
                            IconButton(
                                onClick = { showDeleteAllDialog = true }
                            ) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.translator_history_delete_all)
                                )
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.translator_page_title))
                    },
                    navigationIcon = {
                        BackButton()
                    },
                    actions = {
                        IconButton(
                            onClick = { showHistory = true }
                        ) {
                            Icon(
                                HugeIcons.Clock02,
                                contentDescription = stringResource(R.string.translator_history_open)
                            )
                        }
                        ModelSelector(
                            modelId = settings.translateModeId,
                            onSelect = {
                                vm.updateSettings(settings.copy(translateModeId = it.id))
                            },
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            onlyIcon = true,
                        )
                    }
                )
            }
        }
    ) { paddingValues ->
        if (showHistory) {
            TranslationHistoryList(
                records = records,
                selecting = selecting,
                selectedIds = selectedIds,
                onSelectChange = { id ->
                    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
                },
                onOpenRecord = { record ->
                    // 回填到翻译视图：原样带回复原文/译文/语言对，可继续复制或修改后重翻
                    vm.restoreRecord(record.id)
                    showHistory = false
                },
                onLongPressRecord = { record ->
                    // 长按直接进入多选并选中该项
                    if (record.id !in selectedIds) {
                        selectedIds.add(record.id)
                    }
                    selecting = true
                },
                onDeleteRecord = { record ->
                    // 左滑单条删除：先删后给撤销入口（误滑兜底），撤销按原记录原样插回
                    scope.launch {
                        vm.deleteRecords(listOf(record.id))
                        val result = snackbarHostState.showSnackbar(
                            message = deletedRecordMessage,
                            actionLabel = undoLabel,
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            vm.undeleteRecord(record)
                        }
                    }
                },
                onSelectAllToggle = {
                    if (selectedIds.size == records.size) {
                        selectedIds.clear()
                    } else {
                        selectedIds.clear()
                        selectedIds.addAll(records.map { it.id })
                    }
                },
                onCancelSelect = { exitSelection() },
                onDeleteSelectedClick = {
                    if (selectedIds.isNotEmpty()) {
                        showBatchDeleteDialog = true
                    }
                },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LanguageRow(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    onSourceLanguageSelected = vm::updateSourceLanguage,
                    onTargetLanguageSelected = vm::updateTargetLanguage,
                    onSwap = vm::swapLanguages
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.translator_page_source_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CardToolButton(
                                    label = stringResource(R.string.translator_page_paste),
                                    icon = HugeIcons.Clipboard,
                                    onClick = {
                                        scope.launch {
                                            clipboard.getClipEntry()?.clipData?.getText()?.let {
                                                vm.updateInputText(it)
                                            }
                                        }
                                    }
                                )
                                CardToolButton(
                                    label = stringResource(R.string.translator_page_clear),
                                    icon = HugeIcons.Delete01,
                                    enabled = inputText.isNotBlank(),
                                    onClick = vm::clearInput
                                )
                            }
                        }
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            BasicTextField(
                                value = inputText,
                                onValueChange = vm::updateInputText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .requiredHeightIn(min = 0.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 10,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (inputText.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.translator_page_input_placeholder),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (translating) {
                            vm.cancelTranslation()
                        } else {
                            vm.translate()
                        }
                    },
                    enabled = translating || inputText.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (translating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.translator_page_cancel))
                    } else {
                        Icon(
                            imageVector = HugeIcons.ArrowDataTransferHorizontal,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.translator_page_translate))
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.translation_text),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CardToolButton(
                                label = stringResource(R.string.copy),
                                icon = HugeIcons.Copy01,
                                enabled = translatedText.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    null,
                                                    translatedText
                                                )
                                            )
                                        )
                                    }
                                }
                            )
                        }

                        if (translating && translatedText.isBlank()) {
                            LinearWavyProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                            Text(
                                text = stringResource(R.string.translating),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = translatedText.ifEmpty {
                                        stringResource(R.string.translator_page_result_placeholder)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (translatedText.isBlank()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.translator_history_delete_selected_title)) },
            text = { Text(stringResource(R.string.translator_history_delete_selected_message, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        showBatchDeleteDialog = false
                        exitSelection()
                        scope.launch {
                            vm.deleteRecords(ids)
                            toaster.show(context.getString(R.string.translator_history_deleted, ids.size))
                        }
                    }
                ) {
                    Text(stringResource(R.string.translator_history_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatchDeleteDialog = false }
                ) {
                    Text(stringResource(R.string.translator_page_cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.translator_history_delete_all)) },
            text = { Text(stringResource(R.string.translator_history_delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        scope.launch {
                            vm.deleteAllRecords()
                            toaster.show(context.getString(R.string.translator_history_cleared))
                        }
                    }
                ) {
                    Text(stringResource(R.string.translator_history_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.translator_page_cancel))
                }
            }
        )
    }

    if (showClipboardPrompt && !showHistory && !clipboardCandidate.isNullOrBlank()) {
        ClipboardPromptDialog(
            content = clipboardCandidate.orEmpty(),
            onConfirm = {
                val text = clipboardCandidate.orEmpty()
                showClipboardPrompt = false
                clipboardCandidate = null
                if (text.isNotBlank()) {
                    vm.updateInputText(text)
                    vm.translate()
                }
            },
            onDismiss = {
                showClipboardPrompt = false
                clipboardCandidate = null
            }
        )
    }
}

@Composable
private fun LanguageRow(
    sourceLanguage: Locale?,
    targetLanguage: Locale,
    onSourceLanguageSelected: (Locale?) -> Unit,
    onTargetLanguageSelected: (Locale) -> Unit,
    onSwap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanguageDropdown(
            selected = sourceLanguage,
            includeAuto = true,
            onLanguageSelected = onSourceLanguageSelected,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = onSwap,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = HugeIcons.ArrowDataTransferHorizontal,
                contentDescription = stringResource(R.string.translator_page_swap),
                modifier = Modifier.size(18.dp)
            )
        }
        LanguageDropdown(
            selected = targetLanguage,
            includeAuto = false,
            onLanguageSelected = { language ->
                if (language != null) {
                    onTargetLanguageSelected(language)
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LanguageDropdown(
    selected: Locale?,
    includeAuto: Boolean,
    onLanguageSelected: (Locale?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = if (selected == null) {
        stringResource(R.string.translator_page_auto_detect)
    } else {
        localeDisplayName(selected)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = HugeIcons.LanguageCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (includeAuto) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.translator_page_auto_detect))
                    },
                    onClick = {
                        onLanguageSelected(null)
                        expanded = false
                    }
                )
            }
            TranslatorSupportedLocales.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Text(localeDisplayName(language))
                    },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CardToolButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.height(30.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ClipboardPromptDialog(
    content: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.translator_page_clipboard_detected_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.translator_page_clipboard_detected_desc))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.translator_page_clipboard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.translator_page_clipboard_dismiss))
            }
        }
    )
}
