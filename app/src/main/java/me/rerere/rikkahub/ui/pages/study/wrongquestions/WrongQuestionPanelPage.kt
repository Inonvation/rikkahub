package me.rerere.rikkahub.ui.pages.study.wrongquestions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Circle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import androidx.compose.material3.Slider
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.data.model.StudySubject
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.study.DeleteSelectionConfirmDialog
import me.rerere.rikkahub.ui.pages.study.PlainTitle
import me.rerere.rikkahub.ui.pages.study.StudyDetailActions
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
import me.rerere.rikkahub.ui.pages.study.StudySelectionEntryIcon
import me.rerere.rikkahub.ui.pages.study.StudySelectionState
import me.rerere.rikkahub.ui.pages.study.StudySelectionTopBar
import me.rerere.rikkahub.ui.pages.study.SubjectTabBar
import me.rerere.rikkahub.ui.pages.study.buildWrongQuestionMarkdown
import me.rerere.rikkahub.ui.pages.study.extractPlainText
import me.rerere.rikkahub.ui.pages.study.parseKnowledgePoints
import me.rerere.rikkahub.ui.pages.study.parseTags
import me.rerere.rikkahub.ui.pages.study.rememberStudySelectionState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class QuestionSettings(val cooldownSeconds: Int = 3)

