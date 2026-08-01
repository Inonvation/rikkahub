package me.rerere.rikkahub.ui.pages.study.vocabulary

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUpRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.study.ExampleItem
import me.rerere.rikkahub.ui.pages.study.TranslationItem
import me.rerere.rikkahub.ui.pages.study.parseExamples
import me.rerere.rikkahub.ui.pages.study.parseTags
import me.rerere.rikkahub.ui.pages.study.parseTranslations
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyPanelPage() {
    val vm = koinViewModel<VocabularyPanelVM>()
    val wordGroups by vm.wordGroups.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showSettings by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val totalWords = wordGroups.sumOf { it.words.size }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("生词面板") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
                actions = {
                    Text("${totalWords}词", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { showSettings = true }) { Icon(HugeIcons.Settings03, "设置") }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(value = searchQuery, onValueChange = { vm.setSearchQuery(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("搜索单词...") }, leadingIcon = { Icon(HugeIcons.Search01, null) }, singleLine = true)
            if (wordGroups.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("还没有生词", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("在英语导师对话中查询单词后会自动保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    wordGroups.forEach { group ->
                        item(key = "divider_${group.timestamp}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                HorizontalDivider(Modifier.weight(1f))
                                Text(group.dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                                HorizontalDivider(Modifier.weight(1f))
                            }
                        }
                        items(group.words, key = { it.id }) { word ->
                            CompactWordCard(word, settings, onDelete = { vm.delete(word.id) }, onArchive = { vm.archive(word.id) }, onReview = { vm.updateReview(word) })
                        }
                    }
                }
            }
        }
    }
    if (showSettings) VocabularySettingsDialog(settings, onUpdate = { vm.updateSettings(it) }, onDismiss = { showSettings = false }, onShowArchived = { showSettings = false; showArchived = true })
    if (showArchived) ArchivedWordsDialog(vm, onDismiss = { showArchived = false })
}

@Composable
private fun CompactWordCard(word: VocabularyEntity, settings: VocabularySettings, onDelete: () -> Unit, onArchive: () -> Unit, onReview: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(targetValue = if (showDetail) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface, animationSpec = tween(200), label = "bg")
    val firstDef = remember(word.translations, settings.showDefinitionOnCard) {
        if (settings.showDefinitionOnCard) parseTranslations(word.translations).firstOrNull()?.definition ?: "" else ""
    }
    Card(Modifier.fillMaxWidth().clickable { onReview(); showDetail = true }, colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (word.pronunciation.isNotBlank()) Text(" /${word.pronunciation}/", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
                if (firstDef.isNotBlank()) Text(firstDef, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("英语", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
                Text("复习${word.reviewCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(" · ${VocabularyPanelVM.formatTime(word.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDetail) WordDetailDialog(word, settings.cooldownSeconds, onDismiss = { showDetail = false }, onDelete = onDelete, onArchive = onArchive, onReview = onReview)
}

@Composable
private fun WordDetailDialog(word: VocabularyEntity, cooldownSeconds: Int, onDismiss: () -> Unit, onDelete: () -> Unit, onArchive: () -> Unit, onReview: () -> Unit) {
    val navController = LocalNavController.current
    var canDismiss by remember { mutableStateOf(cooldownSeconds == 0) }
    var remainingSec by remember { mutableIntStateOf(cooldownSeconds) }
    LaunchedEffect(Unit) { if (cooldownSeconds > 0) { while (remainingSec > 0) { delay(1000); remainingSec-- }; canDismiss = true } }
    val translations = remember(word.translations) { parseTranslations(word.translations) }
    val examples = remember(word.examples) { parseExamples(word.examples) }
    val tags = remember(word.tags) { parseTags(word.tags) }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.titleLarge)
                    if (word.pronunciation.isNotBlank()) Text("/${word.pronunciation}/", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (word.sourceConversationId.isNotBlank()) {
                    IconButton(onClick = { runCatching { navigateToChatPage(navController, Uuid.parse(word.sourceConversationId)) } }) {
                        Icon(HugeIcons.ArrowUpRight01, "跳转对话", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (translations.isNotEmpty()) {
                    Text("释义", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    translations.forEachIndexed { i, t ->
                        Row {
                            Text(t.pos, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                            Text(t.definition, style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                if (examples.isNotEmpty()) {
                    Text("例句", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    examples.take(2).forEach { ex ->
                        Text(highlightWord(ex.en, word.word), style = MaterialTheme.typography.bodySmall)
                        Text(ex.zh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                    }
                }
                if (word.mnemonic.isNotBlank()) { Text("助记", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(word.mnemonic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                if (tags.isNotEmpty()) { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp)) } } }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!canDismiss) Text("${remainingSec}秒后可操作", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.width(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDelete, enabled = canDismiss) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onArchive, enabled = canDismiss) { Text("归档") }
                    TextButton(onClick = onDismiss, enabled = canDismiss) { Text("关闭") }
                }
            }
        },
        dismissButton = null,
    )
}

@Composable
private fun ArchivedWordsDialog(vm: VocabularyPanelVM, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf<List<VocabularyEntity>>(emptyList()) }
    LaunchedEffect(Unit) { archived = vm.getArchived() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已归档生词") },
        text = {
            if (archived.isEmpty()) Text("暂无归档生词", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn { items(archived, key = { it.id }) { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(w.word, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { scope.launch { vm.restore(w.id); archived = vm.getArchived() } }) { Text("还原") }
                }
            } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = null,
    )
}

@Composable
private fun VocabularySettingsDialog(settings: VocabularySettings, onUpdate: (VocabularySettings) -> Unit, onDismiss: () -> Unit, onShowArchived: () -> Unit) {
    var cooldown by remember { mutableIntStateOf(settings.cooldownSeconds) }
    var sortBy by remember { mutableStateOf(settings.sortBy) }
    var showDef by remember { mutableStateOf(settings.showDefinitionOnCard) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生词面板设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("详情弹窗冷却时间: ${cooldown}秒", style = MaterialTheme.typography.bodyMedium)
                    Text("设为0可立即关闭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = cooldown.toFloat(), onValueChange = { cooldown = it.toInt() }, valueRange = 0f..10f, steps = 9)
                }
                Column {
                    Text("排序方式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("time" to "时间", "alphabetical" to "字母", "reviewCount" to "复习次数").forEach { (v, l) ->
                            TextButton(onClick = { sortBy = v }, modifier = Modifier.background(if (sortBy == v) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)) { Text(l, color = if (sortBy == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("卡片显示主要释义", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = showDef, onCheckedChange = { showDef = it })
                }
                TextButton(onClick = onShowArchived, modifier = Modifier.fillMaxWidth()) { Text("查看已归档生词") }
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(settings.copy(cooldownSeconds = cooldown, sortBy = sortBy, showDefinitionOnCard = showDef)); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun highlightWord(sentence: String, word: String) = buildAnnotatedString {
    val escaped = Regex.escape(word)
    // Try word boundary first, fall back to case-insensitive contains
    val pattern = Regex("\\b$escaped\\b|$escaped(?!\\w)", RegexOption.IGNORE_CASE)
    var last = 0
    pattern.findAll(sentence).forEach { m ->
        append(sentence.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFE65100))) { append(m.value) }
        last = m.range.last + 1
    }
    if (last == 0) {
        // Fallback: match case-insensitive without word boundaries
        val simple = Regex(escaped, RegexOption.IGNORE_CASE)
        simple.findAll(sentence).forEach { m ->
            append(sentence.substring(last, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFE65100))) { append(m.value) }
            last = m.range.last + 1
        }
    }
    if (last < sentence.length) append(sentence.substring(last))
}