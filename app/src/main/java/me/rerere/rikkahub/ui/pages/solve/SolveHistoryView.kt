package me.rerere.rikkahub.ui.pages.solve

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.unwrapWrappedLatexBlock
import me.rerere.rikkahub.data.db.entity.SolveRecordEntity
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.utils.toLocalDateTime
import java.io.File
import java.time.Instant

/**
 * 解题历史列表视图（与翻译历史同构的交互，版式为分隔线列表而非卡片堆叠）：
 * 展示题目缩略图/题干/精炼作答/时间。行与行之间用低透明度分隔线定界，
 * 去掉整行圆角卡 + 间距（重构根因：卡片化让列表每行独占一个"容器"，信息密度低）。
 *
 * 交互：
 * - 非多选态：点击行 → 回填解题界面；长按 → 进入多选并选中该项；左滑 → 单条删除（撤销由外层 snackbar 处理）。
 * - 多选态：点行/勾选框切换选中，底部悬浮工具条（取消 / 全选 / 删除）。
 */
@Composable
internal fun SolveHistoryList(
    records: List<SolveRecordEntity>,
    selecting: Boolean,
    selectedIds: List<String>,
    onSelectChange: (String) -> Unit,
    onOpenRecord: (SolveRecordEntity) -> Unit,
    onLongPressRecord: (SolveRecordEntity) -> Unit,
    onDeleteRecord: (SolveRecordEntity) -> Unit,
    onSelectAllToggle: () -> Unit,
    onCancelSelect: () -> Unit,
    onDeleteSelectedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = records.isNotEmpty() && selectedIds.size == records.size

    Box(modifier = modifier.fillMaxSize()) {
        if (records.isEmpty()) {
            EmptySolveHistory(
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(records, key = { _, record -> record.id }) { index, record ->
                    // 分隔线放在行首（index>0），最后一行不带拖尾线
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                    if (selecting) {
                        SolveRecordItem(
                            record = record,
                            selecting = true,
                            selected = record.id in selectedIds,
                            onSelectChange = { onSelectChange(record.id) },
                            onOpen = {},
                            onLongPress = {},
                            modifier = itemModifier
                        )
                    } else {
                        SwipeableSolveRecordItem(
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
                        Text(stringResource(R.string.photo_solve_cancel))
                    }
                ) {
                    IconButton(onClick = onCancelSelect) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.photo_solve_cancel)
                        )
                    }
                }
                Tooltip(
                    tooltip = {
                        Text(
                            stringResource(
                                if (allSelected) {
                                    R.string.photo_solve_deselect_all
                                } else {
                                    R.string.photo_solve_select_all
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
                        Text(stringResource(R.string.photo_solve_delete))
                    }
                ) {
                    FilledIconButton(
                        onClick = onDeleteSelectedClick,
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.photo_solve_delete)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableSolveRecordItem(
    record: SolveRecordEntity,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 注意：positionalThreshold 是 @Composable getter，不可放进 remember{}（HistoryPage 同款写法）
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
            // 滑删背景通铺 errorContainer（分隔线列表语言，不再做整行圆角卡背景）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.photo_solve_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        SolveRecordItem(
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
private fun EmptySolveHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = HugeIcons.Camera01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.photo_solve_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SolveRecordItem(
    record: SolveRecordEntity,
    selecting: Boolean,
    selected: Boolean,
    onSelectChange: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeText = remember(record.createdAt) {
        Instant.ofEpochMilli(record.createdAt).toLocalDateTime()
    }
    // 题干为主的列表语义：有题干时题干文本是行主体（markdown/LaTeX 渲染），
    // 答案预览退居单行摘要（完整内容在回填详情页看）；老记录（无题干）保留
    // 原「回退标题 + 答案预览」结构作为降级。
    val question = record.questionText?.takeIf { it.isNotBlank() }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        // 无卡片壳：整行点击（非多选=打开 / 多选=切换选中），ripple 沿行铺开无需再 clip
        modifier = modifier
            .background(
                if (selecting && selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
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
            .padding(
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
        // 题目缩略图（纯文本解题无图时不渲染）；imagePath 是 filesDir 相对路径
        if (record.imagePath.isNotBlank()) {
            val context = LocalContext.current
            val imageFile = remember(record.id) {
                File(context.filesDir, record.imagePath)
            }
            AsyncImage(
                model = imageFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(56.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (question != null) {
                // 题干主体（有题干时优先按题辨认历史）：markdown/LaTeX 渲染，
                // 与结果页题干卡同源；限高渐隐防止长题干把列表行撑爆
                SolveHistoryQuestion(
                    question = question,
                    modifier = Modifier.fillMaxWidth()
                )
                // 底行：答案单行摘要（左侧，右留间隙给时间）+ 时间右下。
                // 摘要不渲染 markdown（单行截断无法容纳公式块），只作扫读提示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = record.answerSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = record.processText.ifBlank { record.finalText },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 内容预览：解题过程/作答用 markdown/latex 渲染（与详情页同源），
                // 限高裁尾 + 底部渐隐，避免长解答把列表行撑爆
                SolveHistoryPreview(
                    process = record.processText,
                    finalAnswer = record.finalText,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 历史行的答案单行摘要：优先作答首行、其次过程首行（去空行），无内容返回空串 */
private fun SolveRecordEntity.answerSummary(): String =
    (finalText.ifBlank { processText })
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

/** 题干主体渲染上限（dp）：约 4 行正文，超出渐隐裁尾 */
private const val QUESTION_MAX_HEIGHT_DP = 96

/**
 * 历史行题干主体：MarkdownBlock 渲染题干（markdown/LaTeX，公式可读），限高渐隐。
 * 题干与答案预览复用同一套裁尾容器（[FadeCropBox]），保持列表行视觉一致。
 */
@Composable
private fun SolveHistoryQuestion(
    question: String,
    modifier: Modifier = Modifier,
) {
    // 题干展示统一去整段 latex 外壳（根因见 unwrapWrappedLatexBlock：整段 $$…$$/
    // \[…\] 包裹会让 MarkdownBlock 把题干当块级公式排版，正文与 markdown 结构失效）
    val content = remember(question) { unwrapWrappedLatexBlock(question) }
    FadeCropBox(maxHeight = QUESTION_MAX_HEIGHT_DP.dp, modifier = modifier) {
        MarkdownBlock(
            content = content,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 历史条目的正文预览：process 与 final 以 [MarkdownBlock] 渲染（公式/加粗/代码可读）。
 * 块高上限 108dp，超出部分被裁切并用页面背景色做底部渐隐（列表行高稳定、扫读友好）。
 * 不包 SelectionContainer：保留行的点击/长按语义，完整渲染在详情回填页查看。
 */
@Composable
private fun SolveHistoryPreview(
    process: String,
    finalAnswer: String,
    modifier: Modifier = Modifier,
) {
    FadeCropBox(maxHeight = 108.dp, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (process.isNotBlank()) {
                MarkdownBlock(
                    content = process,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (finalAnswer.isNotBlank()) {
                MarkdownBlock(
                    content = finalAnswer,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 限高渐隐容器：内容超高部分裁切并把末尾"化"进页面背景色，
 * 硬切感弱化、列表行高稳定（题干主体与答案预览共用）。
 */
@Composable
private fun FadeCropBox(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val fadeColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                // 底部渐隐：把被裁掉的末尾"化"进背景，硬切感弱化
                val fadePx = 28.dp.toPx()
                if (size.height > fadePx) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, fadeColor),
                            startY = size.height - fadePx,
                            endY = size.height,
                        )
                    )
                }
            },
    ) {
        content()
    }
}
