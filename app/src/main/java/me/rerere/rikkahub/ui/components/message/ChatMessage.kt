package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.message.LocalConversationId
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 完成兜底折叠的延迟窗口（codex 式时序下的第二折叠时机）。
 *
 * 折叠时机模型（见触发/唤醒/完成/补折叠四个 effect）：
 * - **第一时机（loading 中）**：正文开始且过程区无进行中步骤 → 收卡，200ms 收起
 *   动画与正文首行并行（codex/ChatGPT 桌面端同节奏）；折叠高度随即被正文流式
 *   增长填补，用户控制列表时暂缓、放手后自动重估，无"漏折"路径；
 * - 完成兜底：第一时机被暂缓的残余场景，消息完成时按视口外（瞬时无动画）/贴底
 *   （带动画）快速折叠，其余留待补折叠 effect 在用户回底/滚出视口/放手后落地；
 * - 手动接管（manualOverride）：用户点过折叠卡后本生成周期内自动折叠一律让位。
 * 窗口（350ms）只用于让过生成收尾的布局/动画（reasoning 收起、末块排版落定），
 * 避免与逐卡折叠动画叠帧造成二次抖动。布局回调做"滚出即折叠"实测不可靠
 * （item 滚出视口后停止布局，回调停更），已弃用，一律以组合生命周期为折叠时机。
 */
