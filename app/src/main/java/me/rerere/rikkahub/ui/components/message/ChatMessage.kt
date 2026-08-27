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

    // 消息级兜底：AI 生成完成时，若开启"自动折叠所有步骤"，强制折叠本消息全部工具调用气泡。
    // 流式中工具执行完成事件若与调用同批到达，step 级折叠可能漏触发，此处兜底保证一定生效。
    var prevLoading by remember(nodeId) { mutableStateOf(loading) }
    LaunchedEffect(loading, settings.displaySetting.autoCollapseAllSteps) {
        // 仅贴底且用户未在控制列表时自动折叠：折叠会使消息高度骤减（含视口上方的历史消息），
        // 用户在看历史/拖拽中触发会引发 LazyColumn 位置修正把列表吸回底部（"下拉被拽回"根因）；
        // 贴底时折叠由底部锚定吸收，视觉无跳变。与 reasoning 的 autoCloseThinking 守卫对齐。
        if (!loading && prevLoading && settings.displaySetting.autoCollapseAllSteps &&
            (isChatListAtBottom?.invoke() != false) && (isUserControlled?.invoke() != true)
        ) {
            withFrameNanos {}
            parts.forEach { part ->
                if (part is UIMessagePart.Tool && part.isExecuted) {
                    toolBubbleExpanded[part.toolCallId] = false
                }
            }
        }
        prevLoading = loading
    }

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
    // 整体折叠：开启开关且消息完成后，过程内容折叠成“已处理 n分m秒”卡片，只保留最终输出。
    // 初始形态优先读用户手动记录（true=展开）；无记录时按开关推导——自动折叠不落库，仅用户点击写入。
    var chainCollapsed by remember(nodeId, autoCollapseAll) {
        mutableStateOf(
            chainStateKey?.let { getSectionExpanded(it) }
                ?: (autoCollapseAll && !loading && hasProcessContent)
        )
    }
    // 开关开启时：生成中强制展开（含重新生成场景），完成后自动折叠；关闭时不干预，保留用户手动折叠状态。
    // 完成后折叠仅在列表贴底时执行：折叠会把本消息的过程内容收掉（高度骤减，含视口上方的历史消息），
    // 用户在看历史/拖拽中触发会引发 LazyColumn 位置修正（"下拉被拽回"根因）；贴底时由底部锚定吸收。
    LaunchedEffect(loading, autoCollapseAll) {
        if (autoCollapseAll) {
            if (!loading) {
                withFrameNanos {}
                // 用户在看历史/刚触碰过列表时同样暂缓整体折叠（见 isUserControlled 说明）
                if (hasProcessContent && isChatListAtBottom?.invoke() != false &&
                    isUserControlled?.invoke() != true
                ) {
                    chainCollapsed = true
                }
            } else {
                chainCollapsed = false
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!loading) Modifier.animateContentSize(animationSpec = tween(200)) else Modifier),
            horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!chainCollapsed) {
                processBlocks.fastForEach { block -> renderBlock(block) }
            }
        }

        // 最终输出：始终显示
        Column(
            modifier = Modifier.fillMaxWidth(),
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
