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
import androidx.compose.runtime.snapshotFlow
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
 * 生成完成到"自动折叠所有步骤"落地的延迟窗口。
 * 折叠本身绝不发生在可见区（可见高度变化与用户起手下拉重叠会抖动，历史多轮
 * 延迟+守卫方案均未根除），窗口只用于让过生成收尾的布局/动画（reasoning 收起、
 * 末块排版落定），窗口后仅在"过程区已完全滚出视口上方"时无动画瞬时折叠；
 * 过程区可见的消息保持展开，折叠推迟到滚出视口后的重建（init 按开关推导）。
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
    // 整体折叠：开启开关且消息完成后，过程内容折叠成“已处理 n分m秒”卡片，只保留最终输出。
    // 初始形态优先读进程级记忆；无记忆时按开关推导。
    // 写入时机：手动点击（下方 Card onClick）与下方 effect（过程区滚出视口后的视口外折叠），
    // 两者都只记录此时真正采取的形态，不记录中间过程。
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
    // 开关开启时：生成中强制展开（含重新生成场景）。完成后的自动折叠**不在可见区执行**：
    // 可见折叠是高度骤变，无论延迟多久、守卫多严，都存在"折叠动画窗口与用户起手下拉
    // 重叠"的碰撞窗口（"生成完后下拉跳动抽搐"根因，历史多轮延迟+守卫方案均未根除）。
    // 折叠只允许发生在过程区不可见时：
    // 1) 长答案（输出顶部已达列表视口顶，过程区完全在视口上方，即贴底满屏态）：
    //    延迟守卫窗口后立即无动画瞬时折叠——折叠只改视口外高度，贴底锚定同帧吸收，
    //    输出纹丝不动，视觉零变化；
    // 2) 过程区可见（短答案/用户上拉中）：保持展开，折叠推迟到本消息滚出视口销毁后的
    //    重建——上方 init 按开关推导折叠，item 以折叠尺寸首次组合（无初次高度动画、
    //    无锚点修正），零跳动。
    //    注意此路径**不落库**：可见区保持展开是"当前组合内"的形态，重建按开关推导折叠
    //    即"推迟折叠"的产品语义（用户开自动折叠=接受步骤事后收卡，切走切回同理收敛）。
    //    勿改成"完成即写 store=true"——那会让过程区可见期间的重建永远塌不了，
    //    也会把"滚出视口才折叠"的守卫语义架空（回归可见区瞬时塌缩/跳底，见
    //    docs/chat-session-view-state-plan.md 4.1-2）。
    var prevChainLoading by remember(nodeId) { mutableStateOf(loading) }
    LaunchedEffect(loading, autoCollapseAll) {
        if (autoCollapseAll) {
            if (loading) {
                // 生成中强制展开（含重新生成场景）
                chainCollapsed = false
            } else if (prevChainLoading && hasProcessContent) {
                // 仅"本组合内 loading 由 true 翻转为 false"（即刚生成完）才处理；
                // 历史消息下拉重建不算生成完成，不折叠、不落库（否则每条被看过的
                // 历史都会被记成折叠，破坏自动折叠的产品语义）。
                delay(AUTO_COLLAPSE_DELAY_MS)
                // 过程区已完全滚出视口上方（最终输出顶部不高于列表视口顶）且用户未控制
                // 列表才折叠；无动画（跳过 animateContentSize）把"折叠与起手重叠"的碰撞
                // 窗口从动画时长压到一帧以内。落库折叠态，滚出重建后读记忆保持折叠卡。
                val viewportTopY = thinkingFreezeState?.topBarBottomY ?: Int.MAX_VALUE
                if (finalOutputTopY <= viewportTopY &&
                    isChatListAtBottom?.invoke() != false &&
                    isUserControlled?.invoke() != true
                ) {
                    chainCollapseAnimated = false
                    chainCollapsed = true
                    chainStateKey?.let { setSectionExpanded(it, false) }
                    // 无动画折叠已同帧落地，恢复标志让用户后续手动展开/收起保持平滑动画
                    withFrameNanos {}
                    chainCollapseAnimated = true
                }
            }
        }
        prevChainLoading = loading
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
                    // 自动折叠的两个 LaunchedEffect 刻意不写 store（只改内存态），守卫/动画行为不受影响。
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
        // chainCollapseAnimated=false：完成时刻的视口外自动折叠，跳过动画瞬时收起
        // （可见动画会与用户下拉起手重叠，见上方 effect 注释）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!loading && chainCollapseAnimated) Modifier.animateContentSize(animationSpec = tween(200))
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
                                                withLink(LinkAnnotation.Url(annotation.url)) {
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
