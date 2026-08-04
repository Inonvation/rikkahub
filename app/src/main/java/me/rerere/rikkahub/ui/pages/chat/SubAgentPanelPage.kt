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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Refresh01
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
    val scope = rememberCoroutineScope()
    val def = SubAgentCatalog.byId(task.agentId)
    val agentName = def?.name ?: task.agentId
    val status = task.status
    val running = status == SubAgentStatus.QUEUED || status == SubAgentStatus.RUNNING

    // 运行中：每秒刷新"已用时长"（终态耗时固定，无需 tick）
    var now by remember(task.taskId) { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(task.taskId, status) {
        if (running) {
            while (true) {
                delay(1000)
                now = Clock.System.now()
            }
        }
    }

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
            // 标题行：状态图标 + 代理名/请求 + 状态标签
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
                StatusBadge(status = status)
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
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 底部行：时间信息 + 操作按钮（运行中可取消、终态可重试）+ 查看箭头
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Clock02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = timeLabel(task = task, running = running, now = now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                when {
                    running && runner.isCancellable(task.taskId) -> FilledTonalIconButton(
                        onClick = { runner.cancel(task.taskId, notifyParent = true) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.subagent_panel_cancel),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    canRerun(status) -> FilledTonalIconButton(
                        onClick = {
                            scope.launch { rerunAndNavigate(runner, navController, task.taskId) }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.subagent_detail_rerun),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 状态标签：带背景色的小胶囊，一眼可辨任务状态 */
@Composable
private fun StatusBadge(status: SubAgentStatus) {
    val (label, container, content) = when (status) {
        SubAgentStatus.SUCCEEDED -> Triple(
            "成功",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> Triple(
            failureLabel(status),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        SubAgentStatus.CANCELLED -> Triple(
            "已取消",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SubAgentStatus.QUEUED -> Triple(
            "排队中",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SubAgentStatus.RUNNING -> Triple(
            "执行中",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun failureLabel(status: SubAgentStatus): String = when (status) {
    SubAgentStatus.FAILED -> "失败"
    SubAgentStatus.TIMEOUT -> "超时"
    SubAgentStatus.TOKEN_LIMIT -> "预算耗尽"
    else -> status.name
}

/**
 * 底部时间文案：
 * - 排队中 → "排队中"
 * - 运行中 → "已用 Xs/Xm Ys"（实时刷新）
 * - 终态   → "用时 X · 完成于 MM-dd HH:mm:ss"（完成时间精确到秒）
 */
private fun timeLabel(task: SubAgentTask, running: Boolean, now: Instant): String {
    return when {
        task.status == SubAgentStatus.QUEUED -> "排队中"
        running -> "已用 ${formatElapsed(task.startedAt, now)}"
        task.finishedAt != null ->
            "用时 ${formatElapsed(task.startedAt, task.finishedAt)} · 完成于 ${formatFinishedTime(task.finishedAt!!)}"
        else -> ""
    }
}

/** 完成时间精确到秒：MM-dd HH:mm:ss */
private fun formatFinishedTime(time: Instant): String {
    val local = time.toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d-%02d %02d:%02d:%02d".format(
        local.monthNumber, local.dayOfMonth,
        local.hour, local.minute, local.second,
    )
}

/** 终态且可续跑：失败/超时/取消/预算耗尽可重新执行 */
private fun canRerun(status: SubAgentStatus): Boolean =
    status == SubAgentStatus.FAILED || status == SubAgentStatus.TIMEOUT ||
        status == SubAgentStatus.CANCELLED || status == SubAgentStatus.TOKEN_LIMIT

/** 重新执行当前任务，成功后导航到新任务的详情页 */
private suspend fun rerunAndNavigate(
    runner: SubAgentRunner,
    navController: me.rerere.rikkahub.ui.context.Navigator,
    taskId: String,
) {
    val newId = runner.rerun(taskId)
    if (newId != null) {
        navController.navigate(Screen.SubAgentDetail(newId, null))
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
