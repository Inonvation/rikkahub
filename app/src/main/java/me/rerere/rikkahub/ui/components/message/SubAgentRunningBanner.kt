package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import me.rerere.rikkahub.ui.context.LocalNavController
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 顶部子代理横幅：展示本会话的子代理任务概览，支持折叠/展开。
 *
 * - 顶部一行始终可见：箭头图标 + 「n 个代理运行中 · x/y 已完成」，整行可点击切换展开/收起。
 * - 展开后（AnimatedVisibility）显示任务卡片列表：**全部任务**（含已完成），
 *   进行中的显示实时状态，已完成的显示完成态（✓）。全部完成后整个横幅才自动隐藏。
 * - 每张卡片可点击进详情页。
 *
 * 设计（用户需求）：子代理状态从聊天气泡移到这里，像 todolist 一样直观可见。
 * 样式对齐 TodolistBanner（Card + 圆角 + 主色 tint）。
 */
@Composable
fun SubAgentRunningBanner(
    tasks: List<SubAgentTask>,
    activeCount: Int,
    completedCount: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 悬浮后不受外层布局约束：展开任务列表限高（约 40% 屏高），避免盖满整个聊天区
    val maxBannerHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.4f).dp }

    AnimatedVisibility(visible = visible, modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .heightIn(max = maxBannerHeight),
            colors = CardDefaults.cardColors(
                // 悬浮在消息列表上方：用不透明背景，避免底下聊天内容透出来（改悬浮前在布局流里有底色垫着）
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column {
                // 折叠/展开头：整行可点击，显示汇总文案
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.subagent_banner_title, activeCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.subagent_banner_summary, activeCount, completedCount, tasks.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 展开区：任务卡片列表（全部任务，含已完成——完成的不隐藏）。
                // 内部可滚动：任务多时（超 40% 屏高）列表内部滚动，不撑破悬浮层。
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tasks.forEach { task ->
                            val def = SubAgentCatalog.byId(task.agentId)
                            val agentName = def?.name ?: task.agentId

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navController.navigate(
                                            Screen.SubAgentDetail(task.taskId, task.parentConversationId.toString())
                                        )
                                    },
                                colors = CardDefaults.cardColors(
                                    // 悬浮层上不透明，避免透出底下聊天内容
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = statusIcon(task.status),
                                        contentDescription = null,
                                        tint = statusColor(task.status),
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = agentName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = statusText(task),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            navController.navigate(
                                                Screen.SubAgentDetail(task.taskId, task.parentConversationId.toString())
                                            )
                                        },
                                        modifier = Modifier.size(22.dp),
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight01,
                                            contentDescription = stringResource(R.string.subagent_panel_open_detail),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 状态文本：排队/执行中（含最近步骤）+ 耗时 */
private fun statusText(task: SubAgentTask): String {
    val base = when (task.status) {
        SubAgentStatus.QUEUED -> "排队中"
        SubAgentStatus.RUNNING -> task.steps.lastOrNull()?.message ?: "执行中"
        SubAgentStatus.SUCCEEDED -> "完成"
        SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> "失败"
        SubAgentStatus.CANCELLED -> "已取消"
    }
    val elapsed = formatElapsed(task.startedAt, task.finishedAt)
    return if (elapsed.isNotBlank()) "$base · $elapsed" else base
}

private fun statusIcon(status: SubAgentStatus): ImageVector = when (status) {
    SubAgentStatus.SUCCEEDED -> HugeIcons.Tick01
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> HugeIcons.Alert01
    SubAgentStatus.CANCELLED -> HugeIcons.Cancel01
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> HugeIcons.Clock02
}

@Composable
private fun statusColor(status: SubAgentStatus): Color = when (status) {
    SubAgentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> MaterialTheme.colorScheme.error
    SubAgentStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatElapsed(start: Instant?, end: Instant? = null): String {
    if (start == null) return ""
    val millis = (end ?: Clock.System.now()).toEpochMilliseconds() - start.toEpochMilliseconds()
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
