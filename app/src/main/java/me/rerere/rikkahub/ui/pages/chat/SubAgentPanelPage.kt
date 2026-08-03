package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 子代理面板：按会话列出所有子代理任务的实时状态。
 * 数据源 [SubAgentRunner.tasksFlow]（进程内 StateFlow），按 parentConversationId 过滤。
 * 高配：
 * - 节流（sample 200ms + distinctUntilChangedBy 指纹），避免 15ms 级流式更新导致高频重组
 * - 卡片点击进入全屏详情页（[SubAgentDetailPage]，聊天式布局 + markdown 渲染）
 * - 运行中的异步任务可取消
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun SubAgentPanelPage(conversationId: String) {
    val runner: SubAgentRunner = koinInject()

    // 派生：按会话过滤 + 节流。指纹只含结构性变化（状态/步骤数/工具数）——
    // 卡片显示最近步骤/结果摘要，不依赖流式文本长度；去掉 streamText/reasoning 依赖，
    // 子代理流式输出不再高频重组整个面板。
    val tasks by remember(conversationId) {
        runner.tasksFlow
            .map { map ->
                map.values
                    .filter { it.parentConversationId.toString() == conversationId }
                    .sortedByDescending { it.createdAt }
            }
            .sample(200)
            .distinctUntilChangedBy { list ->
                list.map { t ->
                    "${t.taskId}|${t.status}|${t.steps.size}|${t.toolCalls.size}"
                }
            }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.subagent_panel_title))
                        Text(
                            stringResource(
                                R.string.subagent_panel_subtitle,
                                tasks.count { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.QUEUED },
                                tasks.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.subagent_panel_empty), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(8.dp))
                Text(
                    stringResource(R.string.subagent_panel_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(tasks, key = { it.taskId }) { task ->
                    SubAgentPanelCard(runner = runner, task = task)
                }
            }
        }
    }
}

@Composable
private fun SubAgentPanelCard(runner: SubAgentRunner, task: SubAgentTask) {
    val navController = LocalNavController.current
    val def = SubAgentCatalog.byId(task.agentId)
    val agentName = def?.name ?: task.agentId
    val status = task.status
    val running = status == SubAgentStatus.QUEUED || status == SubAgentStatus.RUNNING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate(
                    Screen.SubAgentDetail(task.taskId, task.parentConversationId.toString())
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行：状态图标 + 代理名 + 任务摘要 + 时长 + 取消
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = statusIcon(status),
                    contentDescription = null,
                    tint = statusColor(status),
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.shimmer(isLoading = running),
                    )
                    if (task.request.isNotBlank()) {
                        Text(
                            text = task.request,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 运行中的异步任务可取消
                if (running && runner.isCancellable(task.taskId)) {
                    IconButton(onClick = { runner.cancel(task.taskId) }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.subagent_panel_cancel),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = formatElapsed(task.startedAt, task.finishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }

            // 摘要行：进行中显示最近步骤，终态显示结果摘要
            val summary = when {
                status == SubAgentStatus.SUCCEEDED -> task.resultSummary ?: task.streamText
                status == SubAgentStatus.RUNNING || status == SubAgentStatus.QUEUED ->
                    task.steps.lastOrNull()?.message ?: task.streamText
                status == SubAgentStatus.FAILED || status == SubAgentStatus.TIMEOUT ||
                    status == SubAgentStatus.TOKEN_LIMIT -> task.error
                else -> null
            }
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusIcon(status: SubAgentStatus) = when (status) {
    SubAgentStatus.SUCCEEDED -> HugeIcons.Tick01
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> HugeIcons.Alert01
    SubAgentStatus.CANCELLED -> HugeIcons.Cancel01
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> HugeIcons.Clock02
}

@Composable
private fun statusColor(status: SubAgentStatus) = when (status) {
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
