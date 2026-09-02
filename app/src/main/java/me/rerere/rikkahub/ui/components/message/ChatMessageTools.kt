package me.rerere.rikkahub.ui.components.message

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.AskUserQuestion
import me.rerere.rikkahub.ui.components.ai.AskUserSheet
import me.rerere.rikkahub.ui.components.ai.parseAskUserQuestions
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.message.tools.toolApprovalPurpose
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.ToolParseCache

private const val ASK_USER_TOOL_NAME = "ask_user"

/** 默认折叠的工具：内容重、标题已表达意图的工作区文件类工具 */
private val COLLAPSED_BY_DEFAULT_TOOLS = setOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
)

private fun isCollapsedByDefaultTool(toolName: String): Boolean =
    toolName in COLLAPSED_BY_DEFAULT_TOOLS

/**
 * 工具气泡展开状态的进程级存储（key = toolCallId，UUID 全局唯一）。
 *
 * 导航到子代理详情/面板等页面返回时，Chat 重新组合，remember/rememberSaveable 都不可靠
 * （Navigation 3 对非栈顶 entry 的组合槽位重建，saveable 恢复不稳定）。存进程级单例，
 * 只要 App 进程存活，任何导航路径都能保持用户对工具气泡的展开/折叠意图。
 * toolCallId 全局唯一（UUID），天然隔离不同会话/消息，不串状态。
 */
internal val toolBubbleExpanded = mutableStateMapOf<String, Boolean>()

/** toolBubbleExpanded 的容量上限：超过即从最早插入的记录开始淘汰（见 trimToolBubbleExpanded） */
internal const val MAX_TOOL_BUBBLE_ENTRIES = 600

/**
 * 生命周期治理：toolBubbleExpanded 按 toolCallId 存储、不含会话 id，无法随会话切换精准清理，
 * 采用插入序容量上限淘汰（SnapshotStateMap 保插入序）。超限时从最早的记录开始丢弃，
 * 返回删除条数。最早的多是最久未触达的消息，其气泡回默认展开态影响可忽略；
 * 上限 600 远超单会话正常工具量，常态使用不会触发淘汰。
 */
fun trimToolBubbleExpanded(maxEntries: Int = MAX_TOOL_BUBBLE_ENTRIES): Int {
    var removed = 0
    val excess = toolBubbleExpanded.size - maxEntries
    if (excess <= 0) return 0
    for (key in toolBubbleExpanded.keys.toList()) {
        if (removed >= excess) break
        toolBubbleExpanded.remove(key)
        removed++
    }
    return removed
}

/**
 * ask_user 已填未提交的作答草稿（key = toolCallId，UUID 全局唯一）。
 *
 * Navigation 3 对非栈顶 entry 会重建组合槽位，remember 的作答进度在切页返回后清零；
 * 与 toolBubbleExpanded 同思路存进程级单例，只要 App 进程存活即可恢复草稿继续作答。
 * 提交（Answered）后移除；超容量按插入序淘汰最早记录。
 */
internal val askUserAnswerDrafts = mutableStateMapOf<String, AskUserAnswerDraft>()

/** askUserAnswerDrafts 容量上限：草稿体量比展开状态大，取 toolBubbleExpanded 的一半 */
internal const val MAX_ASK_USER_DRAFT_ENTRIES = 300

/** 单个 ask_user 工具调用的作答草稿；字段用 SnapshotStateMap 驱动 AskUserSheet 重组 */
internal class AskUserAnswerDraft {
    val answers: MutableMap<String, String> = mutableStateMapOf()
    val multiAnswers: MutableMap<String, Set<String>> = mutableStateMapOf()
}

