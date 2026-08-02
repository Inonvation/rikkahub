package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.sample
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import me.rerere.rikkahub.ui.components.message.ChatMessageReasoningStep
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 子代理全屏详情页（"像打开另一个聊天窗口"）。
 *
 * 布局：任务描述气泡 → 思维链时间线（思考+工具按执行顺序交错，复用主聊天区
 * [ChainOfThought] / [ChatMessageReasoningStep] / [ChatMessageToolStep]，工具可点开弹窗看入参/输出）
 * → 最终输出气泡（markdown）。
 *
 * 数据源 [SubAgentRunner.observeTask]（taskId == 母代理 toolCallId），进程内 StateFlow 实时订阅。
 * 性能：collect 侧 sample(100ms) + distinctUntilChangedBy 指纹节流，避免 15ms 级流式高频重组。
 * 滚动：isAtBottom 守卫，用户上翻时间线时不强拉到底。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun SubAgentDetailPage(taskId: String, conversationId: String?) {
    val runner: SubAgentRunner = koinInject()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // 节流：sample(200ms) + 指纹。指纹跟踪"实际渲染内容"的文本长度（非分桶），
    // 让流式文本平滑逐段更新——之前按 length/512 分桶，512 字符内指纹不变，
    // UI 显示旧内容、越过边界才蹦出一大段，正是"卡一会突然蹦"的根源。
    // 跟随滚动用 scrollTo（瞬移）而非 animateScrollTo（动画）——每次消息更新重启动画是流式期间卡顿的主因。
    val task by remember(taskId) {
        runner.observeTask(taskId)
            .sample(200)
            .distinctUntilChangedBy { t ->
                if (t == null) "null"
                else {
                    val lastAssistantMsg = t.messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    val lastTextLen = lastAssistantMsg?.parts
                        ?.filterIsInstance<UIMessagePart.Text>()
                        ?.sumOf { it.text.length } ?: 0
                    "${t.status}|${t.steps.size}|${t.toolCalls.size}|$lastTextLen|${t.reasoning.length}"
                }
            }
    }.collectAsStateWithLifecycle(initialValue = null)

    val def = task?.agentId?.let { SubAgentCatalog.byId(it) }
    val agentName = def?.name ?: task?.agentId ?: "subagent"
    val status = task?.status
    val running = status == SubAgentStatus.QUEUED || status == SubAgentStatus.RUNNING

    // 自动滚动跟随：仅在用户位于底部时滚到最新（仿 ChatPage）
    val bottomThresholdPx = with(density) { 120.dp.toPx() }
    val isAtBottom by remember {
        derivedStateOf { scrollState.maxValue - scrollState.value < bottomThresholdPx }
    }
    val messagesFingerprint = task?.messages?.hashCode() ?: 0
    LaunchedEffect(messagesFingerprint, status) {
        if (isAtBottom) {
            // 流式期间用瞬移 scrollTo（重启动画会打断渲染导致卡顿）
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agentName, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = statusIcon(status),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = statusColor(status),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = statusLabel(status, task),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (running) {
                                Spacer(Modifier.width(8.dp))
                                // 运行中的异步任务可取消
                                if (runner.isCancellable(taskId)) {
                                    IconButton(
                                        onClick = { runner.cancel(taskId) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(R.string.subagent_panel_cancel),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = task ?: run {
                // 任务不存在（进程重启后 task 已丢失，只有 tool output 终态 JSON）
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.subagent_detail_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // 1. 任务描述（右侧用户气泡）
            if (current.request.isNotBlank()) {
                ChatBubble(isUser = true) {
                    Text(
                        text = current.request,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // 2. 内容区：完全复用主聊天区的 groupMessageParts 渲染管线——
            //    思考/工具交错时间线 + Text 气泡按原始顺序穿插（AI 调完工具输出一段话再继续调，
            //    中间穿插的话都显示），工具执行中显示"调用中"状态
            val assistantMsg = current.messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
            if (assistantMsg != null) {
                val blocks = remember(assistantMsg.parts) { assistantMsg.parts.groupMessageParts() }
                blocks.forEach { block ->
                    when (block) {
                        is MessagePartBlock.ThinkingBlock -> {
                            if (block.steps.isNotEmpty()) {
                                val isReasoningOnlyBlock = block.steps.all { it is ThinkingStep.ReasoningStep }
                                ChainOfThought(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    steps = block.steps,
                                    collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    cardColors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                ) { step ->
                                    when (step) {
                                        is ThinkingStep.ReasoningStep -> key(step.reasoning.createdAt) {
                                            ChatMessageReasoningStep(
                                                reasoning = step.reasoning,
                                                model = null,
                                                assistant = null,
                                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                            )
                                        }

                                        is ThinkingStep.ToolStep -> key(
                                            step.tool.toolCallId.ifBlank { step.hashCode().toString() }
                                        ) {
                                            // 工具执行中（未回填 output）→ loading=true 显示"调用中"；
                                            // 完成后 loading=false。onToolApproval/onToolAnswer=null（无审批路径）
                                            ChatMessageToolStep(
                                                tool = step.tool,
                                                loading = running && !step.tool.isExecuted,
                                                onToolApproval = null,
                                                onToolAnswer = null,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is MessagePartBlock.ContentBlock -> key(block.index) {
                            when (val part = block.part) {
                                is UIMessagePart.Text -> {
                                    if (part.text.isNotBlank()) {
                                        ChatBubble(isUser = false) {
                                            MarkdownBlock(
                                                content = part.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }
            } else {
                // 没有结构化 assistant 消息（任务完成但 messages 为空，或任务已结束）。
                // 兜底：用 resultSummary / streamText 渲染最终结果，避免"任务完成却空白"。
                val fallbackText = current.resultSummary
                    ?.takeIf { it.isNotBlank() }
                    ?: current.streamText.takeIf { it.isNotBlank() }
                if (!fallbackText.isNullOrBlank()) {
                    ChatBubble(isUser = false) {
                        MarkdownBlock(
                            content = fallbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (!current.status.isTerminal) {
                    // 执行中但还没有任何输出：思考中占位
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_detail_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.shimmer(isLoading = true),
                        )
                    }
                }
            }

            // 错误
            val error = current.error
            if (error != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_panel_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** 聊天气泡：用户任务 → 右侧主色；助手输出 → 左侧 surfaceVariant */
@Composable
private fun ChatBubble(isUser: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (isUser) 16.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 16.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

private fun statusIcon(status: SubAgentStatus?): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    SubAgentStatus.SUCCEEDED -> HugeIcons.Tick01
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT -> HugeIcons.Alert01
    SubAgentStatus.CANCELLED -> HugeIcons.Cancel01
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> HugeIcons.Clock02
    null -> HugeIcons.Clock02
}

@Composable
private fun statusColor(status: SubAgentStatus?): androidx.compose.ui.graphics.Color = when (status) {
    SubAgentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT -> MaterialTheme.colorScheme.error
    SubAgentStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(status: SubAgentStatus?, task: SubAgentTask?): String {
    val base = when (status) {
        SubAgentStatus.QUEUED -> "Queued"
        SubAgentStatus.RUNNING -> "Running"
        SubAgentStatus.SUCCEEDED -> "Done"
        SubAgentStatus.FAILED -> "Failed"
        SubAgentStatus.TIMEOUT -> "Timeout"
        SubAgentStatus.CANCELLED -> "Cancelled"
        null -> ""
    }
    return when {
        status == SubAgentStatus.SUCCEEDED && task != null -> "$base · ${formatElapsed(task.startedAt, task.finishedAt)}"
        status == SubAgentStatus.RUNNING -> "$base · ${formatElapsed(task?.startedAt)}"
        else -> base
    }
}

private fun formatElapsed(start: Instant?, end: Instant? = null): String {
    if (start == null) return ""
    val millis = (end ?: Clock.System.now()).toEpochMilliseconds() - start.toEpochMilliseconds()
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