private const val AUTO_COLLAPSE_DELAY_MS = 350L

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onAssistantNameClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f),
                    onAssistantNameClick = onAssistantNameClick,
                    onAvatarClick = if (assistant != null) {
                        {
                            navController.navigate(
                                Screen.AssistantDetail(assistant.id.toString())
                            )
                        }
                    } else {
                        null
                    },
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                nodeId = node.id.toString(),
                messageCreatedAt = message.createdAt,
                messageFinishedAt = message.finishedAt,
                onToolApproval = onToolApproval,
                onApproveAllRelated = onApproveAllRelated,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        // 末条消息的操作按钮行常驻占位高度（生成中也占位）：消除生成结束瞬间
        // 消息高度突变导致的 LazyColumn 锚点重排跳动（无输入时列表自移 ~30px）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (lastMessage) 32.dp else 0.dp),
            // 用户消息的按钮行保持右对齐（与气泡对齐），助手消息左对齐
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        ) {
            AnimatedVisibility(
                visible = showActions,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                Column(
                    modifier = Modifier.animateContentSize()
                ) {
                    ChatMessageActionButtons(
                        message = message,
                        onRegenerate = onRegenerate,
                        node = node,
                        onUpdate = onUpdate,
                        onOpenActionSheet = {
                            showActionsSheet = true
                        },
                        onTranslate = onTranslate,
                        onClearTranslation = onClearTranslation
                    )
                }
            }
        }

        // 仅当消息含工具调用时才组合文件变更/学习卡片：
        // 普通文本消息不创建这两个空卡片组件，减少流式重组时的组合开销
        if (message.parts.any { it is UIMessagePart.Tool }) {
            val messageId = message.id.toString()
            EditedFilesList(
                parts = message.parts,
                assistant = assistant,
                messageId = messageId,
            )

            TrustedFolderEditedFilesList(parts = message.parts, messageId = messageId)

            StudyItemsList(parts = message.parts)
        }

        // 统计行：生成期间隐藏（alpha=0）但用虚拟 finishedAt 渲染出与完成态一致的
        // 完整行（tokens/tok/s/耗时），生成结束零高度差，LazyColumn 锚点不受影响。
        if (!loading || (lastMessage && settings.showTokenUsage)) {
            Box(
                modifier = Modifier.graphicsLayer { alpha = if (loading) 0f else 1f },
            ) {
                ProvideTextStyle(textStyle) {
                    ChatMessageNerdLine(
                        message = if (loading) {
                            message.copy(
                                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                            )
                        } else {
                            message
                        },
                    )
                }
            }
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    nodeId: String,
    messageCreatedAt: LocalDateTime,
    messageFinishedAt: LocalDateTime?,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)
    // 折叠后重新贴底用：组合期捕获，回调中调用（lambda 内部按调用时刻读当前布局）
    val isChatListAtBottom = LocalIsChatListAtBottom.current
    val scrollChatToBottom = LocalScrollChatToBottom.current
    // 用户是否正在控制列表（触碰中/滚动中/刚操作过）：自动折叠据此暂缓
    val isUserControlled = LocalIsChatListUserControlled.current
    // 用户手动展开/收起过程区/思考步骤/工具气泡等时通知列表取消自动跟随
    val onManualContentToggle = LocalOnManualContentToggle.current
    // 记录折叠瞬间是否在底部：动画落定后重新贴底，抵消 LazyColumn scrollBack 的上移
    var collapseAtBottom by remember { mutableStateOf(false) }

    // 思考链"已处理"时长：消息创建到完成（AI 处理这条请求的总耗时），用于折叠态标题统计
    // 实时"已处理"时长：生成中每秒刷新，完成后固定为消息创建到完成
    var nowTick by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                nowTick = Clock.System.now()
                delay(1.seconds)
            }
        }
    }
    val processedDuration: Duration? = remember(messageCreatedAt, messageFinishedAt, nowTick) {
        val start = messageCreatedAt.toInstant(TimeZone.currentSystemDefault())
        val end = messageFinishedAt?.toInstant(TimeZone.currentSystemDefault()) ?: nowTick
        (end - start).coerceAtLeast(Duration.ZERO)
    }
    // 折叠态控制条文案："已处理 n分m秒"（不足一分钟显示秒，负值钳制为 0）
    val processedLabel: String? = processedDuration?.let { d ->
        val totalSeconds = d.inWholeSeconds
        if (totalSeconds >= 60) {
            stringResource(
                R.string.chain_of_thought_processed_min_sec,
                totalSeconds / 60,
                totalSeconds % 60,
            )
        } else {
            stringResource(R.string.chain_of_thought_processed_sec, totalSeconds)
        }
    }

    // 工具气泡的自动收起只由 step 级 effect（ChatMessageToolStep）在工具完成时逐个执行。
    // 不再做"生成完成时刻一次性折叠全部气泡"的消息级兜底：气泡折叠是可见高度变化
    // （各自 animateContentSize），完成瞬间执行会与用户紧跟着的下拉起手重叠（"生成完
    // 下拉跳动"根因之一）；step 级折叠随工具完成逐个落地已覆盖主路径，被守卫暂缓的
    // 个别气泡保持展开，待本消息过程区折叠/滚出视口重建后一并收起。

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    // 最终输出起点：最后一个 ContentBlock 之前的内容（思考链 + 中间输出）视为"过程"，可整体折叠
    val finalOutputStart = groupedParts.indexOfLast { it is MessagePartBlock.ContentBlock }
    val autoCollapseAll = settings.displaySetting.autoCollapseAllSteps
    val hasThinkingSteps = parts.any {
        it is UIMessagePart.Reasoning || it is UIMessagePart.Tool || it is UIMessagePart.ServerTool
    }
    val hasProcessContent =
        role == MessageRole.ASSISTANT && (finalOutputStart > 0 || hasThinkingSteps)
    // codex 式折叠时序的两个信号（最终输出开始 = finalBlocks 非空 ⇔ 存在 ContentBlock）：
    // - finalOutputStarted：正文首 chunk 已到达（思考/工具链之后的最终输出区非空）；
    // - processActive：仍有进行中的步骤（思考未 finish / 工具未执行完 / 服务端工具
    //   未结束）。两者共同构成"过程已结束、正文已开始"的折叠触发条件。按流式顺序
    //   正文首 chunk 到达时前序过程通常已完成，processActive 为防御条件（防异常
    //   交错的流式数据在过程未定时提前折叠）；审批/ask_user 未答时 isExecuted=false
    //   → processActive=true，折叠被天然抑制。
    val finalOutputStarted = finalOutputStart >= 0
    val processActive = parts.any {
        (it is UIMessagePart.Reasoning && it.finishedAt == null) ||
            (it is UIMessagePart.Tool && !it.isExecuted) ||
            (it is UIMessagePart.ServerTool && !it.isFinished)
    }
    // 手动折叠记忆 key（与思考步骤 sectionExpanded / 工具气泡 toolBubbleExpanded 同款进程级存储）：
    // item 滚出视口会被 LazyColumn 销毁，本地 remember 重建后只能按开关强制推导折叠态，
    // 此前以展开态出现过的过程区会在重新进入视口的瞬间塌缩（高度骤减触发 LazyColumn 锚点修正，
    // 即"下拉历史回弹抽搐"根源），故手动形态须落入进程级 store。
    // 前缀 process: 不与思考链的 chain: 冲突；无会话上下文（导出预览等）为 null → 退化为纯推导。
    val chainStateKey = LocalConversationId.current?.let { "process:$it:$nodeId" }
    // 最终输出列顶部在窗口坐标中的 Y（onLayoutRectChanged 逐帧同步；只写不读于组合，
    // 不引发重组）：过程区紧邻其上方，"输出顶 ≤ 列表视口顶" 即过程区已完全滚出视口
    // 上方的充分条件（贴底满屏态）。未布局/无列表上下文时保持 MAX_VALUE → 判定不成立，
    // 折叠走"滚出视口后重建"路径，安全默认。
    var finalOutputTopY by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // false 时过程区高度变化跳过 animateContentSize（自动折叠的"无动画瞬时收起"专用），
    // 折叠落地后立即恢复 true，用户手动展开/收起仍保持平滑高度动画。
    var chainCollapseAnimated by remember { mutableStateOf(true) }
    // 列表视口顶（窗口坐标）：来自聊天页提供的思考吸顶冻结状态（其 topBarBottomY 即
    // 消息列表容器顶）；预览等无列表上下文为 null → 视口外折叠不触发。
    val thinkingFreezeState = LocalThinkingFreezeState.current
    // 整体折叠：开启开关后，过程内容在"正文开始"时机自动折叠成“已处理 n分m秒”卡片，
    // 只保留最终输出（codex 式时序，见下方触发 effect）。初始形态优先读进程级记忆；
    // 无记忆时按开关推导。
    // 写入口径（单向化）：自动路径只写折叠（false）——触发 effect / 完成兜底 B/C /
    // 补折叠，折叠落地即写；展开态（生成中 init 强制展开、唤醒展开）一律不写。
    // 手动路径双向写（点击时 !willCollapse），手动优先级最高（manualOverride 让位自动）。
    // 注意 store 语义统一为「true=展开」（与 reasoning/chain/todo 一致，写入侧也都按
    // 展开语义写），而本变量语义为「true=折叠」，读记忆恢复时必须取反——直接
    // `remembered ?: derived` 会把记忆倒置恢复：手动展开（存 true）重建后变折叠、
    // 手动折叠（存 false）重建后变展开（”切回会话后已处理卡片折叠态重置”根因）。
    // 生成中恒展开，且**不能**走记忆：本条消息可能在上一次完成/手动折叠时存了 false，
    // 若 init 先按记忆组合成折叠、随后被 effect 的”生成中强制展开”（else 分支）拉回
    // 展开，组合后高度突增会落在滚动锚点附近，触发下拉回弹（与完成折叠同理）。
    var chainCollapsed by remember(nodeId, autoCollapseAll) {
        val remembered = chainStateKey?.let { getSectionExpanded(it) }
        val derived = (autoCollapseAll && !loading && hasProcessContent)
        mutableStateOf(if (loading) false else remembered?.let { !it } ?: derived)
    }
    // 手动接管标记：用户手动点击过折叠卡后，本生成周期内自动折叠一律让位（手动优先）。
    // 取代旧方案 autoCollapseHold 的"手动接管"语义——hold 同时承担的"固化展开护重建"
    // 已随 codex 式时序取消（折叠在 loading 中落地即写 store，无"定稿瞬间展开残留"）。
    // 复位时机 = loading false→true 翻转（重新生成）：新周期自动折叠重新接管；
    // 完成时不复位，手动形态持久到周期结束（与 store 手动写入语义一致）。
    var manualOverride by remember(nodeId) { mutableStateOf(false) }
    LaunchedEffect(loading, messageFinishedAt) {
        // messageFinishedAt 守卫：发送新消息时 generationJob 先于新节点落库，旧末条
        // 已完成消息可能在竞态窗口内短暂吃到 loading=true，不得据此复位手动接管。
        if (loading && messageFinishedAt == null) manualOverride = false
    }
    // codex 式自动折叠（第一折叠时机，loading 中）：正文开始且过程区无进行中步骤 → 收卡，
    // 200ms 收起动画与正文首行并行（codex/ChatGPT 桌面端同节奏，对齐设置文案
    // "before the final answer is shown"）。相比旧"消息完成定稿折叠"，折叠窗口从
    // "生成刚结束、用户注意力高峰"挪到"正文流式增长期"，完成瞬间折叠与用户起手
    // 下拉的历史碰撞窗口（回弹主根因）结构性消失。
    // 折叠态此后**保持不动**（"保留前面的折叠态"）：折叠后模型再发起的工具/思考
    // 属于新 ThinkingBlock，按分组规则渲染在最终输出区（最后 ContentBlock 下方，
    // 即正文1 之下），不在折叠的过程区内——步骤与审批气泡天然可见，无需展开；
    // 曾为此设的"唤醒展开"已删：它在工具循环中反复展开/折叠过程区（真机反馈体验差），
    // 且审批可见性并不依赖它。待下一个正文块（正文2）出现时分组自动重排，正文1 与
    // 中间工具一并划回过程区，chainCollapsed 保持 true → 自动并入折叠卡，无需动作。
    // userControlled 暂缓自带重试：轮询等待（100ms）而非 snapshotFlow 订阅——
    // "350ms 用户滚动冷却窗过期"这类无触点事件的时刻没有 State 写入，订阅式等待
    // 可能永不唤醒；轮询读 isUserControlled（内部含冷却窗/闩锁判定）无此死角，
    // 用户停手最迟 ~100ms 内折叠落地。等待中本 effect 可被 key 翻转取消（loading/
    // processActive 变化），取消即放弃本次折叠，交由完成兜底接管。
    LaunchedEffect(autoCollapseAll, loading, hasProcessContent, finalOutputStarted, processActive) {
        if (autoCollapseAll && loading && hasProcessContent &&
            finalOutputStarted && !processActive && !chainCollapsed && !manualOverride
        ) {
            while (isUserControlled?.invoke() == true) {
                delay(100)
            }
            // 等待期间可能被手动接管抢先改变形态，复核后再折
            if (chainCollapsed || manualOverride || processActive) return@LaunchedEffect
            chainCollapsed = true
            chainStateKey?.let { setSectionExpanded(it, false) }
        }
    }
    // 完成兜底折叠（第二时机，极少数场景）：正文开始时机的折叠被 userControlled
    // 暂缓、且用户控制到消息完成的残余场景，在完成时再给一次机会。手动接管
    // （manualOverride）让位。旧 A/B/C 三分类定稿中"用户控制中→固化展开 + hold"
    // 分支已删：重建由 init 按开关推导折叠，与开关目标形态一致，无展开残留
    // （旧固化是为护"切走切回不塌缩"，新时序下折叠在 loading 中即落库，不存在该态）。
    var prevChainLoading by remember(nodeId) { mutableStateOf(loading) }
    LaunchedEffect(loading, autoCollapseAll, messageFinishedAt) {
        if (autoCollapseAll) {
            if (loading) {
                // 生成中强制展开（含重新生成场景）。
                // messageFinishedAt 守卫：竞态窗口内旧已完成消息短暂 loading=true 时
                // 不得强展——这是"发第二条时第一条过程闪展闪收"的直接触发点。
                if (messageFinishedAt == null) {
                    chainCollapsed = false
                }
            } else if (prevChainLoading && hasProcessContent && !chainCollapsed && !manualOverride) {
                // 仅"本组合内 loading 由 true 翻转为 false"（即刚生成完）才处理；
                // 历史消息下拉重建不算生成完成，不折叠、不落库（否则每条被看过的
                // 历史都会被记成折叠，破坏自动折叠的产品语义）。
                delay(AUTO_COLLAPSE_DELAY_MS)
                val viewportTopY = thinkingFreezeState?.topBarBottomY ?: Int.MAX_VALUE
                when {
                    // B) 过程区已完全滚出视口上方：瞬时无动画折叠（视口外，不可见），
                    // 落库折叠态，滚出重建后读记忆保持折叠卡。
                    finalOutputTopY <= viewportTopY -> {
                        chainCollapseAnimated = false
                        chainCollapsed = true
                        chainStateKey?.let { setSectionExpanded(it, false) }
                        // 无动画折叠已同帧落地，恢复标志让用户后续手动展开/收起保持平滑动画
                        withFrameNanos {}
                        chainCollapseAnimated = true
                    }
                    // C) 贴底观看：可见折叠，带动画；完成后即刻落库，此后重建读记忆保持折叠卡。
                    isChatListAtBottom?.invoke() == true -> {
                        chainCollapsed = true
                        chainStateKey?.let { setSectionExpanded(it, false) }
                    }
                    // 其余（用户控制中/可见非贴底）：不折叠不落库，留待下方补折叠 effect
                    // 在用户回到底部/过程区滚出视口/放手后落地。
                }
            }
        }
        prevChainLoading = loading
    }
    // "完成兜底被推迟"的补折叠：完成瞬间用户在操控或非贴底 → 折叠不落地。此 effect
    // 在消息仍组合、开关开启、无手动接管、尚未折叠时监视列表：一旦"贴底 或 过程区
    // 已滚出视口上方"且用户不再控制（含松手冷却与翻历史闩锁，见
    // LocalIsChatListUserControlled），延迟一帧余量后补折叠——保证开关语义下每条
    // 消息最终都会收卡。折叠发生在用户稳定后，不与他正在进行的滚动争夺锚点。
    LaunchedEffect(autoCollapseAll, loading, manualOverride, chainCollapsed) {
        if (autoCollapseAll && !loading && !manualOverride && !chainCollapsed && hasProcessContent) {
            val viewportTopY = thinkingFreezeState?.topBarBottomY ?: Int.MAX_VALUE
            // 轮询等待（100ms）：同触发 effect 注释——"冷却窗过期"这类无触点事件的
            // 时刻没有 State 写入，订阅式等待（snapshotFlow.first）可能永不唤醒，
            // 即"部分消息永不折叠"的隐性来源；轮询每次重读最终输出顶坐标，无此死角
            while (isUserControlled?.invoke() == true ||
                !(isChatListAtBottom?.invoke() == true || finalOutputTopY <= viewportTopY)
            ) {
                delay(100)
            }
            // 条件满足后让过一帧的边界抖动（列表刚回底的收尾），再复核一次才折叠
            delay(120)
            if (isUserControlled?.invoke() != true &&
                (isChatListAtBottom?.invoke() == true || finalOutputTopY <= viewportTopY)
            ) {
                if (finalOutputTopY <= viewportTopY) {
                    chainCollapseAnimated = false
                }
                chainCollapsed = true
                chainStateKey?.let { setSectionExpanded(it, false) }
                if (!chainCollapseAnimated) {
                    withFrameNanos {}
                    chainCollapseAnimated = true
                }
            }
        }
    }

    // 渲染单个块（思考链或内容块），过程区与最终输出区复用
    val renderBlock: @Composable (MessagePartBlock) -> Unit = { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 对齐上游：思维链卡片直接渲染，不做出现淡入动画。
                    // 此前用 AnimatedVisibility 淡入 200ms，生成结束后动画仍在进行，
                    // 卡片 item 高度在动画期间变化；用户停留片刻后下滑时，滚动 offset
                    // 按动画前高度计算，LazyColumn 重测布局产生视觉跳动（"生成完跳一下"）。
                    ChainOfThought(
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                        stateKey = LocalConversationId.current?.let { "chain:$it:$nodeId" },
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onApproveAllRelated = onApproveAllRelated,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> {
                key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexesCached(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                        )
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexesCached(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexesCached(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier.animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        val renderContent = @Composable {
                            if (loading) {
                                textContent()
                            } else {
                                SelectionContainer {
                                    textContent()
                                }
                            }
                        }

                        renderContent()
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
            }
        }
    }

    // 过程块（最终输出之前的思考链 + 中间输出）与最终输出块
    val processBlocks = if (finalOutputStart >= 0) groupedParts.subList(0, finalOutputStart) else groupedParts
    val finalBlocks = if (finalOutputStart >= 0) groupedParts.subList(finalOutputStart, groupedParts.size) else emptyList()

    // 消息内容区：折叠卡 + 过程区 + 最终输出，块间统一 4.dp 间距（与外层一致，避免气泡粘连）。
    // 注意：外层不做 animateContentSize——流式正文/思考卡片自带高度动画，再包一层会二次动画，
    // 流式结束产生额外跳动；"已处理"折叠用下方 AnimatedVisibility 的自包含高度动画即可。
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 折叠控制卡：有过程内容时始终显示（手动折叠/展开与开关无关），开关只决定完成后是否自动收起。
        // 普通布局下过程内容在卡片下方展开/收起，卡片本身顶部锚定不动。
        if (hasProcessContent) {
            Card(
                onClick = {
                    val willCollapse = !chainCollapsed
                    collapseAtBottom = willCollapse && (isChatListAtBottom?.invoke() == true)
                    chainCollapsed = willCollapse
                    // 记录用户手动选择的形态（true=展开，与思考步骤同语义）：滚出回收重建后保持所见形态。
                    // store 自动写入路径：手动点击此处、自动折叠落地（触发/完成兜底/补折叠）。
                    // 手动点击即置 manualOverride——本生成周期内自动折叠让位（手动优先级最高），
                    // 手动展开保持展开、手动折叠保持折叠；重新生成（loading 翻 true）时复位。
                    manualOverride = true
                    chainStateKey?.let { setSectionExpanded(it, !willCollapse) }
                    // 用户手动展开/收起过程区：通知列表取消自动跟随，避免高度骤增被拽到底部
                    onManualContentToggle?.invoke()
                },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (chainCollapsed) HugeIcons.ArrowDown01 else HugeIcons.ArrowUp01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = processedLabel ?: stringResource(R.string.chain_of_thought_show_all_steps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        // 过程内容折叠若发生在底部：等高度动画落定后重新贴底，抵消 scrollBack 上移
        LaunchedEffect(chainCollapsed) {
            if (chainCollapsed && collapseAtBottom) {
                delay(250)
                scrollChatToBottom?.invoke()
            }
        }

        // 过程内容：自包含高度动画（expand/shrink）+ 淡入淡出。只作用于本块，
        // 不影响外层/流式正文的高度动画；普通布局下内容在卡片下方自然展开。
        // 用短 tween 与内部 ChainOfThought / 工具步骤的动画同步，避免默认 spring 造成
        // 多层高度动画不同步的回弹/抖动（"工具完成/展开时轻微抖一下"根因）。
        // 生成中同样启用：codex 式触发（正文开始收卡）的 200ms 收起与正文首行并行，
        // 高度差被随后的正文流式增长逐步填补（旧"仅 !loading 启用"会让 loading 中
        // 折叠高度骤变无过渡，正文开始瞬间闪跳）。
        // chainCollapseAnimated=false：视口外自动折叠（完成兜底 B 分支/补折叠），跳过
        // 动画瞬时收起（可见动画会与用户下拉起手重叠，见上方 effect 注释）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (chainCollapseAnimated) Modifier.animateContentSize(animationSpec = tween(200))
                    else Modifier
                ),
            horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!chainCollapsed) {
                processBlocks.fastForEach { block -> renderBlock(block) }
            }
        }

        // 最终输出：始终显示。顶部窗口坐标供上方 effect 判定"过程区是否已完全
        // 滚出视口上方"（输出顶即过程区下边界 + 间距）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
                    finalOutputTopY = it.boundsInWindow.top
                },
            horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            finalBlocks.fastForEach { block -> renderBlock(block) }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                // file:// URI 不可外开，降级为不可点击
                                                val ann = if (annotation.url.startsWith("file://", ignoreCase = true)) {
                                                    LinkAnnotation.Clickable(tag = annotation.url, linkInteractionListener = null)
                                                } else {
                                                    LinkAnnotation.Url(annotation.url)
                                                }
                                                withLink(ann) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
}
