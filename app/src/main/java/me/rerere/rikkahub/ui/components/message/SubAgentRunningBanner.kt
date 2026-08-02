package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
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
 * 顶部子代理横幅：纵向堆叠展示本会话**进行中**（QUEUED/RUNNING）的子代理任务。
 * 位于消息列表上方，每个子代理一张卡片**独占一行**（宽度铺满），点击卡片进详情页。
 *
 * 设计（用户需求）：子代理状态从聊天气泡移到这里，像 todolist 一样直观可见。
 * 完成后任务移出横幅（只显示进行中）。样式对齐 TodolistBanner（Card + 圆角 + 主色 tint）。
 */
@Composable
fun SubAgentRunningBanner(
    tasks: List<SubAgentTask>,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current

    AnimatedVisibility(visible = tasks.isNotEmpty(), modifier = modifier) {
        // 标题 + 纵向卡片列表（每张卡片独占一行）
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.subagent_banner_title, tasks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
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
                        // 查看详情入口：点击进详情页（与整卡点击一致）。
                        // 不做取消功能，避免用户误关子代理任务。
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

/** 状态文本：排队/执行中（含最近步骤）+ 耗时 */
private fun statusText(task: SubAgentTask): String {
    val base = when (task.status) {
        SubAgentStatus.QUEUED -> "排队中"
        SubAgentStatus.RUNNING -> task.steps.lastOrNull()?.message ?: "执行中"
        SubAgentStatus.SUCCEEDED -> "完成"
        SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT -> "失败"
        SubAgentStatus.CANCELLED -> "已取消"
    }
    val elapsed = formatElapsed(task.startedAt, task.finishedAt)
    return if (elapsed.isNotBlank()) "$base · $elapsed" else base
}

private fun statusIcon(status: SubAgentStatus): ImageVector = when (status) {
    SubAgentStatus.SUCCEEDED -> HugeIcons.Tick01
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT -> HugeIcons.Alert01
    SubAgentStatus.CANCELLED -> HugeIcons.Cancel01
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> HugeIcons.Clock02
}

@Composable
private fun statusColor(status: SubAgentStatus): Color = when (status) {
    SubAgentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT -> MaterialTheme.colorScheme.error
    SubAgentStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatElapsed(start: Instant?, end: Instant? = null): String {
    if (start == null) return ""
    val millis = (end ?: Clock.System.now()).toEpochMilliseconds() - start.toEpochMilliseconds()
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
