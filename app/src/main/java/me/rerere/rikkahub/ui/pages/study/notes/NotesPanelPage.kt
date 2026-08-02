package me.rerere.rikkahub.ui.pages.study.notes

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Slider
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
import me.rerere.rikkahub.data.db.entity.NoteEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.study.DeleteSelectionConfirmDialog
import me.rerere.rikkahub.ui.pages.study.PlainTitle
import me.rerere.rikkahub.ui.pages.study.StudyDetailActions
import me.rerere.rikkahub.ui.pages.study.StudyMarkdownBlock
import me.rerere.rikkahub.ui.pages.study.StudySelectionEntryIcon
import me.rerere.rikkahub.ui.pages.study.StudySelectionState
import me.rerere.rikkahub.ui.pages.study.StudySelectionTopBar
import me.rerere.rikkahub.ui.pages.study.SubjectTabBar
import me.rerere.rikkahub.ui.pages.study.buildNoteMarkdown
import me.rerere.rikkahub.ui.pages.study.parseTags
import me.rerere.rikkahub.ui.pages.study.rememberStudySelectionState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NoteSettings(val cooldownSeconds: Int = 3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesPanelPage() {
    val vm = koinViewModel<NotesPanelVM>()
    val notes by vm.items.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()
    val selectedSubject by vm.selectedSubject.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showArchived by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(NoteSettings()) }
    val selection = rememberStudySelectionState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val grouped = remember(notes) {
        runCatching {
            notes.groupBy { note -> formatDate(note.createdAt) }
        }.getOrDefault(emptyMap())
    }

    Scaffold(
        topBar = {
            if (selection.enabled) {
                StudySelectionTopBar(
                    selectedCount = selection.count,
                    scrollBehavior = scrollBehavior,
                    onSelectAll = { selection.selectAll(notes.map { it.id }) },
                    onClear = { selection.clear() },
                    onDelete = { showDeleteConfirm = true },
                    onExit = { selection.exit() },
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("笔记")
                            Text("${notes.size} 条笔记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (notes.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("还没有笔记", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(8.dp))
                    Text("在对话中 AI 会自动保存有价值的内容", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    grouped.entries.forEach { (dateLabel, list) ->
                        item(key = "divider_$dateLabel") { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f)); Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp)); HorizontalDivider(Modifier.weight(1f)) } }
                        items(list, key = { it.id }) { note -> CompactNoteCard(note, settings, selection, onDelete = { vm.delete(note.id) }, onArchive = { vm.archive(note.id) }) }
                    }
                }
            }
        }
    }
    if (showArchived) ArchivedNotesDialog(vm, onDismiss = { showArchived = false })
    if (showSettings) NoteSettingsDialog(settings, onUpdate = { settings = it; showSettings = false }, onDismiss = { showSettings = false }, onShowArchived = { showSettings = false; showArchived = true })
    if (showDeleteConfirm) DeleteSelectionConfirmDialog(count = selection.count, onConfirm = {
        vm.deleteByIds(selection.selectedIds.toList())
        selection.exit()
        showDeleteConfirm = false
    }, onDismiss = { showDeleteConfirm = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactNoteCard(note: NoteEntity, settings: NoteSettings, selection: StudySelectionState, onDelete: () -> Unit, onArchive: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(targetValue = if (showDetail) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface, animationSpec = tween(200), label = "bg")
    val tags = remember(note.tags) { parseTags(note.tags) }
    val selected = note.id in selection.selectedIds
    Card(Modifier.fillMaxWidth().clickable {
        if (selection.enabled) selection.toggle(note.id) else showDetail = true
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
                PlainTitle(note.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
                Row { Text(note.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); if (tags.isNotEmpty()) Text(" · ${tags.take(2).joinToString(" ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(note.category, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
                Text(formatTime(note.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDetail) NoteDetailDialog(note, tags, settings.cooldownSeconds, onDismiss = { showDetail = false }, onDelete = onDelete, onArchive = onArchive)
}

@Composable
private fun NoteDetailDialog(note: NoteEntity, tags: List<String>, cooldown: Int, onDismiss: () -> Unit, onDelete: () -> Unit, onArchive: () -> Unit) {
    var canDismiss by remember { mutableStateOf(cooldown == 0) }; var remainingSec by remember { mutableIntStateOf(cooldown) }
    LaunchedEffect(Unit) { if (cooldown > 0) { while (remainingSec > 0) { delay(1000); remainingSec-- }; canDismiss = true } }
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PlainTitle(note.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                StudyDetailActions(
                    title = note.title,
                    content = buildNoteMarkdown(note),
                    sourceConversationId = note.sourceConversationId,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                StudyMarkdownBlock(note.content)
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
private fun NoteSettingsDialog(settings: NoteSettings, onUpdate: (NoteSettings) -> Unit, onDismiss: () -> Unit, onShowArchived: () -> Unit) {
    var cooldown by remember { mutableIntStateOf(settings.cooldownSeconds) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("笔记设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column { Text("详情弹窗冷却时间: ${cooldown}秒", style = MaterialTheme.typography.bodyMedium); Text("设为0可立即关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Slider(value = cooldown.toFloat(), onValueChange = { cooldown = it.toInt() }, valueRange = 0f..10f, steps = 9) }
                TextButton(onClick = onShowArchived, modifier = Modifier.fillMaxWidth()) { Text("查看已归档笔记") }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(settings.copy(cooldownSeconds = cooldown)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ArchivedNotesDialog(vm: NotesPanelVM, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope(); var archived by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    LaunchedEffect(Unit) { archived = vm.getArchived() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("已归档笔记") },
        text = { if (archived.isEmpty()) Text("暂无归档笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else LazyColumn { items(archived, key = { it.id }) { n -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(n.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { scope.launch { vm.restore(n.id); archived = vm.getArchived() } }) { Text("还原") } } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, dismissButton = null)
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun formatTime(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)
private fun formatDate(epochMs: Long): String {
    return runCatching {
        val local = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val date = local.toLocalDate()
        when { date == today -> "今天"; date == today.minusDays(1) -> "昨天"; date == today.minusDays(2) -> "前天"; else -> local.format(DateTimeFormatter.ofPattern("MM月dd日")) }
    }.getOrDefault(DateTimeFormatter.ofPattern("MM月dd日").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())))
}