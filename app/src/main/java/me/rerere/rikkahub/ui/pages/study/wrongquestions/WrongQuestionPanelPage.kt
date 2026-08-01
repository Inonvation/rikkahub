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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import me.rerere.hugeicons.stroke.ArrowUpRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import androidx.compose.material3.Slider
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.study.parseKnowledgePoints
import me.rerere.rikkahub.ui.pages.study.parseTags
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun cleanLatex(s: String) = s
    .replace(Regex("\\$\\$([\\s\\S]*?)\\$\\$")) { it.groupValues[1].trim() }
    .replace(Regex("\\$([^$]*?)\\$")) { it.groupValues[1].trim() }
    .replace(Regex("\\\\[a-zA-Z]+"), "")
    .replace("{", "").replace("}", "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(80)

data class QuestionSettings(val cooldownSeconds: Int = 3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrongQuestionPanelPage() {
    val vm = koinViewModel<WrongQuestionPanelVM>()
    val mathQuestions by vm.mathQuestions.collectAsStateWithLifecycle()
    val mechanicsQuestions by vm.mechanicsQuestions.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showArchived by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(QuestionSettings()) }
    val allQuestions = listOf(mathQuestions, mechanicsQuestions)
    val currentQuestions = allQuestions[selectedTab]
    val tabs = listOf("数学", "机械原理")

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("错题本")
                        Text("${currentQuestions.size} 道错题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
                actions = { IconButton(onClick = { showSettings = true }) { Icon(HugeIcons.Settings03, "设置") } })
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) { tabs.forEachIndexed { i, t -> Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(t) }) } }
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
                        items(list, key = { it.id }) { q -> CompactQuestionCard(q, settings, onDelete = { vm.delete(q.id) }, onArchive = { vm.archive(q.id) }, onReview = { vm.updateReview(q) }) }
                    }
                }
            }
        }
    }
    if (showArchived) ArchivedQuestionsDialog(vm, onDismiss = { showArchived = false })
    if (showSettings) QuestionSettingsDialog(settings, onUpdate = { settings = it; showSettings = false }, onDismiss = { showSettings = false }, onShowArchived = { showSettings = false; showArchived = true })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactQuestionCard(q: WrongQuestionEntity, settings: QuestionSettings, onDelete: () -> Unit, onArchive: () -> Unit, onReview: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(targetValue = if (showDetail) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface, animationSpec = tween(200), label = "bg")
    val knowledgePoints = remember(q.knowledgePoints) { parseKnowledgePoints(q.knowledgePoints) }
    Card(Modifier.fillMaxWidth().clickable { onReview(); showDetail = true }, colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cleanLatex(q.question), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (knowledgePoints.isNotEmpty()) Text(knowledgePoints.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(if (q.subject == "math") "数学" else "机原", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
    val navController = LocalNavController.current
    var canDismiss by remember { mutableStateOf(cooldown == 0) }; var remainingSec by remember { mutableIntStateOf(cooldown) }
    LaunchedEffect(Unit) { if (cooldown > 0) { while (remainingSec > 0) { delay(1000); remainingSec-- }; canDismiss = true } }
    val tags = remember(q.tags) { parseTags(q.tags) }
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(cleanLatex(q.question).take(40), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (q.sourceConversationId.isNotBlank()) IconButton(onClick = { runCatching { navigateToChatPage(navController, Uuid.parse(q.sourceConversationId)) } }) { Icon(HugeIcons.ArrowUpRight01, "跳转对话", tint = MaterialTheme.colorScheme.primary) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownBlock(q.question)
                if (q.answer.isNotBlank()) { Text("答案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); MarkdownBlock(q.answer) }
                if (q.solution.isNotBlank()) { Text("解析", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); MarkdownBlock(q.solution) }
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
        text = { if (archived.isEmpty()) Text("暂无归档错题", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else LazyColumn { items(archived, key = { it.id }) { q -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(q.question.take(40), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { scope.launch { vm.restore(q.id); archived = vm.getArchived() } }) { Text("还原") } } } } },
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