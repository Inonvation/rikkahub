package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.knowledge.KnowledgeManager
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import org.koin.compose.koinInject

@OptIn(ExperimentalUuidApi::class)
@Composable
fun KnowledgeBasePickerButton(
    selectedIds: Set<Uuid>,
    onSelectionChange: (Set<Uuid>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showPicker by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val hapticController = rememberHaptic()
    val knowledgeManager = koinInject<KnowledgeManager>()
    val bases by knowledgeManager.baseRepository.getAllWithDocumentCount()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val hasSelection = selectedIds.isNotEmpty()

    IconButton(
        enabled = enabled,
        onClick = {
            hapticController.perform(HapticFeedbackType.KeyboardTap)
            showPicker = true
        },
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            HugeIcons.Bookshelf01,
            contentDescription = "知识库",
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                hasSelection -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Text(
                    "选择知识库",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "选中的知识库将在对话中供 AI 检索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

                if (bases.isEmpty()) {
                    Text(
                        "还没有知识库，请先在扩展管理中创建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(bases, key = { it.id }) { base ->
                        val baseUuid = Uuid.parse(base.id)
                        val isSelected = baseUuid in selectedIds
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(base.name, style = MaterialTheme.typography.titleSmall)
                                if (base.description.isNotBlank()) {
                                    Text(
                                        base.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    "${base.documentCount} 文档",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        onSelectionChange(selectedIds + baseUuid)
                                    } else {
                                        onSelectionChange(selectedIds - baseUuid)
                                    }
                                },
                            )
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(
                            onClick = {
                                showPicker = false
                                navController.navigate(Screen.KnowledgeBases)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("管理知识库")
                        }
                    }
                }
            }
        }
    }
}