/** 取或创建 toolCallId 的作答草稿；超过容量上限时先按插入序淘汰最早的记录 */
internal fun getOrCreateAskUserAnswerDraft(toolCallId: String): AskUserAnswerDraft {
    if (askUserAnswerDrafts.size >= MAX_ASK_USER_DRAFT_ENTRIES && !askUserAnswerDrafts.containsKey(toolCallId)) {
        var excess = askUserAnswerDrafts.size - MAX_ASK_USER_DRAFT_ENTRIES + 1
        for (key in askUserAnswerDrafts.keys.toList()) {
            if (excess <= 0) break
            if (askUserAnswerDrafts.remove(key) != null) excess--
        }
    }
    return askUserAnswerDrafts.getOrPut(toolCallId) { AskUserAnswerDraft() }
}

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                // 标题固定单行 + 省略号：避免长标题（如 shell 多行命令）被右侧 extra 宽窄变化
                // 挤到一行/两行之间反复切换，触发 animateContentSize 高度动画 + LazyColumn 锚点跳动。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

internal fun formatToolDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes <= 0 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        minutes < 60 -> String.format(Locale.US, "%dm%02ds", minutes, seconds)
        else -> String.format(Locale.US, "%dh%02dm%02ds", minutes / 60, minutes % 60, seconds)
    }
}

internal fun toolTextParts(tool: UIMessagePart.Tool): List<String> =
    tool.output.filterIsInstance<UIMessagePart.Text>().map { it.text }

internal fun toolExitCode(tool: UIMessagePart.Tool): Int? {
    var zeroCode: Int? = null
    for (text in toolTextParts(tool)) {
        val json = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
        val code = json["exitCode"]?.jsonPrimitive?.intOrNull ?: continue
        if (code != 0) return code
        zeroCode = code
    }
    return zeroCode
}

internal fun toolFailed(tool: UIMessagePart.Tool): Boolean {
    for (text in toolTextParts(tool)) {
        val json = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
        val exitCode = json["exitCode"]?.jsonPrimitive?.intOrNull
        if (exitCode != null && exitCode != 0) return true
        val error = json["error"]
        if (error is JsonPrimitive && error.contentOrNull?.isNotBlank() == true) return true
    }
    return false
}

