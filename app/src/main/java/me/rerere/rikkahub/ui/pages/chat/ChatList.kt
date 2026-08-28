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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
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
import me.rerere.rikkahub.ui.components.message.PresetMessagesIntro
import me.rerere.rikkahub.ui.components.message.LocalThinkingFreezeState
import me.rerere.rikkahub.ui.components.message.LocalOnManualContentToggle
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

// 用户触碰/滚动列表后的冷却窗口：窗口内自动跟随一律不发起。
// 封死两个竞态：手指已按下但尚未消费滚动（touch-slop 窗口）、手指抬起后
// 排队中的 scrollToItem 才执行（松手窗口）——"生成完后下滑查看上方消息被拽回/回弹"根因。
private const val USER_SCROLL_COOLDOWN_MS = 400L

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    // 用户手指是否正按在消息列表上（由本列表盒的 pointerInput 维护）：程序滚动
    // （自动跟随）在用户触碰列表期间绝不发起，避免拖拽中被拽回。
    isUserInteracting: MutableState<Boolean>? = null,
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
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
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
                isUserInteracting = isUserInteracting,
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
                onApproveAllRelated = onApproveAllRelated,
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
    isUserInteracting: MutableState<Boolean>? = null,
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
    onApproveAllRelated: ((toolCallId: String) -> Unit)? = null,
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
    // Keep the preset intro dismissed after the first real chat item is added.
    var presetIntroDismissed by rememberSaveable(conversation.id) { mutableStateOf(false) }
    // 用户最近一次触碰/滚动列表的时刻（elapsedRealtime）：跟随冷却与松手窗口共用。
    // 持久 remember：effect 因 loadingState 变化重启时不丢，避免冷却窗口被重启冲掉。
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    // 思考悬浮冻结条状态：折叠/展开的程序滚动与高度动画期间置 scrollingByProgram，抑制自动跟随抢滚
    val thinkingFreezeState = LocalThinkingFreezeState.current

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                // 音量上=往历史滚（普通布局负 delta）；reverseLayout 时代被翻转，这里还原。
                // 音量键滚动视为手动滚动：解除跟随，回底后由"稳定回底"重新武装。
                userScrolledUp = true
                lastUserScrollAt = SystemClock.elapsedRealtime()
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    // 触点冷却跟踪：手指按下/抬起都刷新"最近交互"时刻。
    // isScrollInProgress 要越过 touch-slop 才置位，覆盖不了"按下但未消费滚动"与
    // "抬起后排队滚动才执行"两个窗口；触点状态（down 即置位、up 即清除）在两边各刷新一次
    // 时刻，把这两个窗口一并封死（"生成完后下滑查看上方消息被拽回/回弹"根因）。
    LaunchedEffect(isUserInteracting?.value) {
        lastUserScrollAt = SystemClock.elapsedRealtime()
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
    // 新对话的预设消息只作为开场展示，不进入普通聊天消息列表；
    // 通过消息 id 对齐，避免内容相同但已被用户编辑的消息被误判为预设。
    // 不依赖 @Transient 的 newConversation 字段：该字段不入库，会话从 Room 重建后
    // 会丢失（恢复为 false），导致已开始的会话把预设消息误当成普通消息再次展示。
    val presetMessages = assistant?.presetMessages.orEmpty()
    val presetMessageCount = remember(conversation.messageNodes, presetMessages) {
        presetMessages.indices.takeWhile { index ->
            conversation.messageNodes.getOrNull(index)?.let { node ->
                node.messages.size == 1 && node.messages.firstOrNull()?.id == presetMessages[index].id
            } == true
        }.size
    }
    val hasPresetIntroItem = presetMessageCount > 0 && assistant != null
    val hasStartedConversation = conversation.messageNodes.size > presetMessageCount
    LaunchedEffect(conversation.id, hasStartedConversation) {
        if (hasStartedConversation) {
            presetIntroDismissed = true
        }
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
    val currentOnApproveAllRelated = rememberUpdatedState(onApproveAllRelated)
    val currentOnToolAnswer = rememberUpdatedState(onToolAnswer)
    val currentOnToggleFavorite = rememberUpdatedState(onToggleFavorite)
    val currentOnAssistantNameClick = rememberUpdatedState(onAssistantNameClick)
    val currentConversation = rememberUpdatedState(conversation)

    // 触点追踪（原在 ChatPage 列表盒上）：消息列表区域的任一下落都标记"用户正在操作列表"，
    // 全部抬起后清除。程序滚动（打开定位/自动跟随/发送贴底）据此在用户触碰期间绝不开抢——
    // isScrollInProgress 要到越过 touch slop 才置位，无法覆盖"手指已按下但尚未消费滚动"的竞态窗口。
    // 放在 ChatList 而非 ChatPage：悬浮吸顶条（冻结条）等列表外 UI 的点击不误判为列表操作。
    val interact = isUserInteracting
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (interact != null) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                interact.value = event.changes.any { it.pressed }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        // 仅当新增节点是用户消息（用户主动发送）时复位跟随闩锁：
        // 发送后应跟随新输出；生成中追加的助手/工具节点不复位，避免读历史时被重新武装拽回。
        LaunchedEffect(conversationUpdated.messageNodes.size) {
            if (conversationUpdated.messageNodes.lastOrNull()?.currentMessage?.role == MessageRole.USER) {
                userScrolledUp = false
            }
        }

        // 自动跟随：加载中且用户未主动上滑时，把列表保持在底部。
        // 状态机：用户主动上滑/跳离底部 → userScrolledUp=true 取消跟随；
        // 只有滚动稳定且回到真正底部 → userScrolledUp=false 恢复跟随（"稳定回底才重新武装"）。
        // 新内容追加使列表暂时离开底部不算"用户上滑"（否则第二条起永远不再跟随）。
        // 跟随用可取消的挂起 scrollToItem（effect 重启/新 emission 即取消在途滚动），
        // 不用 fire-and-forget 的 requestScrollToItem——它在滚动进行中会硬跳并把位置钉死，
        // 且会在列表内部作用域排一个手势结束后的补正协程（"闪到底又弹回"根因）。
        LaunchedEffect(state, loadingState, settings.displaySetting.enableAutoScroll, thinkingFreezeState) {
            if (settings.displaySetting.enableAutoScroll) {
                // 记录"上一次快照是否在滚动中"与"本次滚动是否由用户手指驱动"：
                // 用于在用户亲手滚回底部并停下（手势结束）时立刻重新武装跟随。
                // 若只用下方 2 的"冷却窗 + 稳定回底"，流式追加会在 400ms 窗内持续把
                // pinned 打成 false，导致"打断跟随后再手动滑到底部不会恢复跟随"。
                // 但也不能在任意"恰好贴底"帧复位——那会把内容突变造成的贴底误判成用户
                // 回底，下一帧就把正在看历史的用户拽回（"工具调用后下拉回弹"根因）。
                // 所以只在"一次由用户驱动的滚动手势刚结束、且当前确实贴底"时复位。
                var wasScrollInProgress = false
                var scrollWasUserDriven = false
                snapshotFlow {
                    Pair(
                        state.layoutInfo,
                        state.isScrollInProgress,
                    )
                }.collectLatest { (info, inProgress) ->
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@collectLatest
                    val pinned = isChatListPinnedToBottom(
                        totalItemsCount = info.totalItemsCount,
                        lastVisibleIndex = last.index,
                        lastItemEnd = last.offset + last.size,
                        viewportEnd = info.viewportEndOffset,
                        afterContentPadding = info.afterContentPadding,
                    )
                    val userTouching = isUserInteracting?.value == true
                    // 本次滚动过程中任一转场看到"手指按在列表上"即记为用户驱动。
                    // 抬起后 fling 仍算用户手势（isScrollInProgress 继续但手指已抬起）。
                    if (inProgress && userTouching) {
                        scrollWasUserDriven = true
                    }
                    // 手势结束 = 上一次快照在滚动、本次快照已停。用上一帧的 wasScrollInProgress，
                    // 并在读取后立刻更新，避免块在中途被 collectLatest 取消时丢状态。
                    val scrollEnded = wasScrollInProgress && !inProgress
                    wasScrollInProgress = inProgress
                    // 1) 解除跟随：用户手指按在列表上且列表正因手势滚动 → 立即解除。
                    //    - 不做方向判断（movedUp）：快速 fling / 与程序滚动重叠时 collectLatest
                    //      会丢中间帧，方向判定会漏闩 → 跟随重新武装 → 生成结束瞬间把正在看
                    //      历史的用户拽回底部（"回拉抽搐"根因）。任何用户手势滚动都算接管，
                    //      回到底部由下方"稳定回底"重新武装。
                    //    - 不判 scrollingByProgram：该标志只用于抑制"发起跟随"（下方 requestNow
                    //      的 folding），不能否定"用户此刻正在拖拽"的事实；用户拖拽时在途的
                    //      程序滚动会被 collectLatest 取消。
                    if (inProgress && userTouching) {
                        userScrolledUp = true
                        lastUserScrollAt = SystemClock.elapsedRealtime()
                    }
                    // 2) 滚动稳定且回到真正底部 → 恢复跟随。
                    //    冷却窗口内不复位：工具完成/内容突变可能让布局"恰好贴底"
                    //    （用户仍在上拉后的位置），立即复位会让跟随重新武装、下一帧
                    //    把列表拽回（"工具调用后下拉回弹"根因之一）。
                    val liveScrolling = state.isScrollInProgress
                    val settledAfterInteraction =
                        SystemClock.elapsedRealtime() - lastUserScrollAt >= USER_SCROLL_COOLDOWN_MS
                    if (!inProgress && !liveScrolling && pinned && settledAfterInteraction) {
                        userScrolledUp = false
                    }
                    // 2b) 用户驱动的滚动手势刚结束且正停在底部 → 立即恢复跟随（不等冷却窗）。
                    //     冷却窗本是为防"内容突变恰好贴底"的误复位；这里用"用户亲手滚回底部"
                    //     这一手势结束信号代替，非用户驱动（程序滚动/内容突变）不会走到这里，
                    //     因此不会把正在读历史的用户误武装。复位后刷新 lastUserScrollAt，
                    //     让下方跟随仍走冷却守护，避免在同帧抢滚。
                    if (scrollEnded && scrollWasUserDriven && !liveScrolling && pinned) {
                        userScrolledUp = false
                        lastUserScrollAt = SystemClock.elapsedRealtime()
                    }
                    // 滚动停止后清空用户驱动标记，避免上一个手势的标记串到下一次程序滚动；
                    // 放在任何挂起点之前，保证即使后续被 cancel 也已清空。
                    if (!inProgress) {
                        scrollWasUserDriven = false
                    }
                    // 思考折叠/展开的程序滚动与高度动画期间不跟随，避免抢滚
                    val folding = thinkingFreezeState?.scrollingByProgram == true
                    val shouldFollow = !userScrolledUp && loadingState && !pinned
                    // 触点守护：用户手指正按在列表上绝不跟随。isScrollInProgress 要越过
                    // touch-slop 后才置位，覆盖不了"手指已按下但尚未消费滚动"的帧级竞态；
                    // down 事件即置位的触点状态把这个窗口封死，杜绝在拖拽期间发起
                    // scrollToItem 排队到松手后执行（"生成完/切会话后下拉被拽回"根因）。
                    // 冷却守护：手指抬起后的松手窗口内同样不跟随——排队中的 scrollToItem
                    // 会在用户开始翻历史时执行把列表拽回（"生成完后下滑回弹抽搐"根因）。
                    val followCooledDown =
                        SystemClock.elapsedRealtime() - lastUserScrollAt >= USER_SCROLL_COOLDOWN_MS
                    val requestNow = !inProgress && !liveScrolling && !folding && shouldFollow &&
                        followCooledDown && (isUserInteracting?.value != true)
                    // 3) 跟随：仅当"生成中、用户未上滑、列表已离开底部"时发请求。
                    //    已钉底/正在滚动/思考折叠动画/用户触碰中都不发。发请求前用实时
                    //    isScrollInProgress 二次校验，挡住 snapshotFlow 的陈旧快照。
                    if (requestNow && !state.isScrollInProgress) {
                        // 跟随滚动期间置 scrollingByProgram：闩锁收集器据此不把程序滚动记作
                        // 用户交互（不置闩锁、不刷新 lastUserScrollAt），保证生成结束的自动折叠
                        // 不被"刚跟随过"误杀；collectLatest 取消在途滚动时 finally 复位。
                        thinkingFreezeState?.scrollingByProgram = true
                        try {
                            state.scrollToItem(info.totalItemsCount - 1)
                        } finally {
                            thinkingFreezeState?.scrollingByProgram = false
                        }
                    }
                }
            }
        }

        // 生成结束不再发任何滚动请求（38217976 语义：生成结束即停止跟随）。
        // 此前的"落定 effect"会在 loading 翻转后继续发 requestScrollToItem，落在用户
        // 刚起手的那一帧就把列表硬拽到底（"闪到底后弹回"），这里整体移除；
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
                // 用户手动展开/收起消息内可折叠内容（思考步骤/过程链/工具气泡等）时，
                // 在流式加载中把用户视为主动离开底部，取消自动跟随。展开/收起会改变 item 高度，
                // 若不暂停跟随，自动跟随会把列表硬拽到内容底部（"展开后突然跳到底部"根因）。
                // 仅在加载中抑制；生成结束后自动跟随本就停止，避免无谓地置位 userScrolledUp。
                LocalOnManualContentToggle provides {
                    if (loadingState) {
                        userScrolledUp = true
                        lastUserScrollAt = SystemClock.elapsedRealtime()
                    }
                },
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
            // 手动点"滚到底"按钮同样视为用户主动回到底部：清除上滑闩锁并刷新冷却，
            // 使流式生成能立即重新接管跟随（与手势回底一致），避免点击后仍停在原地。
            val reArmFollowOnJumpToBottom: () -> Unit = {
                userScrolledUp = false
                lastUserScrollAt = SystemClock.elapsedRealtime()
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