private fun WrongQuestionEntity.titleOrFallback(): String =
    title.ifBlank { extractPlainText(question).ifBlank { question.take(30) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrongQuestionPanelPage() {
    val vm = koinViewModel<WrongQuestionPanelVM>()
    val questions by vm.items.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val selectedSubject by vm.selectedSubject.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showArchived by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(QuestionSettings()) }
    val selection = rememberStudySelectionState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val currentQuestions = questions

    Scaffold(
        topBar = {
            if (selection.enabled) {
                StudySelectionTopBar(
                    selectedCount = selection.count,
                    scrollBehavior = scrollBehavior,
                    onSelectAll = { selection.selectAll(currentQuestions.map { it.id }) },
                    onClear = { selection.clear() },
                    onDelete = { showDeleteConfirm = true },
                    onExit = { selection.exit() },
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("错题本")
                            Text("${currentQuestions.size} 道错题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { BackButton() },
                    scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
                    actions = {
                        StudySelectionEntryIcon(onClick = { selection.enter() })
                        IconButton(onClick = { showSettings = true }) { Icon(HugeIcons.Settings03, "设置") }
                    })
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SubjectTabBar(subjects = subjects, selected = selectedSubject, onSelect = { selection.exit(); vm.select(it) })
            if (currentQuestions.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("还没有错题", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(8.dp))
                    Text("在对话中解题后 AI 会自动保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val grouped = currentQuestions.groupBy { formatDate(it.createdAt) }
                    grouped.entries.forEach { (dateLabel, list) ->
                        item(key = "divider_$dateLabel") { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f)); Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp)); HorizontalDivider(Modifier.weight(1f)) } }
                        items(list, key = { it.id }) { q -> CompactQuestionCard(q, settings, selection, onDelete = { vm.delete(q.id) }, onArchive = { vm.archive(q.id) }, onReview = { vm.updateReview(q) }) }
                    }
                }
            }
        }
    }
    if (showArchived) ArchivedQuestionsDialog(vm, onDismiss = { showArchived = false })
    if (showSettings) QuestionSettingsDialog(settings, onUpdate = { settings = it; showSettings = false }, onDismiss = { showSettings = false }, onShowArchived = { showSettings = false; showArchived = true })
    if (showDeleteConfirm) DeleteSelectionConfirmDialog(count = selection.count, onConfirm = {
        vm.deleteByIds(selection.selectedIds.toList())
        selection.exit()
        showDeleteConfirm = false
    }, onDismiss = { showDeleteConfirm = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactQuestionCard(q: WrongQuestionEntity, settings: QuestionSettings, selection: StudySelectionState, onDelete: () -> Unit, onArchive: () -> Unit, onReview: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(targetValue = if (showDetail) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface, animationSpec = tween(200), label = "bg")
    val knowledgePoints = remember(q.knowledgePoints) { parseKnowledgePoints(q.knowledgePoints) }
    val selected = q.id in selection.selectedIds
    Card(Modifier.fillMaxWidth().clickable {
        if (selection.enabled) selection.toggle(q.id) else { onReview(); showDetail = true }
    }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else bgColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (selection.enabled) {
                Icon(
                    if (selected) HugeIcons.CheckmarkCircle01 else HugeIcons.Circle,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                PlainTitle(q.titleOrFallback(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
                if (knowledgePoints.isNotEmpty()) Text(knowledgePoints.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(StudySubject.name(q.subject), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
                Text("复习${q.reviewCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(" · ${formatTime(q.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDetail) QuestionDetailDialog(q, knowledgePoints, settings.cooldownSeconds, onDismiss = { showDetail = false }, onDelete = onDelete, onArchive = onArchive, onReview = onReview)
}

@Composable
private fun QuestionDetailDialog(q: WrongQuestionEntity, knowledgePoints: List<String>, cooldown: Int, onDismiss: () -> Unit, onDelete: () -> Unit, onArchive: () -> Unit, onReview: () -> Unit) {
    var canDismiss by remember { mutableStateOf(cooldown == 0) }; var remainingSec by remember { mutableIntStateOf(cooldown) }
    LaunchedEffect(Unit) { if (cooldown > 0) { while (remainingSec > 0) { delay(1000); remainingSec-- }; canDismiss = true } }
    val tags = remember(q.tags) { parseTags(q.tags) }
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PlainTitle(q.titleOrFallback(), modifier = Modifier.weight(1f), maxLines = 2)
                StudyDetailActions(
                    title = q.titleOrFallback(),
                    content = buildWrongQuestionMarkdown(q),
                    sourceConversationId = q.sourceConversationId,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyMarkdownBlock(q.question)
                if (q.answer.isNotBlank()) { Text("答案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); StudyMarkdownBlock(q.answer) }
                if (q.solution.isNotBlank()) { Text("解析", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); StudyMarkdownBlock(q.solution) }
                if (knowledgePoints.isNotEmpty()) { Text("知识点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { knowledgePoints.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp)) } } }
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp)) } }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!canDismiss) Text("${remainingSec}秒后可操作", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.width(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = onDelete, enabled = canDismiss) { Text("删除", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onArchive, enabled = canDismiss) { Text("归档") }; TextButton(onClick = onDismiss, enabled = canDismiss) { Text("关闭") } }
            }
        },
        dismissButton = null,
    )
}

@Composable
private fun ArchivedQuestionsDialog(vm: WrongQuestionPanelVM, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope(); var archived by remember { mutableStateOf<List<WrongQuestionEntity>>(emptyList()) }
    LaunchedEffect(Unit) { archived = vm.getArchived() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("已归档错题") },
        text = { if (archived.isEmpty()) Text("暂无归档错题", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else LazyColumn { items(archived, key = { it.id }) { q -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(q.titleOrFallback(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { scope.launch { vm.restore(q.id); archived = vm.getArchived() } }) { Text("还原") } } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, dismissButton = null)
}

@Composable
private fun QuestionSettingsDialog(settings: QuestionSettings, onUpdate: (QuestionSettings) -> Unit, onDismiss: () -> Unit, onShowArchived: () -> Unit) {
    var cooldown by remember { mutableIntStateOf(settings.cooldownSeconds) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("错题本设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column { Text("详情弹窗冷却时间: ${cooldown}秒", style = MaterialTheme.typography.bodyMedium); Text("设为0可立即关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Slider(value = cooldown.toFloat(), onValueChange = { cooldown = it.toInt() }, valueRange = 0f..10f, steps = 9) }
                TextButton(onClick = onShowArchived, modifier = Modifier.fillMaxWidth()) { Text("查看已归档错题") }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(settings.copy(cooldownSeconds = cooldown)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val dateFormatter = DateTimeFormatter.ofPattern("MM月dd日")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun formatTime(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)
private fun formatDate(epochMs: Long): String { val local = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()); val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate(); val date = local.toLocalDate(); return when { date == today -> "今天"; date == today.minusDays(1) -> "昨天"; date == today.minusDays(2) -> "前天"; else -> local.format(dateFormatter) } }