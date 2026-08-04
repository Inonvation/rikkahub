package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.AskUserQuestion
import me.rerere.rikkahub.ui.components.ai.AskUserSheet
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"

/**
 * 工具气泡展开状态的进程级存储（key = toolCallId，UUID 全局唯一）。
 *
 * 导航到子代理详情/面板等页面返回时，Chat 重新组合，remember/rememberSaveable 都不可靠
 * （Navigation 3 对非栈顶 entry 的组合槽位重建，saveable 恢复不稳定）。存进程级单例，
 * 只要 App 进程存活，任何导航路径都能保持用户对工具气泡的展开/折叠意图。
 * toolCallId 全局唯一（UUID），天然隔离不同会话/消息，不串状态。
 */
private val toolBubbleExpanded = mutableStateMapOf<String, Boolean>()

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val navController = LocalNavController.current
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember(tool.toolCallId) { mutableStateOf(false) }
    var showDenyDialog by remember(tool.toolCallId) { mutableStateOf(false) }
    // 展开状态存进程级单例 map（toolBubbleExpanded 是 mutableStateMapOf，读写自动触发重组）。
    // 跨导航保留：Navigation 3 对非栈顶 entry 组合重建，remember/rememberSaveable 恢复不可靠，
    // 单例 map 只要进程存活就稳定保留。默认展开（map 无记录）。
    val expanded = toolBubbleExpanded[tool.toolCallId] ?: true
    val onExpandedChange: (Boolean) -> Unit = { value ->
        if (value) toolBubbleExpanded.remove(tool.toolCallId) else toolBubbleExpanded[tool.toolCallId] = false
    }
    val hapticController = rememberHaptic()
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // 加载态由渲染器决定（如子代理用任务真实状态，避免并行时已完成仍闪烁）
    val rendererLoading = renderer.isLoading(context, loading)

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        icon = {
            if (rendererLoading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = rendererLoading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { hapticController.perform(HapticFeedbackType.KeyboardTap); showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { hapticController.perform(HapticFeedbackType.KeyboardTap); onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        // 点击：alwaysOpenPreview 工具（子代理）直接进全屏详情页（不弹 BottomSheet）；
        // 普通工具打开 BottomSheet 详情
        onClick = if (context.content != null || isPending || images.isNotEmpty() || renderer.alwaysOpenPreview) {
            if (renderer.alwaysOpenPreview) {
                {
                    // spawn_subagent_completed 详情页导航：taskId 在 input 里（完成气泡 toolCallId 是随机 id，
                    // 不能再用 toolCallId 定位任务）。兼容旧数据：input 无 taskId 时回退 toolCallId（旧格式 taskId==toolCallId）。
                    val taskIdFromInput = (tool.inputAsJson() as? JsonObject)
                        ?.get("taskId")?.jsonPrimitive?.contentOrNull
                    val subAgentTaskId = taskIdFromInput?.takeIf { it.isNotBlank() } ?: tool.toolCallId
                    if (!subAgentTaskId.isNullOrBlank()) {
                        navController.navigate(
                            Screen.SubAgentDetail(subAgentTaskId, null)
                        )
                    }
                }
            } else {
                { showResult = true }
            }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    val title = remember(arguments) {
        arguments.jsonObject["title"]?.jsonPrimitive?.contentOrNull
    }
    val questions = remember(arguments) {
        parseAskUserQuestions(arguments)
    }

    var showSheet by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }

    // Hoisted answer state — persists across sheet open/close
    val answers = remember { mutableStateMapOf<String, String>() }
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    // Auto-open the sheet when the tool becomes pending
    // 模型未返回有效问题时（questions 为空）不自动弹出，避免空列表崩溃
    LaunchedEffect(isPending, questions.isEmpty()) {
        if (isPending && questions.isNotEmpty()) showSheet = true
    }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = when {
                    isAnswered -> "已回答 ${questions.size} 个问题"
                    questions.isEmpty() -> "模型未返回有效问题"
                    questions.size <= 1 -> questions.firstOrNull()?.question ?: "..."
                    else -> stringResource(R.string.chat_message_tool_ask_questions, questions.size)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolAnswer != null) {
            {
                FilledTonalIconButton(
                    onClick = { showSheet = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.BubbleChatQuestion,
                        contentDescription = "回答",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else null,
        content = if (isAnswered) {
            {
                val answeredState = tool.approvalState as ToolApprovalState.Answered
                val answerJson = runCatching {
                    JsonInstant.parseToJsonElement(answeredState.answer)
                }.getOrNull()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    questions.forEach { q ->
                        val answerText = answerJson?.jsonObject?.get("answers")
                            ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                            ?: answeredState.answer
                        Text(
                            text = "${q.question}: $answerText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else null,
        onClick = if (isPending && onToolAnswer != null) {
            { showSheet = true }
        } else null,
    )

    if (showSheet) {
        AskUserSheet(
            title = title,
            questions = questions,
            answers = answers,
            multiAnswers = multiAnswers,
            onSubmit = { answer ->
                showSheet = false
                onToolAnswer?.invoke(tool.toolCallId, answer)
            },
            onDismiss = { showSheet = false },
        )
    }
}

/** Parse AskUserQuestion list from tool JSON arguments. Includes new optional fields. */
private fun parseAskUserQuestions(arguments: kotlinx.serialization.json.JsonElement): List<AskUserQuestion> {
    return runCatching {
        arguments.jsonObject["questions"]?.jsonArray?.map { q ->
            val obj = q.jsonObject
            AskUserQuestion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                rationale = obj["rationale"]?.jsonPrimitive?.contentOrNull ?: "",
                options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text",
                placeholder = obj["placeholder"]?.jsonPrimitive?.contentOrNull ?: "",
                required = obj["required"]?.jsonPrimitive?.contentOrNull?.let { it != "false" } ?: true,
            )
        } ?: emptyList()
    }.getOrElse { emptyList() }
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    val hapticController = rememberHaptic()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { hapticController.perform(HapticFeedbackType.KeyboardTap); onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = { hapticController.perform(HapticFeedbackType.KeyboardTap); onDismiss() }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
