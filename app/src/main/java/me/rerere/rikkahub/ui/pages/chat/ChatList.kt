package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.CompressedHistoryCard
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.LocalThinkingFreezeState
import me.rerere.rikkahub.ui.components.message.warmMessageExtractions
import me.rerere.rikkahub.ui.components.richtext.warmMarkdownCache
import me.rerere.rikkahub.ui.components.richtext.warmMarkdownNewCache
import me.rerere.rikkahub.ui.components.richtext.LocalWorkspaceImageResolver
import me.rerere.rikkahub.ui.components.richtext.LocalOpenWorkspaceImagePreview
import me.rerere.rikkahub.ui.components.richtext.LocalOpenWorkspaceFile
import me.rerere.rikkahub.ui.components.richtext.workspaceImageResolver
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.message.LocalConversationId
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.utils.ToolParseCache
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val ScrollBottomKey = "ScrollBottomKey"

// 滚动预取窗口：跨过 PREFETCH_WINDOW 条索引才触发一次；前向预取 PREFETCH_AHEAD
// （对齐/超出 LazyColumn 的 WindowAwarePrefetchStrategy 组合预取，保证解析先于组合完成），后向 PREFETCH_BEHIND
const val PREFETCH_WINDOW = 8
const val PREFETCH_AHEAD = 20
const val PREFETCH_BEHIND = 8

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onAssistantNameClick: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
                onAssistantNameClick = onAssistantNameClick,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onAssistantNameClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    // 用户是否主动上滑离开底部：上滑置 true 取消跟随，滚回真正底部置 false 恢复跟随。
    // 持久 remember（而非局部变量），避免 LaunchedEffect 因 loadingState 变化重启时丢状态。
    var userScrolledUp by remember { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) scrollAmount else -scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    // 自动跟随键盘：普通布局下键盘弹起时把列表底部滚到可见
    ImeLazyListAutoScroller(lazyListState = state)

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    // workspace 图片/链接点击 → 应用内预览（ImagePreviewDialog）
    var workspacePreviewImage by remember { mutableStateOf<String?>(null) }

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    // 工作区图片解析器：AI 正文用 ![描述](/workspace/路径) 引用工作区图片时，
    // 结合当前会话绑定的 workspace 解析成沙箱内实际文件 Uri（createWorkspace 时 root = id）
    val workspaceManager = koinInject<WorkspaceManager>()
    val workspaceImgResolver = remember(assistant) {
        workspaceImageResolver(workspaceManager, assistant?.workspaceId?.toString())
    }
    val modelById = remember(settings.providers) {
        settings.providers
            .flatMap { it.models }
            .associateBy { it.id }
    }
    // 回调引用通过 rememberUpdatedState 捕获：item 层用 remember(node) 缓存稳定闭包，
    // 使 ChatMessage 全部参数在 node 不变时保持稳定引用，LazyColumn 可见 item 可被 Compose 跳过重组
    // （静态滚动时避免 250-350 节点/条的整棵子树重跑）。闭包内部通过 State 读最新引用，避免过期值 bug。
    val currentOnRegenerate = rememberUpdatedState(onRegenerate)
    val currentOnEdit = rememberUpdatedState(onEdit)
    val currentOnForkMessage = rememberUpdatedState(onForkMessage)
    val currentOnDelete = rememberUpdatedState(onDelete)
    val currentOnUpdateMessage = rememberUpdatedState(onUpdateMessage)
    val currentOnTranslate = rememberUpdatedState(onTranslate)
    val currentOnClearTranslation = rememberUpdatedState(onClearTranslation)
    val currentOnToolApproval = rememberUpdatedState(onToolApproval)
    val currentOnToolAnswer = rememberUpdatedState(onToolAnswer)
    val currentOnToggleFavorite = rememberUpdatedState(onToggleFavorite)
    val currentOnAssistantNameClick = rememberUpdatedState(onAssistantNameClick)
    val currentConversation = rememberUpdatedState(conversation)

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动跟随：加载中、最后一条未定稿且用户未主动上滑时，把列表保持在底部。
        // 新内容追加使列表暂时离开底部不算"用户上滑"（否则第二条起永远不再跟随），
        // 只有用户真正滚动向上才取消跟随；滚回底部或发送消息后自动恢复。
        LaunchedEffect(state, loadingState) {
            if (settings.displaySetting.enableAutoScroll) {
                var lastIdx = 0
                var lastOff = 0
                snapshotFlow {
                    Triple(
                        state.layoutInfo,
                        state.isScrollInProgress,
                        conversationUpdated.messageNodes.lastOrNull()?.currentMessage?.finishedAt,
                    )
                }.collect { (info, inProgress, lastFinishedAt) ->
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
                    val pinned = isChatListPinnedToBottom(
                        totalItemsCount = info.totalItemsCount,
                        lastVisibleIndex = last.index,
                        lastItemEnd = last.offset + last.size,
                        viewportEnd = info.viewportEndOffset,
                        afterContentPadding = info.afterContentPadding,
                    )
                    val idx = state.firstVisibleItemIndex
                    val off = state.firstVisibleItemScrollOffset
                    // 1) 用户滚动中上滑（看历史）→ 取消跟随
                    if (inProgress) {
                        val movedUp = idx < lastIdx || (idx == lastIdx && off < lastOff)
                        if (movedUp) userScrolledUp = true
                    }
                    // 2) 滚回真正底部 → 恢复跟随
                    if (!inProgress && pinned) {
                        userScrolledUp = false
                    }
                    lastIdx = idx
                    lastOff = off
                    // 3) 跟随：生成中、最后一条未定稿、用户未主动上滑
                    if (!inProgress && !userScrolledUp && loadingState && lastFinishedAt == null) {
                        state.requestScrollToItem(info.totalItemsCount - 1)
                    }
                }
            }
        }

        // 生成结束的一次性落定：若仍钉在底部，把最后内容滚到可见（用户已上滑则跳过）
        LaunchedEffect(loadingState) {
            if (!loadingState) {
                snapshotFlow { state.layoutInfo }
                    .first { it.totalItemsCount > 0 }
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
                if (isChatListPinnedToBottom(
                        totalItemsCount = info.totalItemsCount,
                        lastVisibleIndex = last.index,
                        lastItemEnd = last.offset + last.size,
                        viewportEnd = info.viewportEndOffset,
                        afterContentPadding = info.afterContentPadding,
                    )
                ) {
                    state.requestScrollToItem(info.totalItemsCount - 1)
                }
            }
        }

        // 滚动预取：提前在后台解析视口附近消息的 markdown/HTML/LaTeX 并写入进程级缓存，
        // 消息真正进入视口时 MarkdownBlock/MarkdownNew 命中缓存、不再主线程同步解析（快速滚动掉帧根因）。
        // 按"每跨过 PREFETCH_WINDOW 条才触发一次 + 取消上一次未完成任务"合并快速滚动时的并发任务，
        // 避免每个 firstVisibleItemIndex 变化都启动一个重任务挤占主线程/GC。
        LaunchedEffect(state) {
            var prefetchJob: Job? = null
            snapshotFlow { state.firstVisibleItemIndex / PREFETCH_WINDOW }
                .distinctUntilChanged()
                .collect {
                    val firstVisible = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@collect
                    val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
                    val size = conversationUpdated.messageNodes.size
                    if (size <= 0) return@collect
                    // 普通布局下消息 item index 即消息下标（底部固定项在列表末尾）
                    fun origIndex(itemIdx: Int): Int? =
                        if (itemIdx in 0 until size) itemIdx else null
                    val oFirst = origIndex(firstVisible) ?: return@collect
                    val oLast = origIndex(lastVisible) ?: return@collect
                    val lo = (minOf(oFirst, oLast) - PREFETCH_BEHIND).coerceAtLeast(0)
                    val hi = (maxOf(oFirst, oLast) + PREFETCH_AHEAD).coerceAtMost(size)
                    if (lo >= hi) return@collect
                    val nodes = conversationUpdated.messageNodes.subList(lo, hi)
                    val prefetchAssistant = assistant
                    prefetchJob?.cancel()
                    prefetchJob = scope.launch(Dispatchers.Default) {
                        nodes.forEach { node ->
                            val msg = node.currentMessage
                            val affectScope = if (msg.role == MessageRole.USER) {
                                AssistantAffectScope.USER
                            } else {
                                AssistantAffectScope.ASSISTANT
                            }
                            // 预热正文 markdown：渲染侧与预取侧都用 replaceRegexesCached 且 key 一致 → 首帧命中缓存；
                            // hasHtml 的结果继续预热 MarkdownNew 的 HTML 生成缓存
                            msg.parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                                val rendered = part.text.replaceRegexesCached(prefetchAssistant, affectScope, visual = true)
                            if (warmMarkdownCache(rendered)) warmMarkdownNewCache(rendered)
                            }
                            // 预热推理块 markdown（ChatMessageReasoningStep 渲染用，key 与渲染侧一致）
                            msg.parts.filterIsInstance<UIMessagePart.Reasoning>().forEach { part ->
                                warmMarkdownCache(
                                    part.reasoning.replaceRegexesCached(prefetchAssistant, affectScope, visual = true)
                                )
                            }
                            // 预热工具 output 解析 + 文件变更提取（写 ToolParseCache / 提取缓存）：
                            // 含工具的消息进入视口时命中缓存，不再主线程同步解析大 JSON（首帧掉帧根因）
                            val toolParts = msg.parts.filterIsInstance<UIMessagePart.Tool>()
                            if (toolParts.isNotEmpty()) {
                                val messageId = msg.id.toString()
                                toolParts.forEach { tool ->
                                    if (tool.isExecuted) ToolParseCache.toolOutputContent(tool)
                                }
                                warmMessageExtractions(messageId, msg.parts)
                            }
                        }
                    }
                }
        }

        // 判断最近是否滚动：滚动开始显示，delay 1500ms 后隐藏
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        ChatFontProvider(displaySetting = settings.displaySetting) {
            // preview lambda 用 remember 缓存为稳定引用：若每次重组新建，Markdown 段落 linkHandler
            // 会随引用变化而重建，破坏 annotatedString 的 remember 缓存（滚动性能）
            val openWsPreview = remember { { url: String -> workspacePreviewImage = url } }
            val navController = LocalNavController.current
            // 工作区文件链接（非图片 [名](/workspace/路径)）点击 → 应用内跳转：文件开编辑器，目录定位
            val openWorkspaceFile = remember(assistant, navController) {
                { dest: String ->
                    val workspaceId = assistant?.workspaceId?.toString()
                    if (workspaceId != null) {
                        val trimmed = dest.trimEnd('/')
                        val (area, relativePath) =
                            if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
                                WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
                            } else {
                                WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
                            }
                        if (relativePath.isBlank()) {
                            navController.navigate(Screen.WorkspaceDetail(workspaceId, area.name, ""))
                        } else {
                            navController.navigate(Screen.WorkspaceFileEditor(workspaceId, area.name, relativePath))
                        }
                    }
                }
            }
            CompositionLocalProvider(
                LocalConversationId provides conversation.id.toString(),
                LocalWorkspaceImageResolver provides workspaceImgResolver,
                LocalOpenWorkspaceImagePreview provides openWsPreview,
                LocalOpenWorkspaceFile provides openWorkspaceFile,
            ) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
                // 按消息形态分类，让 LazyColumn 槽位按形态复用组合/布局缓存
                // （含工具消息重子树：工具气泡 + 文件变更卡片，与纯文本消息形态差异大）
                contentType = { _, node ->
                    val parts = node.currentMessage.parts
                    when {
                        parts.any { it is UIMessagePart.Tool } -> "with_tool"
                        parts.isEmpty() -> "empty"
                        else -> "text"
                    }
                },
            ) { index, node ->
                ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        // 用 remember(node) 缓存稳定闭包，回调内部通过 rememberUpdatedState 读最新引用，
                        // 避免 item 每次重组都新建 lambda 击穿 Compose 跳过链（node 不变时 ChatMessage 整体跳过）
                        val regenCb = remember(node) { { currentOnRegenerate.value(node.currentMessage) } }
                        val editCb = remember(node) { { currentOnEdit.value(node.currentMessage) } }
                        val forkCb = remember(node) { { currentOnForkMessage.value(node.currentMessage) } }
                        val deleteCb = remember(node) { { currentOnDelete.value(node.currentMessage) } }
                        val updateCb = remember(node) { { it: MessageNode -> currentOnUpdateMessage.value(it) } }
                        val shareCb: () -> Unit = remember(node) {
                            {
                                selecting = true  // 使用 CoroutineScope 延迟状态更新
                                selectedItems.clear()
                                val nodes = currentConversation.value.messageNodes
                                selectedItems.addAll(nodes.map { it.id }
                                    .subList(0, nodes.indexOf(node) + 1))
                            }
                        }
                        val toggleFavCb: () -> Unit = remember(node) {
                            { currentOnToggleFavorite.value?.invoke(node) }
                        }
                        val translateCb: (UIMessage, java.util.Locale) -> Unit = remember(node) {
                            { msg: UIMessage, locale: java.util.Locale -> currentOnTranslate.value?.invoke(msg, locale) }
                        }
                        val toolApprovalCb: (String, Boolean, String) -> Unit = remember(node) {
                            { id: String, approved: Boolean, reason: String -> currentOnToolApproval.value?.invoke(id, approved, reason) }
                        }
                        val toolAnswerCb: (String, String) -> Unit = remember(node) {
                            { id: String, answer: String -> currentOnToolAnswer.value?.invoke(id, answer) }
                        }
                        val assistantNameCb: () -> Unit = remember(node) {
                            { currentOnAssistantNameClick.value?.invoke() }
                        }
                        ChatMessage(
                            node = node,
                            model = node.currentMessage.modelId?.let(modelById::get),
                            assistant = assistant,
                            loading = loading && index == conversation.messageNodes.lastIndex,
                            onRegenerate = regenCb,
                            onEdit = editCb,
                            onFork = forkCb,
                            onDelete = deleteCb,
                            onShare = shareCb,
                            onUpdate = updateCb,
                            isFavorite = node.isFavorite,
                            onToggleFavorite = toggleFavCb,
                            onTranslate = translateCb,
                            onClearTranslation = remember(node) { { msg: UIMessage -> currentOnClearTranslation.value(msg) } },
                            onToolApproval = toolApprovalCb,
                            onToolAnswer = toolAnswerCb,
                            lastMessage = index == conversation.messageNodes.lastIndex,
                            onAssistantNameClick = assistantNameCb,
                        )
                    }
            }

            conversation.compressedHistory
                ?.takeIf { it.summaryText.isNotBlank() }
                ?.let { history ->
                    item(key = "CompressedHistorySummary") {
                        CompressedHistoryCard(summary = history.summaryText)
                    }
                }
            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    ConversationSystemPromptButton(
                        customSystemPrompt = conversation.customSystemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                }
            }
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }

            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 加载指示器悬浮在列表底部上方，不占用 LazyColumn item 高度，
            // 避免生成结束后 44dp 常驻空白，也避免收尾时 item 高度变化引发锚点跳动
            AnimatedVisibility(
                visible = loading || processingStatus != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(2).dp)
                    .zIndex(4f),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RabbitLoadingIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    AnimatedVisibility(
                        visible = processingStatus != null,
                    ) {
                        Text(
                            text = processingStatus ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state,
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // workspace 图片/链接点击预览（resolveWorkspaceImage 解析成功后由预览入口触发）
            val previewUrl = workspacePreviewImage
            if (previewUrl != null) {
                ImagePreviewDialog(images = listOf(previewUrl)) {
                    workspacePreviewImage = null
                }
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color,
    textColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = textColor
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightTextColor = MaterialTheme.colorScheme.onTertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor,
                                    textColor = highlightTextColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
) {
    val hapticController = rememberHaptic()
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex + 1).coerceAtMost(
                                state.layoutInfo.totalItemsCount - 1
                            )
                        )
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
