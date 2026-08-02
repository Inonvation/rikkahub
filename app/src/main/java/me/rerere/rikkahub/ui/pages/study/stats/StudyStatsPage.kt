package me.rerere.rikkahub.ui.pages.study.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Bulb
import me.rerere.hugeicons.stroke.Note01
import me.rerere.rikkahub.data.model.StudySubject
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun StudyStatsPage(vm: StudyStatsVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("学习统计") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (state.isLoading) {
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
                    TypeCountCard(
                        counts = state.totalCounts,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    SubjectDistributionCard(
                        items = state.bySubject,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    TrendCard(
                        days = state.byDay,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeCountCard(counts: List<TypeCount>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("内容总量", style = MaterialTheme.typography.titleMedium)

            val icons = listOf(HugeIcons.BookOpen01, HugeIcons.Note01, HugeIcons.Alert01, HugeIcons.Bulb)
            counts.zip(icons).chunked(2).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowCells.forEach { (count, icon) ->
                        TypeCountCell(
                            count = count,
                            icon = icon,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCells.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeCountCell(count: TypeCount, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = count.count.toString(),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = count.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubjectDistributionCard(items: List<SubjectCount>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("学科分布", style = MaterialTheme.typography.titleMedium)

            if (items.isEmpty()) {
                Text(
                    text = "暂无学科内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxTotal = items.maxOf { it.total }.coerceAtLeast(1)
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = StudySubject.name(item.subjectCode),
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        BarTrack(
                            fraction = item.total.toFloat() / maxTotal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "共 ${item.total} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(days: List<DayCount>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("近 7 天趋势", style = MaterialTheme.typography.titleMedium)

            val maxTotal = days.maxOf { it.total }.coerceAtLeast(1)
            val today = LocalDate.now()
            days.forEach { day ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (day.date == today) {
                            "今天"
                        } else {
                            day.date.format(DateTimeFormatter.ofPattern("MM/dd"))
                        },
                        modifier = Modifier.width(44.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BarTrack(
                        fraction = day.total.toFloat() / maxTotal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = day.total.toString(),
                        modifier = Modifier.width(24.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 横向占比条：轨道底色 + 主色填充条 */
@Composable
private fun BarTrack(fraction: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
