package me.rerere.rikkahub.ui.pages.stats

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Zap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.DayModelUsage
import me.rerere.rikkahub.data.db.dao.ModelUsageEntry
import me.rerere.rikkahub.data.db.dao.AssistantUsageEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    HeatmapCard(
                        conversationsPerDay = stats.conversationsPerDay,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    StatsGrid(
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    TrendCard(
                        trendByModel = stats.trendByModel,
                        modelDisplayNames = stats.modelDisplayNames,
                        modelNameSnapshots = stats.modelNameSnapshots,
                        unknownName = stringResource(R.string.stats_page_unknown_model),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    val othersLabel = stringResource(R.string.stats_page_others)
                    UsageRankCard(
                        title = stringResource(R.string.stats_page_model_usage_title),
                        entries = mergeUnknownModels(
                            items = stats.modelUsage.map {
                                it.toRankItem(
                                    names = stats.modelDisplayNames,
                                    snapshotNames = stats.modelNameSnapshots,
                                    unknown = stringResource(R.string.stats_page_unknown_model),
                                )
                            },
                            knownIds = stats.modelDisplayNames.keys,
                            othersLabel = othersLabel,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    val othersLabel = stringResource(R.string.stats_page_others)
                    UsageRankCard(
                        title = stringResource(R.string.stats_page_assistant_usage_title),
                        entries = mergeUnknownModels(
                            items = stats.assistantUsage.map {
                                it.toRankItem(
                                    names = stats.assistantDisplayNames,
                                    unknown = stringResource(R.string.stats_page_unknown_assistant),
                                )
                            },
                            knownIds = stats.assistantDisplayNames.keys,
                            othersLabel = othersLabel,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(conversationsPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_page_heatmap_title), style = MaterialTheme.typography.titleMedium)

            ChatHeatmap(conversationsPerDay = conversationsPerDay)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_page_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val activeCounts = conversationsPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    // Month label row height
    val monthLabelHeight = 14.dp

    // Day-of-week labels (only Mon/Wed/Fri to save space, Sun=0)
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    // Shared scroll state so month labels + grid scroll together
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fixed left column: spacer for month label row + DOW labels
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Scrollable area: month labels + heatmap grid share one scroll state
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Month labels row
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Heatmap grid
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // future
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

@Composable
private fun StatsGrid(stats: AppStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.ChartColumn,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCount(stats.totalConversations.toLong()),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCount(stats.totalMessages.toLong()),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_input_tokens),
                value = formatTokens(stats.totalPromptTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatTokens(stats.totalCompletionTokens),
            )
        }
        if (stats.totalCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatTokens(stats.totalCachedTokens),
            )
        }
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatCount(stats.launchCount.toLong()),
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

// ---- 用量趋势 ----

private enum class TrendGranularity { DAY, WEEK, MONTH }

/** 趋势图单柱最多展示的模型分段数，其余合并为「其他」避免过杂 */
private const val MAX_VISIBLE_MODELS = 3

/** 未匹配到品牌的模型 fallback 色板（避开 DeepSeek 蓝与 Claude 黄） */
private val FALLBACK_MODEL_COLORS = listOf(
    Color(0xFF6366F1), // 靛
    Color(0xFF10B981), // 绿
    Color(0xFF8B5CF6), // 紫
    Color(0xFFEC4899), // 粉
    Color(0xFF06B6D4), // 青
    Color(0xFFF97316), // 橙
    Color(0xFFEF4444), // 红
    Color(0xFF14B8A6), // 青绿
)

/** 按模型显示名（小写包含关键词）分配品牌专属色 */
private fun brandColorFor(name: String): Color? {
    val n = name.lowercase()
    return when {
        "deepseek" in n -> Color(0xFF3B82F6) // DeepSeek 蓝
        "claude" in n || "anthropic" in n -> Color(0xFFF59E0B) // Claude 黄
        "gpt" in n || "openai" in n -> Color(0xFF10B981)
        "gemini" in n -> Color(0xFF8B5CF6)
        "glm" in n -> Color(0xFF06B6D4)
        "qwen" in n -> Color(0xFF0EA5E9)
        "kimi" in n || "moonshot" in n -> Color(0xFFEC4899)
        "llama" in n -> Color(0xFFF97316)
        "mistral" in n -> Color(0xFFEF4444)
        "doubao" in n || "volc" in n -> Color(0xFFFF6B6B)
        "hunyuan" in n -> Color(0xFF2563EB)
        "spark" in n -> Color(0xFF6366F1)
        "ernie" in n || "wenxin" in n -> Color(0xFF14B8A6)
        else -> null
    }
}

/** 单个时间桶（周/月）的聚合用量 */
private data class TrendBucket(
    val label: String,
    val start: LocalDate,
    val end: LocalDate,
    val messageCount: Int,
    val tokens: Long,
    val segments: List<TrendSegment>,
)

/** 时间桶内的单个模型分段（堆叠柱的一段）；「其他」段的 children 存放被合并的真实成员（可下钻） */
private data class TrendSegment(
    val modelId: String,
    val label: String,
    val tokens: Long,
    val children: List<TrendSegment> = emptyList(),
)

/** 使用率排行榜单行（已带显示名）；「其他」行的 children 存放被合并的真实成员（可下钻） */
private data class UsageRankItem(
    val id: String,
    val name: String,
    val count: Int,
    val tokens: Long,
    val children: List<UsageRankItem> = emptyList(),
)

/** 解析模型显示名：当前配置名 > 消息落库快照名 > 「未知模型」 */
private fun resolveModelName(
    modelId: String,
    names: Map<String, String>,
    snapshotNames: Map<String, String>,
    unknown: String,
): String = names[modelId] ?: snapshotNames[modelId] ?: unknown

private fun ModelUsageEntry.toRankItem(
    names: Map<String, String>,
    snapshotNames: Map<String, String>,
    unknown: String,
) = UsageRankItem(
    id = modelId,
    name = resolveModelName(modelId, names, snapshotNames, unknown),
    count = count,
    tokens = tokens,
)

private fun AssistantUsageEntry.toRankItem(names: Map<String, String>, unknown: String) =
    UsageRankItem(id = assistantId, name = names[assistantId] ?: unknown, count = count, tokens = tokens)

/** 把未匹配到当前配置的模型/助手合并为「其他」，成员放入 children 供下钻查看，避免历史遗留的未知项刷屏 */
private fun mergeUnknownModels(
    items: List<UsageRankItem>,
    knownIds: Set<String>,
    othersLabel: String,
): List<UsageRankItem> {
    val known = items.filter { it.id.isNotEmpty() && it.id in knownIds }
    val unknown = items.filterNot { it.id in knownIds }
    if (unknown.isEmpty()) return items
    return known + UsageRankItem(
        id = "",
        name = othersLabel,
        count = unknown.sumOf { it.count },
        tokens = unknown.sumOf { it.tokens },
        children = unknown,
    )
}

/**
 * 把按「日 × 模型」聚合的用量整理成连续的时间桶（含 0 值）。
 * 每个桶按 [modelOrder]（模型按总用量降序）生成分段，供堆叠柱状图使用。
 * 周粒度：最近 12 周（每周从周一开始）；月粒度：最近 6 个自然月。
 */
private fun buildTrendBuckets(
    trendByModel: List<DayModelUsage>,
    modelOrder: List<String>,
    topKnownIds: Set<String>,
    names: Map<String, String>,
    snapshotNames: Map<String, String>,
    unknown: String,
    othersLabel: String,
    granularity: TrendGranularity,
): List<TrendBucket> {
    val byDayAndModel = HashMap<LocalDate, MutableMap<String, Long>>()
    val byDayCount = HashMap<LocalDate, Int>()
    trendByModel.forEach { entry ->
        val date = runCatching { LocalDate.parse(entry.day) }.getOrNull() ?: return@forEach
        val modelTokens = byDayAndModel.getOrPut(date) { HashMap() }
        modelTokens[entry.modelId] = (modelTokens[entry.modelId] ?: 0L) + entry.tokens
        byDayCount[date] = (byDayCount[date] ?: 0) + entry.count
    }
    val today = LocalDate.now()

    fun aggregateBucket(start: LocalDate, end: LocalDate): Pair<Int, List<TrendSegment>> {
        val modelTokens = HashMap<String, Long>()
        var count = 0
        var d = start
        while (!d.isAfter(end)) {
            byDayAndModel[d]?.forEach { (modelId, tokens) ->
                modelTokens[modelId] = (modelTokens[modelId] ?: 0L) + tokens
            }
            count += byDayCount[d] ?: 0
            d = d.plusDays(1)
        }
        var segments = modelOrder.mapNotNull { modelId ->
            val tokens = modelTokens[modelId] ?: return@mapNotNull null
            if (tokens <= 0L) null else TrendSegment(
                modelId = modelId,
                label = resolveModelName(modelId, names, snapshotNames, unknown),
                tokens = tokens,
            )
        }
        // 仅保留「全局已知用量前 MAX_VISIBLE_MODELS」的模型为独立段，
        // 其余（未知模型、已知但用量靠后）一律合并为「其他」，保证柱子与图例严格一致；
        // 被合并的真实成员放入 children，用户可下钻查看每个模型的用量
        val othersSegments = segments.filterNot { it.modelId in topKnownIds }
        val othersTokens = othersSegments.sumOf { it.tokens }
        segments = segments.filter { it.modelId in topKnownIds }
        if (othersTokens > 0L) {
            segments = segments + TrendSegment(
                modelId = "",
                label = othersLabel,
                tokens = othersTokens,
                children = othersSegments,
            )
        }
        return count to segments
    }

    return when (granularity) {
        TrendGranularity.DAY -> {
            // 最近 14 天，每天一个桶
            (13 downTo 0).map { i ->
                val date = today.minusDays(i.toLong())
                val (count, segments) = aggregateBucket(date, date)
                TrendBucket(
                    label = "${date.monthValue}/${date.dayOfMonth}",
                    start = date,
                    end = date,
                    messageCount = count,
                    tokens = segments.sumOf { it.tokens },
                    segments = segments,
                )
            }
        }

        TrendGranularity.WEEK -> {
            val weekEnd = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(6)
            (0 until 12).map { i ->
                val end = weekEnd.minusWeeks((11 - i).toLong())
                val start = end.minusDays(6)
                val (count, segments) = aggregateBucket(start, end)
                TrendBucket(
                    label = "${start.monthValue}/${start.dayOfMonth}",
                    start = start,
                    end = end,
                    messageCount = count,
                    tokens = segments.sumOf { it.tokens },
                    segments = segments,
                )
            }
        }

        TrendGranularity.MONTH -> {
            // 最近 12 个自然月，label 用月份名（如「8月」/「Aug」）；
            // 跨年时次年 1 月只显示年份（如「2026」），避免和「8月」混淆
            val thisMonth = today.withDayOfMonth(1)
            (0 until 12).map { i ->
                val start = thisMonth.minusMonths((11 - i).toLong())
                val end = start.withDayOfMonth(start.lengthOfMonth())
                val (count, segments) = aggregateBucket(start, end)
                TrendBucket(
                    label = if (start.monthValue == 1) {
                        start.year.toString()
                    } else {
                        start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    },
                    start = start,
                    end = end,
                    messageCount = count,
                    tokens = segments.sumOf { it.tokens },
                    segments = segments,
                )
            }
        }
    }
}

private fun LocalDate.shortRange(): String = "${monthValue}/${dayOfMonth}"

@Composable
private fun TrendCard(
    trendByModel: List<DayModelUsage>,
    modelDisplayNames: Map<String, String>,
    modelNameSnapshots: Map<String, String>,
    unknownName: String,
    modifier: Modifier = Modifier,
) {
    var granularity by remember { mutableStateOf(TrendGranularity.WEEK) }
    // 模型按总用量降序排列，作为颜色分配顺序（同一模型跨桶颜色稳定一致）
    val modelOrder = remember(trendByModel) {
        trendByModel.groupBy { it.modelId }
            .mapValues { (_, v) -> v.sumOf { it.tokens } }
            .entries.sortedByDescending { it.value }
            .map { it.key }
    }
    val colorByModel = remember(modelOrder, modelDisplayNames) {
        modelOrder.mapIndexed { index, id ->
            val name = modelDisplayNames[id].orEmpty()
            id to (brandColorFor(name) ?: FALLBACK_MODEL_COLORS[index % FALLBACK_MODEL_COLORS.size])
        }.toMap()
    }
    val othersLabel = stringResource(R.string.stats_page_others)
    // 全局已知模型中用量前 MAX_VISIBLE_MODELS 个，柱子与图例都只显示它们 + 其他
    val topKnownIds = remember(modelOrder, modelDisplayNames) {
        modelOrder.filter { it in modelDisplayNames }.take(MAX_VISIBLE_MODELS).toSet()
    }
    val buckets = remember(trendByModel, modelOrder, topKnownIds, granularity, modelNameSnapshots) {
        buildTrendBuckets(
            trendByModel,
            modelOrder,
            topKnownIds,
            modelDisplayNames,
            modelNameSnapshots,
            unknownName,
            othersLabel,
            granularity,
        )
    }
    // 图例与柱子一致：只显示全局已知前 3 模型 + 其他，避免各桶并集导致模型过多
    val legendSegments = remember(topKnownIds, buckets, othersLabel, unknownName, modelDisplayNames, modelNameSnapshots) {
        val seenIds = buckets.flatMap { it.segments }.map { it.modelId }.toSet()
        // 「其他」段（modelId 为空串）只要任一桶存在，图例就展示对应的灰色块
        val hasOthers = buckets.any { bucket -> bucket.segments.any { it.modelId.isEmpty() } }
        buildList {
            topKnownIds.forEach { id ->
                if (id in seenIds) {
                    add(TrendSegment(id, resolveModelName(id, modelDisplayNames, modelNameSnapshots, unknownName), 0L))
                }
            }
            if (hasOthers) {
                add(TrendSegment("", othersLabel, 0L))
            }
        }
    }
    // 选中索引随粒度一起重置，避免旧粒度索引越界（12 周切 6 月时 index=11 崩溃）
    var selectedIndex by remember(granularity) { mutableStateOf(buckets.lastIndex.coerceAtLeast(0)) }
    val safeIndex = selectedIndex.coerceIn(0, buckets.lastIndex.coerceAtLeast(0))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stats_page_trend_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.stats_page_trend_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = granularity == TrendGranularity.DAY,
                        onClick = { granularity = TrendGranularity.DAY },
                        label = { Text(stringResource(R.string.stats_page_trend_day)) },
                    )
                    FilterChip(
                        selected = granularity == TrendGranularity.WEEK,
                        onClick = { granularity = TrendGranularity.WEEK },
                        label = { Text(stringResource(R.string.stats_page_trend_week)) },
                    )
                    FilterChip(
                        selected = granularity == TrendGranularity.MONTH,
                        onClick = { granularity = TrendGranularity.MONTH },
                        label = { Text(stringResource(R.string.stats_page_trend_month)) },
                    )
                }
            }

            if (buckets.all { it.tokens == 0L }) {
                Text(
                    text = stringResource(R.string.stats_page_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrendChart(
                    buckets = buckets,
                    colorByModel = colorByModel,
                    legendSegments = legendSegments,
                    selectedIndex = safeIndex,
                    onSelect = { selectedIndex = it },
                )
                val selected = buckets[safeIndex]
                Text(
                    text = stringResource(
                        R.string.stats_page_trend_range,
                        selected.start.shortRange(),
                        selected.end.shortRange(),
                    ) + " · " +
                        stringResource(R.string.stats_page_usage_count, selected.messageCount) + " · " +
                        stringResource(R.string.stats_page_usage_tokens, formatTokens(selected.tokens)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TrendBreakdown(
                    segments = selected.segments,
                    colorByModel = colorByModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(
    buckets: List<TrendBucket>,
    colorByModel: Map<String, Color>,
    legendSegments: List<TrendSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val maxTokens = buckets.maxOf { it.tokens }.coerceAtLeast(1)
    // 横轴标签间隔随桶数量自适应，避免过密挤在一起
    val labelInterval = when {
        buckets.size >= 16 -> 3
        buckets.size >= 10 -> 2
        else -> 1
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            buckets.forEachIndexed { index, bucket ->
                val totalFraction = (bucket.tokens.toFloat() / maxTokens).coerceIn(0.03f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = totalFraction,
                    animationSpec = tween(durationMillis = 400),
                    label = "trendBar",
                )
                val isSelected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(animatedFraction)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                                )
                            } else Modifier
                        )
                        .clickable { onSelect(index) },
                ) {
                    if (bucket.tokens > 0L) {
                        // 堆叠段：每个模型按 token 占比占一段高度，颜色区分
                        bucket.segments.forEach { segment ->
                            val weight = segment.tokens.toFloat() / bucket.tokens
                            if (weight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(weight)
                                        .background(
                                            colorByModel[segment.modelId]
                                                ?: MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            buckets.forEachIndexed { index, bucket ->
                Text(
                    text = if (index % labelInterval == 0 || index == buckets.lastIndex) bucket.label else "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }

        // 图例：全局前 3 模型 + 其他（由 TrendCard 计算传入）
        if (legendSegments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                legendSegments.forEach { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    colorByModel[segment.modelId]
                                        ?: MaterialTheme.colorScheme.surfaceVariant
                                ),
                        )
                        Text(
                            text = segment.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 选中时间桶的模型用量明细（颜色点 + 名称 + token）；「其他」段可点击展开真实成员 */
@Composable
private fun TrendBreakdown(segments: List<TrendSegment>, colorByModel: Map<String, Color>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            if (segment.children.isEmpty()) {
                TrendSegmentRow(segment = segment, colorByModel = colorByModel)
            } else {
                var expanded by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TrendSegmentRow(
                        segment = segment,
                        colorByModel = colorByModel,
                        onClick = { expanded = !expanded },
                        trailing = {
                            Icon(
                                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            modifier = Modifier.padding(start = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            segment.children.forEach { child ->
                                TrendSegmentRow(segment = child, colorByModel = colorByModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSegmentRow(
    segment: TrendSegment,
    colorByModel: Map<String, Color>,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onClick)
                } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    colorByModel[segment.modelId]
                        ?: MaterialTheme.colorScheme.surfaceVariant
                ),
        )
        Text(
            text = segment.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTokens(segment.tokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

// ---- 使用率排行榜（模型 / 助手复用） ----

private enum class UsageMetric { MESSAGE, TOKEN }

@Composable
private fun UsageRankCard(
    title: String,
    entries: List<UsageRankItem>,
    modifier: Modifier = Modifier,
) {
    var metric by remember { mutableStateOf(UsageMetric.MESSAGE) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = metric == UsageMetric.MESSAGE,
                        onClick = { metric = UsageMetric.MESSAGE },
                        label = { Text(stringResource(R.string.stats_page_metric_messages)) },
                    )
                    FilterChip(
                        selected = metric == UsageMetric.TOKEN,
                        onClick = { metric = UsageMetric.TOKEN },
                        label = { Text(stringResource(R.string.stats_page_metric_tokens)) },
                    )
                }
            }
            // 切换指标时排行内容交叉淡入淡出 + 尺寸平滑，避免整卡突变
            AnimatedContent(
                targetState = metric,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(120)))
                        .using(SizeTransform(clip = false))
                },
                label = "rankMetric",
            ) { m ->
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_page_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 按所选维度降序排序，进度条与该维度占比对齐
                    val sorted = when (m) {
                        UsageMetric.MESSAGE -> entries.sortedByDescending { it.count }
                        UsageMetric.TOKEN -> entries.sortedByDescending { it.tokens }
                    }
                    val total = when (m) {
                        UsageMetric.MESSAGE -> sorted.sumOf { it.count }.coerceAtLeast(1).toFloat()
                        UsageMetric.TOKEN -> sorted.sumOf { it.tokens }.coerceAtLeast(1).toFloat()
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 只展示前 10，避免模型/助手过多时卡片过长
                        sorted.take(10).forEach { item ->
                            val fraction = when (m) {
                                UsageMetric.MESSAGE -> item.count.toFloat() / total
                                UsageMetric.TOKEN -> item.tokens.toFloat() / total
                            }
                            UsageRankRow(
                                item = item,
                                fraction = fraction,
                                metric = m,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRankRow(item: UsageRankItem, fraction: Float, metric: UsageMetric) {
    if (item.children.isEmpty()) {
        UsageRankRowMain(item = item, fraction = fraction, metric = metric)
    } else {
        var expanded by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UsageRankRowMain(
                item = item,
                fraction = fraction,
                metric = metric,
                onClick = { expanded = !expanded },
                trailing = {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item.children.forEach { child ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Text(
                                text = child.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when (metric) {
                                    UsageMetric.MESSAGE ->
                                        stringResource(R.string.stats_page_usage_count, child.count)
                                    UsageMetric.TOKEN ->
                                        stringResource(R.string.stats_page_usage_tokens, formatTokens(child.tokens))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRankRowMain(
    item: UsageRankItem,
    fraction: Float,
    metric: UsageMetric,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onClick)
                } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (metric) {
                        UsageMetric.MESSAGE ->
                            stringResource(R.string.stats_page_usage_count, item.count)
                        UsageMetric.TOKEN ->
                            stringResource(R.string.stats_page_usage_tokens, formatTokens(item.tokens))
                    } + " · " + stringResource(R.string.stats_page_usage_pct, fraction * 100),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (trailing != null) {
                    Spacer(Modifier.width(4.dp))
                    trailing()
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}
