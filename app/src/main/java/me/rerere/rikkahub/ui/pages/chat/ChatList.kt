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
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.ai.GenerationLiveStats
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.CompressedHistoryCard
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.PresetMessagesIntro
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
internal fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    // 滚动控制中枢（ChatPage 创建）：触点/手势/闩锁/时间戳的唯一持有者。
    // 本列表的触点追踪器与滚动主循环（ChatListScrollEffect）驱动它。
    scroller: ChatListScroller,
    loading: Boolean,
    processingStatus: String? = null,
    generationStats: GenerationLiveStats? = null,
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
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onAssistantNameClick: (() -> Unit)? = null,
    // 滚动位置上报：ChatList 已知"LazyColumn item ↔ 真实消息"的换算（intro 偏移），
    // 由它把当前视口锚点消息 + item index + offset 一并上报给 ChatPage 存入 ChatScrollStore。
    // 空实现/未传时不收集（预览等只读列表不产生存档）。
    onScrollSnapshot: ((anchorMessageId: Uuid?, index: Int, offset: Int) -> Unit)? = null,
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
                scroller = scroller,
                loading = loading,
                processingStatus = processingStatus,
                generationStats = generationStats,
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
                onApproveAllRelated = onApproveAllRelated,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
                onAssistantNameClick = onAssistantNameClick,
                onScrollSnapshot = onScrollSnapshot,
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
    scroller: ChatListScroller,
    loading: Boolean,
    processingStatus: String? = null,
    generationStats: GenerationLiveStats? = null,
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
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onAssistantNameClick: (() -> Unit)? = null,
    onScrollSnapshot: ((anchorMessageId: Uuid?, index: Int, offset: Int) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isRecentScroll by remember { mutableStateOf(false) }
    // Keep the preset intro dismissed after the first real chat item is added.
    var presetIntroDismissed by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    // 新消息入场动画锚点：晚于本页打开时刻创建的消息（用户发送 / AI 首条回复）才播放入场动画，
    // 更早的历史消息（含打开页面时仍在流的消息）滚动回看零动画。键 conversation.id，切会话重新锚定。
    // 不用"打开瞬间抓 id 快照"判定：历史消息在页面打开后仍可能异步加载，createdAt 早于开页时刻，
    // 永不误播，比快照集合更稳。
    val pageOpenedAt = remember(conversation.id) {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    }
    // 已播放入场的节点锁存：LazyColumn 滚动回收重建后不重播（父级持有，随会话切换整体重置）
    val entrancePlayed = remember(conversation.id) { mutableStateMapOf<Uuid, Boolean>() }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                // 音量上=往历史滚（普通布局负 delta）；reverseLayout 时代被翻转，这里还原。
                // 音量键滚动视为手动滚动：解除跟随，回底后由滚动主循环重新武装。
                scroller.followSuspended = true
                scroller.lastTouchAt = SystemClock.elapsedRealtime()
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    // 触点冷却跟踪（手指按下/抬起都刷新"最近交互"时刻）由 scroller.noteUserTouch 完成，
    // isScrollInProgress 要越过 touch-slop 才置位，覆盖不了"按下但未消费滚动"与
    // "抬起后排队滚动才执行"两个窗口；触点状态（down 即置位、up 即清除）把这两个窗口一并封死。

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
    // 新对话的预设消息只作为开场展示，不进入普通聊天消息列表；
    // 通过消息 id 对齐，避免内容相同但已被用户编辑的消息被误判为预设。
    // 不依赖 @Transient 的 newConversation 字段：该字段不入库，会话从 Room 重建后
    // 会丢失（恢复为 false），导致已开始的会话把预设消息误当成普通消息再次展示。
    val presetMessages = assistant?.presetMessages.orEmpty()
    // 换算与 ChatPage 滚动位置恢复共用 matchPresetMessageCount（见 ChatScrollUtils），
    // 避免"预设开场占一个 LazyColumn item"的口径两处分叉。
    val presetMessageCount = remember(conversation.messageNodes, presetMessages) {
        matchPresetMessageCount(conversation.messageNodes, presetMessages)
    }
    val hasPresetIntroItem = presetMessageCount > 0 && assistant != null
    val hasStartedConversation = conversation.messageNodes.size > presetMessageCount
    LaunchedEffect(conversation.id, hasStartedConversation) {
        if (hasStartedConversation) {
            presetIntroDismissed = true
        }
    }
    // 工作区图片解析器：AI 正文用 ![描述](/workspace/路径) 引用工作区图片时，
    // 结合当前会话绑定的 workspace 解析成沙箱内实际文件 Uri（createWorkspace 时 root = id）。
    // cwd 参与会话相对路径候选解析（AI 常相对 cwd 写图片路径），会话 cwd 变化时重建解析器与缓存
    val workspaceManager = koinInject<WorkspaceManager>()
    val workspaceImgResolver = remember(assistant, conversation.workspaceCwd) {
        workspaceImageResolver(workspaceManager, assistant?.workspaceId?.toString(), conversation.workspaceCwd)
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
    val currentOnApproveAllRelated = rememberUpdatedState(onApproveAllRelated)
    val currentOnToolAnswer = rememberUpdatedState(onToolAnswer)
    val currentOnToggleFavorite = rememberUpdatedState(onToggleFavorite)
    val currentOnAssistantNameClick = rememberUpdatedState(onAssistantNameClick)
    val currentConversation = rememberUpdatedState(conversation)

    // 触点追踪：消息列表区域的任一下落都标记"用户正在操作列表"，全部抬起后清除。
    // 程序滚动（打开定位/自动跟随/发送贴底）据此在用户触碰期间绝不开抢——
    // isScrollInProgress 只在滚动真正开始（越过 touch slop）后才为 true，帧级竞态下
    // 挡不住"手指已按下但尚未消费滚动"的窗口。放在 ChatList 而非 ChatPage：
    // 悬浮吸顶条（冻结条）等列表外 UI 的点击不误判为列表操作。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        scroller.noteUserTouch(event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        // 列表尺寸变化（新消息节点入列）：仅当新增节点是用户消息（用户主动发送）时
        // 复位跟随闩锁——发送后应跟随新输出；生成中追加的助手/工具节点不复位，避免
        // 读历史时被重新武装拽回。发送贴底请求也在此消费（performSend 置位标记）。
        LaunchedEffect(conversationUpdated.messageNodes.size) {
            if (conversationUpdated.messageNodes.lastOrNull()?.currentMessage?.role == MessageRole.USER) {
                scroller.followSuspended = false
            }
            scroller.consumeSendScroll()
        }

        // 滚动主循环：用户接管判定（自动折叠抑制）+ 自动跟随（生成中逐帧重锚贴底）。
        // 状态机与守卫语义见 ChatListScroller / ChatListScrollEffect。
        ChatListScrollEffect(
            scroller = scroller,
            autoScrollEnabled = settings.displaySetting.enableAutoScroll,
            loading = loading,
        )

        // 生成结束不再发任何滚动请求（38217976 语义：生成结束即停止跟随）。
        // 此前的"落定 effect"会在 loading 翻转后继续发 requestScrollToItem，落在用户
        // 刚起手的那一帧就把列表硬拽到底（"闪到底后弹回"），已整体移除；
        // 最后一块内容的可见性由跟随逻辑在流式最后一帧保证。
        // 滚动预取：提前在后台解析视口附近消息的 markdown/HTML/LaTeX 并写入进程级缓存，
        // 消息真正进入视口时 MarkdownBlock/MarkdownNew 命中缓存、不再主线程同步解析（快速滚动掉帧根因）。
        // 按"每跨过 PREFETCH_WINDOW 条才触发一次 + 取消上一次未完成任务"合并快速滚动时的并发任务，
        // 避免每个 firstVisibleItemIndex 变化都启动一个重任务挤占主线程/GC。
        LaunchedEffect(state) {
            var prefetchJob: Job? = null
            snapshotFlow { state.firstVisibleItemIndex / PREFETCH_WINDOW }
                .distinctUntilChanged()
                .collect {
                    val visibleIndexes = state.layoutInfo.visibleItemsInfo.map { it.index }
                    val size = conversationUpdated.messageNodes.size
                    if (size <= 0 || visibleIndexes.isEmpty()) return@collect
                    // 预设开场占用一个 LazyColumn item，后续消息下标需要扣除该偏移；
                    // 末尾的摘要、系统提示和底部占位项不对应消息，直接跳过。
                    val listItemOffset = if (hasPresetIntroItem) 1 else 0
                    fun origIndex(itemIdx: Int): Int? =
                        (itemIdx - listItemOffset).takeIf { it in 0 until size }
                    val messageIndexes = visibleIndexes.mapNotNull(::origIndex)
                    if (messageIndexes.isEmpty()) return@collect
                    val oFirst = messageIndexes.minOrNull() ?: return@collect
                    val oLast = messageIndexes.maxOrNull() ?: return@collect
                    val lo = (minOf(oFirst, oLast) - PREFETCH_BEHIND).coerceAtLeast(0)
                    val hi = (maxOf(oFirst, oLast) + PREFETCH_AHEAD).coerceAtMost(size)
                    if (lo >= hi) return@collect
                    val nodes = conversationUpdated.messageNodes.subList(lo, hi)
                    val prefetchAssistant = assistant
                    prefetchJob?.cancel()
                    prefetchJob = scope.launch(Dispatchers.Default) {
                        nodes.forEach { node ->
                            // 循环体全是纯 CPU 操作、无挂起点，协程取消是协作式的：
                            // 不检查 isActive 的话，被取消的 job 仍会把整批消息跑完，
                            // 快速 fling 时多批解析叠加造成 CPU/GC 尖峰（并可能争抢 LruCache 锁）
                            if (!isActive) return@launch
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

        // 滚动位置上报（锚点消息 id + LazyColumn item index + offset）：会话切换由
        // ChatPage 存进程级 ChatScrollStore、重进时恢复。原来在 ChatPage 侧用裸
        // firstVisibleItemIndex 订阅，拿不到"视口首条真实消息是哪条"——会话离开期间
        // 列表头增删（压缩/远端同步）后，恢复只能按同序号 index 落点、会漂到另一条消息。
        // 换算（预设开场 intro item 占一位）在本函数内唯一可知，故由这里上报锚点，
        // ChatPage 恢复时"锚点优先、index 兜底"。index/offset 保持 LazyColumn 原始
        // item 空间，恢复侧 scrollToItem 同空间直用。
        //
        // 锚点必须与 firstVisibleItemIndex 同 item：offset 是相对首可见 item 的，若锚点
        // 取"视口内第一条真实消息"（可能是第二、第三条可见 item），恢复时把该 offset
        // 套到锚点 item 上会整体偏移若干 item 高度（预设 intro 占满屏、摘要卡在视口
        // 上缘时即复现）。首可见 item 不是消息时锚点置 null，走 index 兜底。
        //
        // 空布局（visibleItemsInfo 为空）不上报：组合销毁/未完成首帧布局时 LazyColumn
        // 会短暂清空可见列表，此时上报 (null, index, 0) 会覆盖真实存档——离开会话
        // （cleanupChatPages 清栈 / 导航到工作区）再切回时位置漂移的根因之一。
        val currentScrollMapping by rememberUpdatedState(presetMessageCount to hasPresetIntroItem)
        LaunchedEffect(state, onScrollSnapshot) {
            val report = onScrollSnapshot ?: return@LaunchedEffect
            snapshotFlow {
                val info = state.layoutInfo
                if (info.visibleItemsInfo.isEmpty()) {
                    null
                } else {
                    val nodes = conversationUpdated.messageNodes
                    val (presetCount, hasIntro) = currentScrollMapping
                    val firstVisibleItemIndex = state.firstVisibleItemIndex
                    val firstVisible = info.visibleItemsInfo
                        .firstOrNull { it.index == firstVisibleItemIndex }
                        ?: info.visibleItemsInfo.minByOrNull { it.index }
                    val anchor = firstVisible?.let { item ->
                        chatItemMessageIndexOrNull(
                            itemIndex = item.index,
                            messageCount = nodes.size,
                            presetCount = presetCount,
                            hasPresetIntroItem = hasIntro,
                        )?.let { messageIndex -> nodes[messageIndex].id }
                    }
                    Triple(anchor, firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot != null) {
                        report(snapshot.first, snapshot.second, snapshot.third)
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
                // LocalOnManualContentToggle 由 ChatPageContent 提供（scroller.onManualContentToggle）：
                // 用户手动展开/收起消息内可折叠内容（思考/过程链/工具气泡）时处理列表跟随，
                // 语义见 ChatListScroller.onManualContentToggle。
            ) {
            LazyColumn(
                state = state,
                // 顶部让区走 contentPadding 而非视口裁剪：视口保持全高，
                // 消息可滚到顶栏后方参与背景模糊（与输入栏同款悬浮效果）
                contentPadding = PaddingValues(16.dp) + PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 32.dp + innerPadding.calculateBottomPadding(),
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
            ) {
            val showPresetMessages = !presetIntroDismissed &&
                presetMessageCount > 0 &&
                conversation.messageNodes.size == presetMessageCount
            val visibleMessageNodes = conversation.messageNodes.drop(presetMessageCount)

            if (hasPresetIntroItem) {
                item(key = "PresetMessagesIntro") {
                    // 开场时占满可视高度，让「头像 + 预设消息」在聊天界面正中显示；
                    // 开始对话（showPresetMessages=false）后整个 item 移除，不留下空白占位。
                    if (showPresetMessages) {
                        PresetMessagesIntro(
                            messages = conversation.messageNodes
                                .take(presetMessageCount)
                                .mapNotNull { it.messages.firstOrNull() },
                            assistant = assistant,
                            onAvatarClick = currentOnAssistantNameClick.value,
                            modifier = Modifier.fillParentMaxHeight(),
                        )
                    }
                }
            }

            itemsIndexed(
                items = visibleMessageNodes,
                key = { _, item -> item.id },
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
            ) { visibleIndex, node ->
                val index = visibleIndex + presetMessageCount
                // 新消息入场动画：晚于页面打开时刻创建的消息（用户发送 / AI 首条回复）
                // 播一次淡入+轻微上移；历史消息滚动回看零动画（createdAt 早于开页时刻）。
                // 根因：用户/AI 新消息行首帧即完整渲染，突然出现无过渡；AI 首条回复从首个
                // token 起组合，淡入与正文流式天然叠加（"气泡浮现→文字流出"）。
                // 形态取舍：graphicsLayer 只改绘制不改测量尺寸，不触发 LazyColumn 锚点/间距
                // 重排（避免滚动抖动）；Modifier 用 remember(node.id) 稳定化，不击穿 ChatMessage
                // 子树的 Compose 跳过链（滚动性能约束见上方 remember(node) 注释）。
                val isArrival = node.currentMessage.createdAt > pageOpenedAt
                val entranceAlpha = remember(node.id) {
                    Animatable(if (isArrival && entrancePlayed[node.id] != true) 0f else 1f)
                }
                LaunchedEffect(node.id) {
                    if (isArrival && entranceAlpha.value < 1f) {
                        // 先锁存再播放：滚动回收重建不重播
                        entrancePlayed[node.id] = true
                        entranceAlpha.animateTo(1f, tween(200))
                    }
                }
                val entranceModifier = remember(node.id, isArrival) {
                    if (isArrival) {
                        Modifier.graphicsLayer {
                            // alpha 在绘制期读取 Animatable，逐帧更新只重绘该层，不触发重组
                            val a = entranceAlpha.value
                            alpha = a
                            translationY = (1f - a) * 6.dp.toPx()
                        }
                    } else {
                        Modifier
                    }
                }
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
                        val approveAllCb: (String) -> Unit = remember(node) {
                            { id: String -> currentOnApproveAllRelated.value?.invoke(id) }
                        }
                        val toolAnswerCb: (String, String) -> Unit = remember(node) {
                            { id: String, answer: String -> currentOnToolAnswer.value?.invoke(id, answer) }
                        }
                        val assistantNameCb: () -> Unit = remember(node) {
                            { currentOnAssistantNameClick.value?.invoke() }
                        }
                        // loading 仅给「正在生成中的末条 assistant」：sendMessage/regenerate 的
                        // setJob 先于新节点落库，竞态窗口内旧末条 assistant 会短暂命中
                        // index==lastIndex。若不加 finishedAt/role 守卫，已完成消息会吃到
                        // loading=true → 过程区强制展开又折叠（"发第二条时第一条过程闪展闪收"）。
                        val currentMsg = node.currentMessage
                        val isGeneratingAssistant = loading &&
                            index == conversation.messageNodes.lastIndex &&
                            currentMsg.role == MessageRole.ASSISTANT &&
                            currentMsg.finishedAt == null
                        ChatMessage(
                            node = node,
                            modifier = entranceModifier,
                            model = currentMsg.modelId?.let(modelById::get),
                            assistant = assistant,
                            loading = isGeneratingAssistant,
                            generationStats = if (isGeneratingAssistant) generationStats else null,
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
                            onApproveAllRelated = approveAllCb,
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
            // 常驻（不随 loading 显隐）：生成结束不再插入新 item，避免 LazyColumn 锚点重排跳动
            if (assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
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

            // 加载指示器：优先显示在生成中 assistant 消息的操作栏位置（见 ChatMessage
            // 的 GeneratingLoadingRow）。仅当「尚无进行中的 assistant 消息」（首 token
            // 未到/纯处理态）或有 processingStatus（OCR/压缩等）时才悬浮在列表底部。
            val lastNode = conversation.messageNodes.lastOrNull()
            val lastMsg = lastNode?.currentMessage
            val hasGeneratingAssistant = loading &&
                lastMsg?.role == MessageRole.ASSISTANT &&
                lastMsg.finishedAt == null
            val showFloatingLoading = processingStatus != null || (loading && !hasGeneratingAssistant)
            AnimatedVisibility(
                visible = showFloatingLoading,
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
            // 手动点"滚到底"按钮同样视为用户主动回到底部：清除跟随闩锁并刷新触点冷却，
            // 使流式生成能立即重新接管跟随（与手势回底一致），避免点击后仍停在原地。
            val reArmFollowOnJumpToBottom: () -> Unit = {
                scroller.followSuspended = false
                scroller.lastTouchAt = SystemClock.elapsedRealtime()
            }
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state,
                onJumpToBottom = reArmFollowOnJumpToBottom,
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
    onJumpToBottom: () -> Unit = {},
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
                    hapticController.lightTap()
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
                    hapticController.lightTap()
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
                    hapticController.lightTap()
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
                    hapticController.lightTap()
                    // 用户主动滚到底：清除上滑闩锁，让流式生成重新接管跟随
                    onJumpToBottom()
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
