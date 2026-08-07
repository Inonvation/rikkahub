package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowUp02
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
import me.rerere.rikkahub.data.ai.subagent.SUBAGENT_GUIDANCE_MARKER
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.message.ChatMessageNerdLine
import me.rerere.rikkahub.ui.components.message.ChatMessageReasoningStep
import me.rerere.rikkahub.ui.components.message.ChatMessageServerToolStep
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.context.LocalNavController
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
    val settingsStore: SettingsStore = koinInject()
    val navController = LocalNavController.current
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

    // 进程重启后任务不在内存里：从 Room 历史库恢复，让详情页仍能展示历史执行结果。
    // 内存态出现任务（运行中/刚派发）时以内存态为准，覆盖历史快照。
    var historyTask by remember { mutableStateOf<SubAgentTask?>(null) }
    LaunchedEffect(task?.taskId, task?.status) {
        if (task == null) {
            historyTask = runner.getTaskFromHistory(taskId)
        } else {
            historyTask = null
        }
    }
    val currentTask = task ?: historyTask

    val def = currentTask?.agentId?.let { SubAgentCatalog.byId(it) }
    val agentName = def?.name ?: currentTask?.agentId ?: "subagent"
    val status = currentTask?.status
    val running = status == SubAgentStatus.QUEUED || status == SubAgentStatus.RUNNING

    // 引导消息：仅当设置开启且任务运行中时显示输入框
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var guidanceText by rememberSaveable { mutableStateOf("") }

    // 自动滚动跟随：仅在用户位于底部时滚到最新（仿 ChatPage）
    val bottomThresholdPx = with(density) { 120.dp.toPx() }
    val isAtBottom by remember {
        derivedStateOf { scrollState.maxValue - scrollState.value < bottomThresholdPx }
    }
    val messagesFingerprint = currentTask?.messages?.hashCode() ?: 0
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
                                text = statusLabel(status, currentTask),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (running) {
                                Spacer(Modifier.width(8.dp))
                                // 运行中的异步任务可取消
                                if (runner.isCancellable(taskId)) {
                                    IconButton(
                                        onClick = { runner.cancel(taskId, notifyParent = true) },
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
                            } else if (canRerun(status)) {
                                Spacer(Modifier.width(8.dp))
                                // 终态（失败/超时/取消/进程中断）可重新执行：以新任务续跑，带上上次部分结果
                                val scope = rememberCoroutineScope()
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            rerunAndNavigate(runner, navController, taskId)
                                        }
                                    },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Refresh01,
                                        contentDescription = stringResource(R.string.subagent_detail_rerun),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            // 引导消息输入框：固定在底部，仅当设置开启且任务运行中显示。
            // 用 imePadding + navigationBarsPadding 跟随键盘/避开导航栏。
            if (settings.subAgentAllowGuidance && running) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = guidanceText,
                            onValueChange = { guidanceText = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.subagent_detail_guidance_placeholder),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(
                            onClick = {
                                if (guidanceText.isNotBlank() && runner.submitGuidance(taskId, guidanceText)) {
                                    guidanceText = ""
                                }
                            },
                            enabled = guidanceText.isNotBlank(),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowUp02,
                                contentDescription = stringResource(R.string.subagent_detail_guidance_send),
                                tint = if (guidanceText.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
            val current = currentTask ?: run {
                // 任务不存在（内存与历史库都没有，只有 tool output 终态 JSON）
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

            // 2. 内容区：按 messages 原始顺序渲染——
            //    - 引导 USER 消息（speakerName == SUBAGENT_GUIDANCE_MARKER）→ "用户引导"气泡
            //    - assistant 消息 → 复用主聊天区 groupMessageParts 渲染思维链 + 文本气泡
            //    思考/工具交错时间线 + Text 气泡按原始顺序穿插（AI 调完工具输出一段话再继续调，
            //    中间穿插的话都显示），工具执行中显示"调用中"状态
            var renderedAssistant = false
            for (msg in current.messages) {
                when {
                    msg.speakerName == SUBAGENT_GUIDANCE_MARKER -> {
                        // 用户引导气泡（去掉注入时给模型看的前缀）
                        GuidanceBubble(text = msg.toText().removePrefix("【用户引导】"))
                    }

                    msg.role == me.rerere.ai.core.MessageRole.ASSISTANT && !renderedAssistant -> {
                        renderedAssistant = true
                        val blocks = remember(msg.parts) { msg.parts.groupMessageParts() }
                        blocks.forEach { block ->
                            when (block) {
                                is MessagePartBlock.ThinkingBlock -> {
                                    if (block.steps.isNotEmpty()) {
                                        ChainOfThought(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp),
                                            steps = block.steps,
                                            // 详情页卡片满宽：不做自适应宽度，label 恒满宽、箭头位置稳定不跳
                                            collapsedAdaptiveWidth = false,
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
                                                        collapsedAdaptiveWidth = false,
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

                                                is ThinkingStep.ServerToolStep -> key(
                                                    step.tool.toolCallId.ifBlank { step.hashCode().toString() }
                                                ) {
                                                    ChatMessageServerToolStep(tool = step.tool)
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
                    }
                }
            }
            if (!renderedAssistant) {
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

            // token 消耗行：终态后展示（复用主聊天区的 NerdLine）
            val taskUsage = current.usage
            if (taskUsage != null) {
                val usageMessage = UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = emptyList(),
                    createdAt = current.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishedAt = current.finishedAt?.toLocalDateTime(TimeZone.currentSystemDefault()),
                    usage = taskUsage,
                )
                ChatMessageNerdLine(
                    message = usageMessage,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 任务结束时间：终态后展示（精确到秒）。
            // 成功显示"完成于"，失败/超时/取消等显示"结束于"，让用户明确任务的收尾时刻。
            val finishedAt = current.finishedAt
            if (current.status.isTerminal && finishedAt != null) {
                val local = finishedAt.toLocalDateTime(TimeZone.currentSystemDefault())
                val timeStr = "%04d-%02d-%02d %02d:%02d:%02d".format(
                    local.year, local.monthNumber, local.dayOfMonth,
                    local.hour, local.minute, local.second,
                )
                val label = if (current.status == SubAgentStatus.SUCCEEDED) "完成于" else "结束于"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "$label $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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

/**
 * 用户引导气泡：右侧 secondaryContainer 样式，带"用户引导"标签。
 * 由 SubAgentRunner.drainGuidance 注入的带 SUBAGENT_GUIDANCE_MARKER 标记的 USER 消息渲染而来。
 */
@Composable
private fun GuidanceBubble(text: String) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 4.dp,
        bottomEnd = 16.dp,
        bottomStart = 16.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = "用户引导",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun statusIcon(status: SubAgentStatus?): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    SubAgentStatus.SUCCEEDED -> HugeIcons.Tick01
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> HugeIcons.Alert01
    SubAgentStatus.CANCELLED -> HugeIcons.Cancel01
    SubAgentStatus.QUEUED, SubAgentStatus.RUNNING -> HugeIcons.Clock02
    null -> HugeIcons.Clock02
}

@Composable
private fun statusColor(status: SubAgentStatus?): androidx.compose.ui.graphics.Color = when (status) {
    SubAgentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    SubAgentStatus.FAILED, SubAgentStatus.TIMEOUT, SubAgentStatus.TOKEN_LIMIT -> MaterialTheme.colorScheme.error
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
        SubAgentStatus.TOKEN_LIMIT -> "Token Limit"
        SubAgentStatus.CANCELLED -> "Cancelled"
        null -> ""
    }
    val retried = task?.retryCount?.takeIf { it > 0 }
    return when {
        status == SubAgentStatus.SUCCEEDED && task != null ->
            "$base · ${formatElapsed(task.startedAt, task.finishedAt)}${retried?.let { " · ${it} retr" } ?: ""}"
        status == SubAgentStatus.RUNNING ->
            "$base · ${formatElapsed(task?.startedAt)}${retried?.let { " · ${it} retr" } ?: ""}"
        status == SubAgentStatus.TOKEN_LIMIT && task != null -> "$base · ${formatElapsed(task.startedAt, task.finishedAt)}"
        else -> base
    }
}

/** 终态且可续跑：失败/超时/取消（含进程中断的僵尸任务）可重新执行 */
private fun canRerun(status: SubAgentStatus?): Boolean =
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

private fun formatElapsed(start: Instant?, end: Instant? = null): String {
    if (start == null) return ""
    val millis = (end ?: Clock.System.now()).toEpochMilliseconds() - start.toEpochMilliseconds()
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