@Composable
private fun ToolDurationText(tool: UIMessagePart.Tool) {
    val finished = tool.isFinished
    val started = tool.hasStarted
    val queued = tool.isQueued
    // 无开始时间也无排队时间：不展示时长（如 denied/answered 等直接结束的工具）
    if (!started && !queued) return
    val finishedAtMs = tool.finishedAtMs
    val finishedAt = tool.finishedAt
    // 基准点：已开始用 startedAt，排队等待中用 queuedAt
    val baseMs = if (started) tool.startedAtMs else tool.queuedAtMs
    val baseWall = if (started) tool.startedAt else tool.queuedAt
    if (baseMs == null && baseWall == null) return
    var durationMs by remember(baseMs, baseWall, finishedAtMs, finishedAt) {
        mutableLongStateOf(
            if (baseMs != null) {
                (finishedAtMs ?: SystemClock.elapsedRealtime()) - baseMs
            } else {
                ((finishedAt ?: Clock.System.now()) - baseWall!!).inWholeMilliseconds
            }
        )
    }
    LaunchedEffect(baseMs, baseWall, finishedAtMs, finishedAt) {
        if (finishedAtMs == null && finishedAt == null) {
            while (true) {
                delay(1000)
                durationMs = if (baseMs != null) {
                    SystemClock.elapsedRealtime() - baseMs
                } else {
                    (Clock.System.now() - baseWall!!).inWholeMilliseconds
                }
            }
        }
    }
    val exitCode = toolExitCode(tool)
    val failed = toolFailed(tool)
    Text(
        text = when {
            queued -> "等待中 ${formatToolDuration(durationMs)}"
            !tool.isFinished -> "执行中 ${formatToolDuration(durationMs)}"
            failed -> "失败 · ${formatToolDuration(durationMs)}${if (exitCode != null) " (exit $exitCode)" else ""}"
            else -> "完成 · ${formatToolDuration(durationMs)}"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    // AI 思考完成后自动折叠所有步骤：工具执行完成（流式中 false→true）时按开关折叠气泡。
    // 守卫与 ChatMessage 兜底一致：折叠会瞬间收掉工具摘要（该步内容直接条件渲染、无动画，
    // 流式中外层 animateContentSize 又不生效 → 高度骤减），用户在翻历史/拖拽中触发会引发
    // LazyColumn 锚点修正把列表吸回底部（"生成中调用工具后下拉回弹"根因）。
    // 仅列表贴底且用户未控制列表时才折叠；否则保持展开，回底后由生成结束兜底折叠。
    val isChatListAtBottom = LocalIsChatListAtBottom.current
    val isUserControlled = LocalIsChatListUserControlled.current
    // 用户手动展开/收起工具气泡时通知列表取消自动跟随（主聊天列表加载中生效）
    val onManualContentToggle = LocalOnManualContentToggle.current
    val settings = LocalSettings.current
    var wasExecuted by remember(tool.toolCallId) { mutableStateOf(tool.isExecuted) }
    LaunchedEffect(tool.isExecuted) {
        if (tool.isExecuted && !wasExecuted && settings.displaySetting.autoCollapseAllSteps &&
            (isChatListAtBottom?.invoke() != false) && (isUserControlled?.invoke() != true)
        ) {
            toolBubbleExpanded[tool.toolCallId] = false
        }
        wasExecuted = tool.isExecuted
    }
    val navController = LocalNavController.current
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    // 折叠类工具：点击优先展开/收起内联摘要，而不是直接弹 BottomSheet
    val collapsedByDefault = isCollapsedByDefaultTool(tool.toolName)
    // 展开状态存进程级单例 map（toolBubbleExpanded 是 mutableStateMapOf，读写自动触发重组）。
    // 跨导航保留：Navigation 3 对非栈顶 entry 组合重建，remember/rememberSaveable 恢复不可靠，
    // 单例 map 只要进程存活就稳定保留。
    // 默认折叠工作区文件类工具（读取/写入/编辑/shell，输入输出内容大、标题本身已表达意图），
    // 其余工具保持展开（map 无记录 → 按 isCollapsedByDefault 决定初始值）。
    val expanded = toolBubbleExpanded[tool.toolCallId] ?: !collapsedByDefault
    // 折叠类重工具（shell/write/edit/read_file）：折叠态不解析 output（输出大、折叠时用不到），
    // 展开才解析；非折叠类工具（默认展开、渲染器读 content 决定摘要）始终解析。
    val needContent = tool.isExecuted && (!collapsedByDefault || expanded)
    val arguments = remember(tool) { ToolParseCache.toolInput(tool) }
    val content = remember(tool, needContent) {
        if (needContent) ToolParseCache.toolOutputContent(tool) else null
    }
    val context = remember(tool, loading, content) {
        ToolUIContext(
            tool = tool,
            arguments = arguments,
            content = content,
            loading = loading,
        )
    }

    var showResult by remember(tool.toolCallId) { mutableStateOf(false) }
    var showDenyDialog by remember(tool.toolCallId) { mutableStateOf(false) }
    var approvalMenuExpanded by remember(tool.toolCallId) { mutableStateOf(false) }
    val onExpandedChange: (Boolean) -> Unit = { value ->
        // 始终写入显式值：初始默认按工具类型推导，用户一旦操作就记录真实意图。
        // 不能 remove 后回落默认——折叠类工具默认 false，remove 会让"展开"操作丢失。
        toolBubbleExpanded[tool.toolCallId] = value
        // 用户手动展开/收起工具气泡：通知列表取消自动跟随，避免高度骤增被拽到底部
        onManualContentToggle?.invoke()
    }
    val hapticController = rememberHaptic()
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // 加载态由渲染器决定（如子代理用任务真实状态，避免并行时已完成仍闪烁）
    val rendererLoading = renderer.isLoading(context, loading)

    // 状态配色（仅用于界面提示）：等待确认 / 执行中 / 失败 / 完成，配合头部行一眼看出工具状态
    val statusColor = when {
        isPending -> MaterialTheme.colorScheme.tertiary
        rendererLoading -> MaterialTheme.colorScheme.primary
        toolFailed(tool) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用。
    // 折叠类工具已执行时也始终保留可展开区域：折叠态 output 未解析、摘要为空，
    // 但必须有非空 content 才能出现展开箭头、可点击展开。否则折叠态 content 与
    // onClick 都为 null，步骤既无展开按钮也无法交互。展开后 needContent 变 true
    // 才解析 output 呈现摘要。
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()
        || (collapsedByDefault && tool.isExecuted)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        // 工具完成瞬间气泡会骤变（摘要/图片/时长行出现、loading 翻转），高度突变会让
        // LazyColumn 锚点修正、与用户拖拽/自动跟随打架（"工具调用后列表跳动"根因）。
        // 步骤自身高度动画平滑：流式中外层过程内容无动画（避免追 chunk），工具步骤
        // 变化频率低（执行中仅时长文本、完成后内容一次性出现），动画成本可忽略。
        // 用与外层 ChainOfThought 一致的短 tween：避免默认 spring 与外层 200ms tween 双重
        // 高度动画不同步造成回弹/抖动（"工具展开/完成时轻微抖一下"根因）。
        modifier = Modifier.animateContentSize(animationSpec = tween(200)),
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
                    tint = statusColor.copy(alpha = 0.8f)
                )
            }
        },
        label = {
            val titleText = renderer.title(context)
            val subtitle = renderer.subtitle(context)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor,
                    modifier = Modifier.shimmer(isLoading = rendererLoading),
                    // 标题固定单行 + 省略号：右侧 extra（执行时长/审批按钮/等待中）宽度会随时长步进
                    // 变化，若标题可两行换行会在一行/两行间切换，使步骤高度抖动。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 审批等待中：直接展示工具目的说明（中文映射 + 参数摘要），
                // 让用户清楚这个操作是干什么的再决定；未映射工具回退工具自带描述
                if (isPending && tool.description.isNotBlank()) {
                    Text(
                        text = toolApprovalPurpose(tool.toolName, tool.description, arguments),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                subtitle?.invoke()
            }
        },
        extra = if (
            (isPending && onToolApproval != null) ||
            (tool.isQueued && loading) ||
            (tool.hasStarted && (tool.isFinished || loading))
        ) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if ((tool.isQueued && loading) || (tool.hasStarted && (tool.isFinished || loading))) {
                        ToolDurationText(tool)
                    }
                    if (isPending && onToolApproval != null) {
                        FilledTonalIconButton(
                            onClick = { hapticController.lightTap(); showDenyDialog = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.chat_message_tool_deny),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { hapticController.lightTap(); onToolApproval(tool.toolCallId, true, "") },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = stringResource(R.string.chat_message_tool_approve),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        // 更多审批选项：同意相关所有审批 / 拒绝并输出原因
                        Box {
                            FilledTonalIconButton(
                                onClick = {
                                    hapticController.lightTap()
                                    approvalMenuExpanded = true
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.MoreHorizontal,
                                    contentDescription = stringResource(R.string.chat_message_tool_more_actions),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = approvalMenuExpanded,
                                onDismissRequest = { approvalMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.chat_message_tool_approve_all),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    onClick = {
                                        approvalMenuExpanded = false
                                        onApproveAllRelated?.invoke(tool.toolCallId)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.chat_message_tool_deny_and_output_reason),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    onClick = {
                                        approvalMenuExpanded = false
                                        showDenyDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            null
        },
        // 点击：折叠类工具（内容重、标题已表达意图）不设置 onClick，header 点击落到
        // 展开/收起内联摘要，完整详情走内容区内的"查看完整详情"入口；
        // 子代理（alwaysOpenPreview）直接进全屏详情页；其余工具点击打开 BottomSheet 详情
        onClick = if (renderer.alwaysOpenPreview) {
            {
                // spawn_subagent_completed 详情页导航：taskId 在 input 里（完成气泡 toolCallId 是随机 id，
                // 不能再用 toolCallId 定位任务）。兼容旧数据：input 无 taskId 时回退 toolCallId（旧格式 taskId==toolCallId）。
                val taskIdFromInput = (ToolParseCache.toolInput(tool) as? JsonObject)
                    ?.get("taskId")?.jsonPrimitive?.contentOrNull
                val subAgentTaskId = taskIdFromInput?.takeIf { it.isNotBlank() } ?: tool.toolCallId
                if (!subAgentTaskId.isNullOrBlank()) {
                    navController.navigate(
                        Screen.SubAgentDetail(subAgentTaskId, null)
                    )
                }
            }
        } else if (collapsedByDefault) {
            // 折叠类工具（内容重、标题已表达意图）：不设置 onClick，header 点击落到
            // 展开/收起内联摘要，完整详情走内容区内的"查看完整详情"入口。
            null
        } else if (context.content != null || isPending || images.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 摘要：summaryClickable 工具（如 todo 进度"x/n 已完成"）整块可点击查看 JSON 详情，
                    // 不再额外提供"查看完整详情"链接
                    val summaryClickable = renderer.summaryClickable &&
                        renderer.hasSummary(context) && !isPending
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (summaryClickable) {
                                    Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            hapticController.lightTap()
                                            showResult = true
                                        }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        renderer.Summary(context)
                    }
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
                    // 折叠类工具的摘要下方提供"查看完整详情"入口，展开后仍需弹窗看全量内容
                    // （summaryClickable 工具点摘要即可看详情，不重复提供）
                    if (collapsedByDefault && renderer.hasSummary(context) && !isPending && !renderer.summaryClickable) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    hapticController.lightTap()
                                    showResult = true
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_view_full_detail),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
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
    // 用户手动展开/收起问答气泡时通知列表取消自动跟随（主聊天列表加载中生效）
    val onManualContentToggle = LocalOnManualContentToggle.current

    val title = remember(arguments) {
        // title 类型漂移（对象/数组）时不至于在组合期抛异常
        runCatching { arguments.jsonObject["title"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
    val questions = remember(arguments) {
        parseAskUserQuestions(arguments)
    }

    var showSheet by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }

    // Hoisted answer state — persists across sheet open/close.
    // 草稿按 toolCallId 存进程级单例（见 askUserAnswerDrafts）：切页返回/组合槽位重建后作答进度不丢
    val draft = remember(tool.toolCallId) { getOrCreateAskUserAnswerDraft(tool.toolCallId) }
    val answers = draft.answers
    val multiAnswers = draft.multiAnswers

    // 提交（Answered）后草稿完成使命，移除释放
    LaunchedEffect(isAnswered) {
        if (isAnswered) askUserAnswerDrafts.remove(tool.toolCallId)
    }

    // Auto-open the sheet when the tool becomes pending
    // 模型未返回有效问题时（questions 为空）不自动弹出，避免空列表崩溃
    LaunchedEffect(isPending, questions.isEmpty()) {
        if (isPending && questions.isNotEmpty()) showSheet = true
    }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            // 用户手动展开/收起问答气泡：通知列表取消自动跟随，避免高度骤增被拽到底部
            onManualContentToggle?.invoke()
        },
        // 与外层 ChainOfThought / 工具气泡一致的短 tween，避免默认 spring 双重高度动画不同步
        modifier = Modifier.animateContentSize(animationSpec = tween(200)),
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
                    // 空态重试路径：用户已把「问题无效」回传模型
                    isAnswered && questions.isEmpty() -> "已反馈问题无效"
                    isAnswered -> "已回答 ${questions.size} 个问题"
                    // 流式生成参数期间 JSON 不完整、解析结果为空，不代表出错；生成结束仍为空才是真异常
                    questions.isEmpty() && loading -> "正在生成问题…"
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
                    if (questions.isEmpty()) {
                        // 空态重试路径：没有可逐条展示的问题，展示回传内容（错误说明或旧数据原文）
                        Text(
                            text = answerJson?.jsonObject?.get("error")
                                ?.jsonPrimitive?.contentOrNull ?: answeredState.answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
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

/** Parse AskUserQuestion list from tool JSON arguments: moved to AskUserQuestionParser.kt（可 JVM 单测） */

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
            TextButton(onClick = { hapticController.lightTap(); onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = { hapticController.lightTap(); onDismiss() }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
