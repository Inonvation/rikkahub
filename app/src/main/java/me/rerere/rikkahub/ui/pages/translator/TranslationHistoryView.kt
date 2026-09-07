package me.rerere.rikkahub.ui.pages.translator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.TranslationRecordEntity
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant
import java.util.Locale

/**
 * 翻译历史的列表视图：展示原文/译文/语言对/时间。
 *
 * 交互：
 * - 非多选态：点击卡片 → 回填翻译界面；长按 → 进入多选并选中该项；左滑 → 单条删除（撤销由外层 snackbar 处理）。
 * - 多选态：点卡片/勾选框切换选中，底部悬浮工具条（取消 / 全选 / 删除）。
 * 整体与聊天历史页（HistoryPage）保持一致。
 */
@Composable
internal fun TranslationHistoryList(
    records: List<TranslationRecordEntity>,
    selecting: Boolean,
    selectedIds: List<String>,
    onSelectChange: (String) -> Unit,
    onOpenRecord: (TranslationRecordEntity) -> Unit,
    onLongPressRecord: (TranslationRecordEntity) -> Unit,
    onDeleteRecord: (TranslationRecordEntity) -> Unit,
    onSelectAllToggle: () -> Unit,
    onCancelSelect: () -> Unit,
    onDeleteSelectedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = records.isNotEmpty() && selectedIds.size == records.size

    Box(modifier = modifier.fillMaxSize()) {
        if (records.isEmpty()) {
            EmptyHistory(
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                    if (selecting) {
                        HistoryRecordItem(
                            record = record,
                            selecting = true,
                            selected = record.id in selectedIds,
                            onSelectChange = { onSelectChange(record.id) },
                            onOpen = {},
                            onLongPress = {},
                            modifier = itemModifier
                        )
                    } else {
                        SwipeableHistoryRecordItem(
                            record = record,
                            onOpen = { onOpenRecord(record) },
                            onLongPress = { onLongPressRecord(record) },
                            onDelete = { onDeleteRecord(record) },
                            modifier = itemModifier
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selecting && records.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            enter = slideInVertically(initialOffsetY = { it * 2 }),
            exit = slideOutVertically(targetOffsetY = { it * 2 }),
        ) {
            HorizontalFloatingToolbar(expanded = true) {
                Tooltip(
                    tooltip = {
                        Text(stringResource(R.string.translator_page_cancel))
                    }
                ) {
                    IconButton(onClick = onCancelSelect) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.translator_page_cancel)
                        )
                    }
                }
                Tooltip(
                    tooltip = {
                        Text(
                            stringResource(
                                if (allSelected) {
                                    R.string.translator_history_deselect_all
                                } else {
                                    R.string.translator_history_select_all
                                }
                            )
                        )
                    }
                ) {
                    IconButton(onClick = onSelectAllToggle) {
                        Icon(
                            HugeIcons.CursorPointer01,
                            contentDescription = null
                        )
                    }
                }
                Tooltip(
                    tooltip = {
                        Text(stringResource(R.string.translator_history_delete))
                    }
                ) {
                    FilledIconButton(
                        onClick = onDeleteSelectedClick,
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.translator_history_delete)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableHistoryRecordItem(
    record: TranslationRecordEntity,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
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
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.translator_history_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        HistoryRecordItem(
            record = record,
            selecting = false,
            selected = false,
            onSelectChange = {},
            onOpen = onOpen,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = HugeIcons.Clock02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.translator_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRecordItem(
    record: TranslationRecordEntity,
    selecting: Boolean,
    selected: Boolean,
    onSelectChange: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 语言 tag 只作存储格式，展示名按当前系统语言重新解析
    val sourceName = languageLabel(record.sourceLanguage?.let { Locale.forLanguageTag(it) })
    val targetName = localeDisplayName(Locale.forLanguageTag(record.targetLanguage))
    val timeText = remember(record.createdAt) {
        Instant.ofEpochMilli(record.createdAt).toLocalDateTime()
    }
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        shape = shape,
        // 不堆 elevation：与页面输入/输出卡片同款「轻描边」视觉，避免大圆角+悬浮灰块
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = if (selecting && selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = modifier
            // Surface 的 onClick 重载不支持长按，改用 plain Surface + combinedClickable；
            // 先 clip 成卡片圆角，ripple/indication 才不会溢出圆角外。
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    if (selecting) onSelectChange() else onOpen()
                },
                onLongClick = {
                    if (!selecting) onLongPress()
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (selecting) 6.dp else 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
            verticalAlignment = Alignment.Top
        ) {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectChange() }
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$sourceName → $targetName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = record.sourceText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = record.translatedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
