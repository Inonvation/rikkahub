package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.input.pointer.pointerInput
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.registry.contextLengthOrDefault
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.SlidersVertical
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.ai.ContextCompositionStore
import me.rerere.rikkahub.data.ai.estimateFallbackComposition
import me.rerere.rikkahub.data.ai.hasRealMessages
import me.rerere.rikkahub.data.ai.hasStaleCalibrationAnchor
import me.rerere.rikkahub.data.ai.lastRealPromptTokens
import me.rerere.rikkahub.data.ai.tools.TodoItem
import me.rerere.rikkahub.data.ai.tools.TodoList
import me.rerere.rikkahub.data.ai.tools.TodoStatus
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.ai.tools.fingerprint
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ContextStatusOverlay
import me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet
import me.rerere.rikkahub.ui.components.ai.CompressContextDialog
import me.rerere.rikkahub.ui.components.ai.autoCompressResetThreshold
import me.rerere.rikkahub.ui.components.ai.autoCompressShouldTrigger
import me.rerere.rikkahub.utils.resolveContextTokenLimit
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.KnowledgeBaseChips
import me.rerere.rikkahub.ui.components.ai.ModePickerSheet
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.ai.completion.CommandCompletionProvider
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.PromptOptimizeSheet
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.message.getSectionExpanded
import me.rerere.rikkahub.ui.components.message.setSectionExpanded
import me.rerere.rikkahub.ui.components.message.trackRecentConversation
import me.rerere.rikkahub.ui.components.message.recentConversationIds
import me.rerere.rikkahub.ui.components.message.LocalThinkingFreezeState
import me.rerere.rikkahub.ui.components.message.LocalIsChatListAtBottom
import me.rerere.rikkahub.ui.components.message.LocalIsChatListUserControlled
import me.rerere.rikkahub.ui.components.message.LocalScrollChatToBottom
import me.rerere.rikkahub.ui.components.message.LocalScrollThinkingHeaderToPin
import me.rerere.rikkahub.ui.components.message.ThinkingFreezeState
import me.rerere.rikkahub.ui.components.message.ThinkingFrozenBar
import me.rerere.rikkahub.ui.components.ui.barHazeBlurStyle
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.ChatScrollStore
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

/** 会话切换时保留的最近会话数：更早会话的展开折叠记忆被回收，回落默认态 */
private const val KEEP_RECENT_CONVERSATIONS_FOR_EXPAND_STATE = 8

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null, mode: String? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString(), mode)
        }
    )
    val filesManager: FilesManager = koinInject()
    val appEventBus: AppEventBus = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    // 清理导航栈中旧的 Chat 页面，避免无限堆积
    LaunchedEffect(id) {
        navController.cleanupChatPages()
    }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.conversationErrors.collectAsStateWithLifecycle()

    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 侧边栏状态保存在 ChatVM 中，导航到子页面返回后仍保持展开
    var leftDrawerOpen by remember { mutableStateOf(vm.leftDrawerOpen) }
    var rightDrawerOpen by remember { mutableStateOf(vm.rightDrawerOpen) }

    // 双向同步到 ViewModel，导航离开/返回时状态保留
    LaunchedEffect(leftDrawerOpen) {
        vm.leftDrawerOpen = leftDrawerOpen
        // Clear input focus so popup transitions cannot reopen the keyboard.
        if (leftDrawerOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    LaunchedEffect(rightDrawerOpen) { vm.rightDrawerOpen = rightDrawerOpen }

    // Handle back press when left drawer is open
    BackHandler(enabled = leftDrawerOpen) {
        leftDrawerOpen = false
    }

    // Handle back press when right drawer is open
    BackHandler(enabled = rightDrawerOpen) {
        rightDrawerOpen = false
    }

    // 键盘唤起（IME 可见）时自动收回左右侧边栏，避免输入框被遮挡。
    // 只认"IME 稳定可见"这一状态，并用 200ms 二次确认抑制动画期间的抖动：
    // 手势打开侧栏会主动收起键盘，收起动画中 insets 从高→0 偶尔会中途抖动回升，
    // 若把这种瞬时 >0 当成"用户又调出输入法"，会把刚展开的侧栏误关。
    // 收集器常驻（不随侧栏开关重启），初始 prev 与当前状态对齐，杜绝重启竞态。
    // 用 WindowInsets.ime.getBottom() 检测 IME（isImeVisible 是 @Composable，无法在 snapshotFlow 中直接读）
    val imeInsets = WindowInsets.ime
    val imeDensity = LocalDensity.current
    LaunchedEffect(Unit) {
        var prevImeVisible = imeInsets.getBottom(imeDensity) > 0
        snapshotFlow { imeInsets.getBottom(imeDensity) }.collect { imeBottom ->
            val imeVisible = imeBottom > 0
            if (imeVisible && !prevImeVisible) {
                // IME 由隐藏 → 可见：可能是用户主动打开了输入法，延迟 200ms 再确认一次，
                // 确保 IME 真的稳定显示（而非收起动画中的瞬时抖动）后才收回侧栏
                delay(200)
                if (imeInsets.getBottom(imeDensity) > 0) {
                    if (leftDrawerOpen) leftDrawerOpen = false
                    if (rightDrawerOpen) rightDrawerOpen = false
                }
            }
            prevImeVisible = imeVisible
        }
    }

    val inputState = vm.inputState

    val hapticFeedback = LocalHapticFeedback.current

    // AI 消息生成开始时触发一次触感反馈
    LaunchedEffect(Unit) {
        appEventBus.events.collect { event ->
            if (event is AppEvent.ChatGenerationStarted && event.conversationId == id) {
                if (setting.displaySetting.enableHapticFeedback &&
                    setting.displaySetting.enableMessageGenerationStartedAndFinishedHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            }
        }
    }

    // AI 消息生成完成后触发一次触感反馈
    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { _ ->
            if (setting.displaySetting.enableHapticFeedback &&
                setting.displaySetting.enableMessageGenerationStartedAndFinishedHapticEffect) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    // 初始化输入状态（处理传入的 files 和 text 参数）。
    // 先等 VM 完成草稿恢复（inputReady）：分享/外部注入的预填内容优先级高于草稿，
    // 直接覆盖之；不等的话草稿异步恢复完成可能反过来覆盖预填内容（竞态丢字）
    LaunchedEffect(files, text) {
        vm.inputReady.first { it }
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    // 普通布局下首次组合就把滚动位置定位到最后一条消息的开头（切换历史会话的统一落点）。
    // 有会话级滚动位置存档（短暂记忆：切换会话时由 ChatScrollStore 保存）时优先恢复存档位置，
    // 否则落最后一条消息开头。若会话异步加载（首帧为空），这里先落在 0，数据到达后由下方
    // LaunchedEffect 补滚到目标位置（避免"先渲染顶部再滚动"的闪动）。
    val scrollStore = koinInject<ChatScrollStore>()
    val savedScroll = remember(conversation.id) { scrollStore.load(conversation.id) }
    // 预设开场 intro item 偏移换算（与 ChatList 同源，见 ChatScrollUtils）：消息下标
    // ≠ LazyColumn item index（intro 占一位）。放组合级供 remember 初值与下方恢复
    // effect 共用——两处若各按自己口径算，一处漏换算就整体偏一位。
    val presetAssistant = setting.getAssistantById(conversation.assistantId)
    val presetMessages = presetAssistant?.presetMessages.orEmpty()
    val presetCount = matchPresetMessageCount(conversation.messageNodes, presetMessages)
    val hasPresetIntroItem = presetCount > 0 && presetAssistant != null
    fun itemIndexOf(messageIndex: Int) =
        chatMessageItemIndex(messageIndex, presetCount, hasPresetIntroItem)
    val chatListState = remember(conversation.id) {
        val nodes = conversation.messageNodes
        val lastMessageIndex = nodes.lastIndex.coerceAtLeast(0)
        if (loadingJob?.isActive == true && nodes.isNotEmpty()) {
            // 生成中返回：存档是"离开时视口"快照，而离开期间生成持续、底部内容继续
            // 增长，快照必过期。以它为初值会先落"旧底部"，随后被恢复/自动跟随拽到
            // 当前底部——用户看到一次跳动（生成中离开→设置→返回即复现）。直接落
            // 当前最后一条消息（与跟随逻辑去向一致），首帧即贴当前底，无可感中间帧。
            LazyListState(
                firstVisibleItemIndex = itemIndexOf(lastMessageIndex),
                firstVisibleItemScrollOffset = 0,
            )
        } else {
            LazyListState(
                firstVisibleItemIndex = (savedScroll?.firstVisibleItemIndex
                    ?: itemIndexOf(lastMessageIndex))
                    .coerceIn(0, itemIndexOf(lastMessageIndex).coerceAtLeast(0)),
                firstVisibleItemScrollOffset = savedScroll?.firstVisibleItemScrollOffset ?: 0,
            )
        }
    }
    // 首次定位完成标记：保存 effect 等待它再开始记录滚动位置。
    // 否则会话数据加载前（首帧空列表）snapshotFlow 会把 (0,0) 写成假存档，
    // 恢复逻辑读到它就把会话钉在开头（"刚打开软件切历史会话落到最前"bug 根因）。
    var chatPositionReady by remember(conversation.id) { mutableStateOf(false) }
    // 滚动位置存档改由 ChatList 上报（见 ChatList onScrollSnapshot / ChatScrollStore
    // 锚点注释）：只有列表内部知道"LazyColumn item ↔ 真实消息"的换算（预设开场 intro
    // item 占一位），裸 firstVisibleItemIndex 拿不到视口锚点消息 id——会话离开期间列表
    // 头部若增删（上下文压缩/远端同步），恢复按同序号 index 会漂到另一条消息上。
    // 门控保持原语义：chatPositionReady（首次定位完成）前丢弃上报，防止首帧空列表把
    // (0,0) 写成假存档覆盖真实位置；回调按 conversation.id remember，切会话重建后
    // 不会把其他会话的位置串写进来。程序滚动（定位/自动跟随）产生的中间位置同样
    // 上报，语义 = "离开时停留在哪就回到哪"。
    val onScrollSnapshot: (Uuid?, Int, Int) -> Unit = remember(conversation.id) {
        { anchor, index, offset ->
            if (chatPositionReady) {
                scrollStore.save(conversation.id, index, offset, anchor)
            }
        }
    }
    // 用户手指是否正按在消息列表区域（由 ChatList 列表盒的 pointerInput 实时维护）。
    // 所有"被动/后台"程序滚动（打开定位、自动跟随、发送贴底）在用户触碰列表期间一律不发起，
    // 避免拖拽途中/松手瞬间被程序滚动拽回（"闪到底又弹回"的另一支根因）：
    // isScrollInProgress 只在滚动真正开始（越过 touch slop）后才为 true，帧级竞态下挡不住"手指已
    // 按下但尚未消费滚动"的窗口；触点状态在 down 事件即置位，把该窗口一并封死。
    val listInteracting = remember { mutableStateOf(false) }
    DisposableEffect(conversation.id) {
        onDispose {
            vm.chatListInitialized = false
        }
    }
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            suspend fun waitForFirstLayout(): Boolean =
                withTimeoutOrNull(1_000) {
                    snapshotFlow { chatListState.layoutInfo.totalItemsCount }
                        .first { it > 0 }
                } != null
            if (!waitForFirstLayout()) {
                vm.chatListInitialized = true
                chatPositionReady = true
                return@LaunchedEffect
            }
            // 用户已触碰列表或滚动进行中 → 放弃定位。scrollToItem 是挂起调用，若在这里排队
            // 会在用户松手后立即执行把列表拽回（忽略即可，绝不发未判定的滚动请求）。
            // itemIndexOf / presetCount 换算来自组合级定义（见 chatListState 初始化处），
            // effect 内只按重启时最新的 messageNodes 重取 nodes / lastMessageIndex。
            val canScroll = !listInteracting.value && !chatListState.isScrollInProgress
            val nodes = conversation.messageNodes
            val lastMessageIndex = nodes.lastIndex.coerceAtLeast(0)
            if (nodeId != null) {
                // 指定消息跳转（收藏/搜索）：对齐该消息开头
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0 && canScroll) {
                    chatListState.scrollToItem(itemIndexOf(index))
                }
            } else {
                // 历史会话打开：有存档位置则恢复（短暂记忆），否则定位到最后一条消息的开头
                // （视口顶部 = 最后一条消息起始）。存档索引钳制到当前消息数（离开期间可能有
                // 新消息追加/删除），offset 交给 LazyColumn 布局钳制。
                // 用可取消的挂起 scrollToItem 而非 requestScrollToItem（后者滚动中会硬跳并钉死）。
                // 不贴"列表底部"：贴底依赖"内容总高"，而视口上方条目在打开时尚未测量，
                // 内容总高按估算值算会落偏，用户上拉时条目首次真实测量变高触发 LazyColumn
                // 锚点修正，把列表往下拽（"下拉被拽回"根因）。定位到最后一条消息开头只需
                // 把锚点条目放到视口顶部，不依赖总高，机制上无此问题；恢复存档位置同理
                // （index + offset 是视口锚点描述，不依赖内容总高）。
                if (canScroll) {
                    if (loadingJob?.isActive == true) {
                        // 生成中返回：跳过存档恢复。存档是"离开时视口"快照，离开期间生成
                        // 持续、底部内容继续增长，快照相对当前底部必过期——若恢复它，列表
                        // 先停"旧底部"，随后被自动跟随（userScrolledUp 重建后复位武装）拽
                        // 到当前底部，产生一次可见跳动。生成中一律直接落当前最后一条，
                        // 与跟随去向同点，首帧即贴当前底、零中间帧；生成结束（isActive
                        // 翻转）后走下方正常恢复分支，产品语义不变。
                        chatListState.scrollToItem(itemIndexOf(lastMessageIndex))
                    } else {
                        val saved = scrollStore.load(conversation.id)
                        // 锚点优先：离开时的视口首条真实消息仍在列表（头部未被压缩/删除）
                        // → 精确钉回该消息当前位置；锚点失效（消息已被删/压缩合并）→ 回落
                        // 旧逻辑按存档 index 恢复（item 空间直用）；都不可用 → 定位最后一条。
                        val anchorTarget = saved?.anchorMessageId?.let { anchorId ->
                            nodes.indexOfFirst { it.id == anchorId }
                                .takeIf { it in presetCount..lastMessageIndex }
                                ?.let(::itemIndexOf)
                        }
                        if (anchorTarget != null) {
                            chatListState.scrollToItem(
                                anchorTarget,
                                saved.firstVisibleItemScrollOffset.coerceAtLeast(0),
                            )
                        } else if (saved != null &&
                            saved.firstVisibleItemIndex in 0..itemIndexOf(lastMessageIndex)
                        ) {
                            chatListState.scrollToItem(
                                saved.firstVisibleItemIndex,
                                saved.firstVisibleItemScrollOffset,
                            )
                        } else {
                            chatListState.scrollToItem(
                                if (nodes.size > presetCount) itemIndexOf(lastMessageIndex) else 0
                            )
                        }
                    }
                }
            }
            // 无论是否被用户互动打断都视为已初始化：定位只做一次，不做补偿重试
            // （否则加载稍慢时会反复尝试滚动，撞上用户手势概率更高）。
            vm.chatListInitialized = true
            // 首次定位（或放弃定位）完成，允许保存 effect 开始记录真实滚动位置
            chatPositionReady = true
        }
    }

    DrawerScaffold(
        leftDrawerOpen = leftDrawerOpen,
        rightDrawerOpen = rightDrawerOpen,
        onLeftDrawerOpenChange = { leftDrawerOpen = it },
        onRightDrawerOpenChange = { rightDrawerOpen = it },
        leftDrawer = {
            ChatDrawerContent(
                navController = navController,
                current = conversation,
                vm = vm,
                settings = setting
            )
        },
        rightDrawer = {
            RightDrawerContent(
                navController = navController,
            )
        },
    ) {
        ChatPageContent(
            inputState = inputState,
            loadingJob = loadingJob,
            processingStatus = processingStatus,
            setting = setting,
            conversation = conversation,
            leftDrawerOpen = leftDrawerOpen,
            onLeftDrawerOpenChange = { leftDrawerOpen = it },
            navController = navController,
            vm = vm,
            chatListState = chatListState,
            listInteracting = listInteracting,
            enableWebSearch = enableWebSearch,
            currentChatModel = currentChatModel,
            errors = errors,
            onDismissError = { vm.dismissError(it) },
            onClearAllErrors = { vm.clearAllErrors() },
            onScrollSnapshot = onScrollSnapshot,
        )
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    conversation: Conversation,
    leftDrawerOpen: Boolean,
    onLeftDrawerOpenChange: (Boolean) -> Unit,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    listInteracting: MutableState<Boolean>,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onScrollSnapshot: ((Uuid?, Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingSendScroll by remember(conversation.id) { mutableStateOf(false) }

    fun scrollAfterSend() {
        pendingSendScroll = true
    }

    // 记录用户最近一次触碰/滚动的时刻（elapsedRealtime）：贴底/发送贴底在阈值内主动放弃，
    // 兜底防止"刚上拉就被贴底/被拽回"的竞态。
    // 只在闩锁收集器里由"真实用户手势"（inProgress 且非程序滚动）刷新：程序跟随滚动会置
    // scrollingByProgram（见 ChatList 跟随逻辑），不会误记为用户交互——否则生成结束前最后
    // 一次跟随会把自动折叠的"用户未控制列表"判断误杀（折叠被跳过）。纯点击（无滚动）不刷新，
    // 保证"点折叠卡→重贴底"的既有行为不被 350ms 窗口误伤。
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }

    // 发送后贴底：等新消息节点真正进入列表（size 变化）再滚到底（index 越界会钳制到末尾）。
    // 用 requestScrollToItem 而非 animateScrollToItem，避免与流式布局/用户手势抢动画帧。
    LaunchedEffect(conversation.messageNodes.size) {
        if (pendingSendScroll && conversation.messageNodes.isNotEmpty()) {
            pendingSendScroll = false
            // 发送瞬间用户可能仍在 fling/拖拽：requestScrollToItem 在滚动进行中会把列表
            // 立即拽到底部（Compose foundation 1.12 行为），跳过本次 snap，由自动跟随接管。
            // 触点守护：用户手指正按在列表上时不发起，避免"发送后正在翻历史被拽回"。
            // 冷却守护：最近 350ms 内刚触碰/滚动过列表也不发起——松手窗口内排队滚动会在
            // 用户翻历史时执行把列表拽回；新用户消息已复位自动跟随闩锁，由跟随接管贴底。
            val recentlyTouched =
                android.os.SystemClock.elapsedRealtime() - lastUserScrollAt < 350L
            if (!chatListState.isScrollInProgress && !listInteracting.value && !recentlyTouched) {
                // 用真实最后一项索引：越界 +10 会先落到 bogus 索引再重锚，产生位置闪变
                val target = (chatListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                chatListState.requestScrollToItem(target)
            }
        }
    }

    val toaster = LocalToaster.current

    val workspaceRepository: WorkspaceRepository = koinInject()
    val todoStorage: TodoStorage = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    // 点击助手名称弹出助手选择器（切换助手后新开聊天窗口）
    var showAssistantPicker by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    // 上下文状态浮窗（页内覆盖层，见 ContextStatusOverlay）：
    // 状态与唯一 toggle 入口都收敛在这里，配合消抖门闩杜绝连点闪烁——
    // 旧 Popup 实现里锚点点击会同时触发 Popup dismiss 与图标 toggle 两路写回，
    // 快速连点时延迟 dismiss 会落在重新打开之后把浮窗关掉（闪烁根因）；
    // 页内覆盖层由全窗点击拦截层 + 图标 toggle 单一驱动，300ms 内连点只响应第一次。
    var showContextPopover by remember { mutableStateOf(false) }
    var lastContextPopoverToggleAt by remember { mutableLongStateOf(0L) }
    val contextPopoverTransition = remember { MutableTransitionState(false) }
    // 锚点圆圈底边在窗口坐标系的 y：面板顶部对齐图标底边（与 Popup 版 y = anchorHeight 同口径）
    var contextAnchorBottomPx by remember { mutableIntStateOf(0) }
    fun toggleContextPopover() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastContextPopoverToggleAt < 300L) return
        lastContextPopoverToggleAt = now
        showContextPopover = !showContextPopover
    }
    fun dismissContextPopover() {
        if (!showContextPopover) return
        lastContextPopoverToggleAt = android.os.SystemClock.elapsedRealtime()
        showContextPopover = false
    }
    // 思考冻结栏：折叠按钮被顶栏遮住时，在顶栏下方悬浮显示便于折叠
    val thinkingFreezeState = remember { ThinkingFreezeState() }

    // 展开折叠进程级缓存（sectionExpanded / toolBubbleExpanded）的生命周期治理：
    // 切换会话时只保留最近 N 个会话的记忆，避免长会话 + 多会话切换下只增不减的慢性累积。
    // 最近记录是进程级单例（trackRecentConversation 内部维护），跨 ChatPage 导航实例累积：
    // 本页按会话切换导航重建（cleanupChatPages 清栈），实例级 remember 队列每次只剩
    // 当前会话，会把其它会话的记忆清空（"切走再切回折叠态重置"根因），故不放这里。
    // GroupDiscussion/SubAgent 详情页等使用独立会话 id 的页面，其记忆会在主聊天
    // 再切换数轮后被回收（回落默认展开态），可接受。
    // 滚动存档治理的 store 引用：ChatScrollStore 是 koin single（AppModule.kt:89），
    // 此注入与顶层 ChatPage 的 scrollStore 同实例。koinInject 是 @Composable 函数，
    // 只能在组合作用域调用、不能进 LaunchedEffect 协程体，故提到这里取值、effect 内引用。
    val scrollPruneStore = koinInject<ChatScrollStore>()
    LaunchedEffect(conversation.id) {
        trackRecentConversation(
            conversation.id.toString(),
            KEEP_RECENT_CONVERSATIONS_FOR_EXPAND_STATE,
        )
        // 滚动存档与展开折叠记忆同口径治理：只保留最近 N 会话（含当前）的滚动位置。
        // 展开折叠记忆在 trackRecentConversation 内回收（pruneSectionExpanded /
        // pruneToolBubbleExpanded），滚动存档属 ChatScrollStore 管辖、不在此文件，
        // 故由调用方借进程级最近访问队列对齐回收——否则切换超过 N 个会话后，较早会话的
        // 折叠态已回落默认、滚动位置却仍残留，形成两套记忆不同步的慢性累积。
        scrollPruneStore.prune(
            recentConversationIds()
                .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
                .toSet()
        )
    }

    // 用户手动滚动闩锁：折叠思考/过程内容后的延迟贴底，只在用户没有滑离底部时执行。
    // 用户在贴底等待窗口内开始滚动 → 置位，贴底直接放弃，避免"看历史时被拽回底部"；
    // 列表稳定回到底部后复位，让下一次折叠重新武装。
    // 关键：用户手指按在列表上（listInteracting，触点即置位）时也要置位——它覆盖"手指已按下
    // 但尚未消费滚动"的窗口，且不受 programScroll（折叠动画的程序滚动）抑制：
    // 否则折叠动画期间用户上拉会被当成"程序滚动"而漏掉，折叠后的延迟贴底仍会拽回（历史回跳根因）。
    var userScrolledLatch by remember { mutableStateOf(false) }
    LaunchedEffect(chatListState, thinkingFreezeState, listInteracting) {
        snapshotFlow {
            val info = chatListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val pinned = last != null && isChatListPinnedToBottom(
                totalItemsCount = info.totalItemsCount,
                lastVisibleIndex = last.index,
                lastItemEnd = last.offset + last.size,
                viewportEnd = info.viewportEndOffset,
                afterContentPadding = info.afterContentPadding,
            )
            Triple(
                chatListState.isScrollInProgress,
                pinned,
                thinkingFreezeState.scrollingByProgram,
            )
        }.collect { (inProgress, pinned, programScroll) ->
            val userInteracting = listInteracting.value
            if (userInteracting || (inProgress && !programScroll)) {
                // 用户手指在列表上，或用户手势已开始（非程序滚动）→ 本次折叠不再贴底。
                // 仅用户手势刷新 lastUserScrollAt：程序跟随滚动会置 scrollingByProgram，
                // 走不到这个分支，不会把"刚跟随完"误当成用户在看历史（生成结束自动折叠不被跳过）。
                userScrolledLatch = true
                lastUserScrollAt = android.os.SystemClock.elapsedRealtime()
            } else if (!inProgress && pinned && !userInteracting) {
                // 列表稳定回到底部、且用户未触碰 → 复位闩锁
                userScrolledLatch = false
            }
        }
    }
    val assistant = setting.getCurrentAssistant()
    val tokenStats = computeTokenStats(conversation, setting, generating = loadingJob != null)
    val effectiveContextTokenLimit = tokenStats.contextTokenLimit
    val totalTokens = tokenStats.totalTokens
    val usagePercent = tokenStats.usagePercent
    // 订阅本会话 todo 状态（TodoStorage 是唯一状态源，写入时实时刷新 banner）
    val todolist by todoStorage.loadAsFlow(conversation.id.toString())
        .collectAsStateWithLifecycle(initialValue = null)
    var showFilesSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var autoCompressPrompted by remember(conversation.id) { mutableStateOf(false) }
    var showPromptOptimizeSheet by remember { mutableStateOf(false) }
    val promptOptimizeVM: PromptOptimizeVM = koinViewModel()

    // 排队中的引导消息列表（生成中发送后以独立气泡显示在输入框上方右对齐，等 AI 回合结束依次注入）
    val pendingGuidance by vm.pendingGuidance.collectAsStateWithLifecycle()
    // 排队中的待发送消息（生成中发附件/仅追加消息时显示卡片，等 AI 回合结束依次发送）
    val pendingSends by vm.pendingSends.collectAsStateWithLifecycle()

    // 当前会话活跃子代理任务数（运行时输入框左侧显示子代理图标 + 数量角标）。
    // distinctUntilChanged：只在计数变化时通知（0→N→0），子代理流式更新期间
    // 计数稳定，避免每次流式 chunk 都触发整个 ChatPageContent 重组（发烫/掉帧来源）。
    val subAgentRunner: SubAgentRunner = koinInject()
    val subAgentActiveCount by subAgentRunner.tasksFlow
        .map { map ->
            map.values.count {
                it.parentConversationId == conversation.id &&
                    (it.status == SubAgentStatus.QUEUED || it.status == SubAgentStatus.RUNNING)
            }
        }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = 0)
    val effectiveProcessingStatus = processingStatus
        ?: if (subAgentActiveCount > 0) "等待子代理完成任务…" else null

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        buildList {
            // 斜杠命令补全(/init + 子代理命令), 始终可用
            add(CommandCompletionProvider())
            // 工作区路径补全(@), 绑定工作区时可用
            assistant.workspaceId?.let { workspaceId ->
                add(
                    WorkspaceCompletionProvider(
                        workspaceId = workspaceId.toString(),
                        repository = workspaceRepository,
                        currentCwd = conversation.workspaceCwd,
                    )
                )
            }
        }
    }

    // 统一发送入口：普通发送（appendOnly=false）与长按仅追加（appendOnly=true）共用一份
    // 分支逻辑，避免两处粘贴的 send/编辑/生成中引导队列代码日后分叉不一致。
    fun performSend(appendOnly: Boolean) {
        if (!appendOnly && currentChatModel == null) {
            toaster.show("请先选择模型", type = ToastType.Error)
            return
        }
        if (inputState.isEditing()) {
            vm.handleMessageEdit(
                parts = inputState.getContents(),
                messageId = inputState.editingMessage!!,
            )
        } else {
            todolist
                ?.takeIf { it.items.isNotEmpty() && it.items.all { item ->
                    item.status == TodoStatus.completed || item.status == TodoStatus.cancelled
                } }
                ?.let { completedTodo ->
                    todoStorage.saveDismissedFingerprint(
                        conversation.id.toString(),
                        completedTodo.fingerprint(),
                    )
                }
            if (loadingJob?.isActive == true || subAgentActiveCount > 0) {
                if (appendOnly) {
                    vm.sendMessageQueued(inputState.getContents(), answer = false)
                } else {
                    // 生成中发送统一走引导通道（文本+附件均支持，作为真实用户消息注入）；
                    // 已有排队引导时打断并立即发送新引导
                    val contents = inputState.getContents()
                    if (pendingGuidance.isNotEmpty()) {
                        vm.sendGuidanceInterrupt(contents)
                    } else {
                        vm.sendGuidance(contents)
                    }
                }
                scrollAfterSend()
            } else {
                vm.sendMessageQueued(inputState.getContents(), answer = !appendOnly)
                scrollAfterSend()
            }
        }
        inputState.clearInput()
        vm.clearDraft()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    LaunchedEffect(
        conversation.id,
        conversation.version,
        setting.autoCompressEnabled,
        setting.autoCompressThreshold,
        loadingJob?.isActive,
        usagePercent,
    ) {
        if (autoCompressShouldTrigger(
                totalTokens = totalTokens,
                contextTokenLimit = effectiveContextTokenLimit,
                thresholdPercent = setting.autoCompressThreshold,
                enabled = setting.autoCompressEnabled,
            ) && loadingJob?.isActive != true && !autoCompressPrompted
        ) {
            autoCompressPrompted = true
            vm.handleCompressContext(
                additionalPrompt = "",
                targetTokens = (effectiveContextTokenLimit / 2).coerceAtLeast(1),
            )
        }
        if (usagePercent * 100f < autoCompressResetThreshold(setting.autoCompressThreshold)) {
            autoCompressPrompted = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            AssistantBackground(
                setting = setting,
                modifier = Modifier.hazeSource(hazeState),
            )
        }

        // 消息列表保持全高，顶栏/输入栏悬浮在上层：消息可以滚到两栏背后参与背景模糊；
        // 列表底部保留输入栏高度 + 间距的 content padding，最后一条消息仍能完整滚动到输入栏上方，
        // 加载指示器 / 建议条 / 知识库徽章也不会紧贴输入栏边缘。
        var inputBarHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // 顶栏高度是确定值（状态栏 inset + TopAppBar 固定 64dp）：预置初值让首帧就让区到位，
        // 消除内容先贴到状态栏再跳下来的闪帧；onSizeChanged 实测值随后覆盖（数值相同则无感）
        // 注意 statusBars 属性是 @Composable，只能在组合期读取，不能放进 remember 计算块
        val statusBarTopPx = WindowInsets.statusBars.getTop(density)
        var topBarHeightPx by remember {
            mutableIntStateOf(
                statusBarTopPx + with(density) { 64.dp.toPx() }.roundToInt()
            )
        }
        val inputBarHeight = with(density) { inputBarHeightPx.toDp() }
        val topBarHeight = with(density) { topBarHeightPx.toDp() }

        // 顶栏模糊（对齐输入栏）：消息列表全高铺底，顶栏悬浮其上做毛玻璃，
        // 消息滚动到顶栏后方即参与背景模糊；覆盖层容器不拦截触摸，列表手势照常穿透
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            CompositionLocalProvider(
                LocalThinkingFreezeState provides thinkingFreezeState,
                // 提供滚动折叠：吸顶条点击时按像素量平滑滚动列表（上滚收起思考 / 下滚解除吸顶）。
                // 挂起函数，调用方在协程中调用并等待完成（如"先滚到位再折叠"的顺序执行）。
                LocalScrollThinkingHeaderToPin provides { delta ->
                    // 程序滚动期间标记 scrollingByProgram，抑制自动跟随抢滚
                    thinkingFreezeState.scrollingByProgram = true
                    try {
                        scrollListByDelta(chatListState, delta)
                    } finally {
                        thinkingFreezeState.scrollingByProgram = false
                    }
                },
                LocalIsChatListAtBottom provides {
                    val info = chatListState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    last != null && isChatListPinnedToBottom(
                        totalItemsCount = info.totalItemsCount,
                        lastVisibleIndex = last.index,
                        lastItemEnd = last.offset + last.size,
                        viewportEnd = info.viewportEndOffset,
                        afterContentPadding = info.afterContentPadding,
                    )
                },
                LocalIsChatListUserControlled provides {
                    // 用户触碰中 / 用户手势滚动中（非程序滚动）/ 最近 350ms 内刚触碰或滚动过
                    // → 视为用户正在控制列表。自动折叠（思考/工具气泡/过程内容）据此暂缓：
                    // 折叠会改变 item 高度，触发 LazyColumn 锚点修正把正在看历史的用户拽回
                    // （"生成完后下滑查看上方消息回弹抽搐"根因）。
                    // 手势判定排除程序滚动：跟随滚动/折叠动画已置 scrollingByProgram，
                    // 否则生成末帧的最后一次跟随会把自动折叠误判为"用户在看历史"而跳过。
                    // 持久闩锁（userScrolledLatch）：离开底部后、回到底部前一律视为用户在
                    // 控制列表——350ms 时间窗在慢速浏览历史时会过期，生成结束的自动折叠
                    // 可能误触发（折叠高度骤减 → LazyColumn 锚点修正 → 拽回）。
                    val now = android.os.SystemClock.elapsedRealtime()
                    listInteracting.value ||
                        (chatListState.isScrollInProgress && !thinkingFreezeState.scrollingByProgram) ||
                        (now - lastUserScrollAt < 350L) ||
                        userScrolledLatch
                },
                LocalScrollChatToBottom provides {
                    // 消费闩锁（无论是否贴底都复位，让下一次折叠重新武装）；
                    // 用户已滑离底部、正在滚动、或刚滚动过（350ms 内）→ 放弃贴底，避免拽回正在看历史的用户。
                    // 关键：手指已按下但尚未消费滚动（touch-slop 窗口）也必须放弃——闩锁/时间戳
                    // 都靠布局事件触发，覆盖不了该窗口；触点状态（listInteracting）down 即置位，
                    // 直接读它把这个窗口封死（"生成完折叠后 250ms 的重贴底撞上用户刚起手"根因）。
                    val interrupted = userScrolledLatch
                    userScrolledLatch = false
                    val recentlyScrolled =
                        android.os.SystemClock.elapsedRealtime() - lastUserScrollAt < 350L
                    val userGesture =
                        chatListState.isScrollInProgress && !thinkingFreezeState.scrollingByProgram
                    if (interrupted || recentlyScrolled || userGesture || listInteracting.value) return@provides
                    // 程序滚动标记：贴底滚动本身不算用户滚动（避免反向武装闩锁/自动跟随抢滚）
                    thinkingFreezeState.scrollingByProgram = true
                    try {
                        // 用可取消的挂起 scrollToItem 而非 requestScrollToItem：
                        // requestScrollToItem 会同步硬跳，且滚动进行中会在列表内部排一个
                        // 手势结束后的补正协程（"闪到底又弹回"根因）。withTimeoutOrNull 兜底：
                        // 若贴底前用户恰好起手，等待互斥锁超时即放弃，绝不压过用户手势。
                        withTimeoutOrNull(300) {
                            chatListState.scrollToItem(
                                (chatListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            )
                        }
                    } finally {
                        thinkingFreezeState.scrollingByProgram = false
                    }
                },
            ) {
                // 底层：消息列表占满全高（顶栏悬浮其上），顶部让区走列表 contentPadding
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = conversation.id,
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                        },
                        label = "ChatContent",
                    ) {
                        ChatList(
                            innerPadding = PaddingValues(top = topBarHeight, bottom = inputBarHeight + 16.dp),
                            conversation = conversation,
                            state = chatListState,
                            isUserInteracting = listInteracting,
                            loading = loadingJob?.isActive == true,
                            processingStatus = effectiveProcessingStatus,
                            previewMode = previewMode,
                            settings = setting,
                            hazeState = hazeState,
                            errors = errors,
                            onDismissError = onDismissError,
                            onClearAllErrors = onClearAllErrors,
                            onRegenerate = {
                                vm.regenerateAtMessage(it)
                            },
                            onEdit = {
                                inputState.editingMessage = it.id
                                inputState.setContents(it.parts)
                            },
                            onForkMessage = {
                                scope.launch {
                                    val fork = vm.forkMessage(message = it)
                                    navigateToChatPage(navController, chatId = fork.id)
                                }
                            },
                            onDelete = {
                                if (loadingJob?.isActive == true) {
                                    vm.showDeleteBlockedWhileGeneratingError()
                                } else {
                                    vm.deleteMessage(it)
                                }
                            },
                            onUpdateMessage = { newNode ->
                                vm.updateConversation(
                                    conversation.copy(
                                        messageNodes = conversation.messageNodes.map { node ->
                                            if (node.id == newNode.id) {
                                                newNode
                                            } else {
                                                node
                                            }
                                        }
                                    ))
                                vm.saveConversationAsync()
                            },
                            onClickSuggestion = { suggestion ->
                                inputState.editingMessage = null
                                inputState.setMessageText(suggestion)
                            },
                            onTranslate = { message, locale ->
                                vm.translateMessage(message, locale)
                            },
                            onClearTranslation = { message ->
                                vm.clearTranslationField(message.id)
                            },
                            onJumpToMessage = { index ->
                                previewMode = false
                                scope.launch {
                                    // 普通布局下消息 item index 即消息下标
                                    chatListState.requestScrollToItem(index)
                                }
                            },
                            onToolApproval = { toolCallId, approved, reason ->
                                vm.handleToolApproval(toolCallId, approved, reason)
                            },
                            onToolAnswer = { toolCallId, answer ->
                                vm.handleToolAnswer(toolCallId, answer)
                            },
                            onApproveAllRelated = { toolCallId ->
                                vm.approveAllRelatedApprovals(toolCallId)
                            },
                            onToggleFavorite = { node ->
                                vm.toggleMessageFavorite(node)
                            },
                            onConversationSystemPromptChange = { newPrompt ->
                                vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                                vm.saveConversationAsync()
                            },
                            onAssistantNameClick = {
                                showAssistantPicker = true
                            },
                            onScrollSnapshot = onScrollSnapshot,
                        )
                    }

                    // 悬浮吸顶条：绘制于列表之上，顶部对齐列表区（顶栏正下方，无间距）。
                    // 只由 activeSection（注册的思考步骤中头部滚入冻结区的那个）驱动显隐。
                    ChatFontProvider(displaySetting = setting.displaySetting) {
                        ThinkingFrozenBar(
                            state = thinkingFreezeState,
                            hazeState = hazeState,
                            blurEnabled = setting.displaySetting.enableBlurEffect,
                            // 列表容器已全高（顶边在窗口顶部）：吸顶条下移到顶栏正下方
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = topBarHeight),
                        )
                    }

                    if (showAssistantPicker) {
                        AssistantPickerSheet(
                            settings = setting,
                            currentAssistant = assistant,
                            onAssistantSelected = { selected ->
                                showAssistantPicker = false
                                // 切换助手：更新全局当前助手，再新开聊天窗口（新窗口按全局当前助手绑定会话）
                                vm.updateSettings(setting.copy(assistantId = selected.id))
                                navigateToChatPage(navController)
                            },
                            onDismiss = {
                                showAssistantPicker = false
                            }
                        )
                    }
                }

                // 顶层：悬浮顶栏（毛玻璃背景，同输入栏）。
                // 高度上报给列表做顶部让区；底边即思考吸顶的冻结线（与旧布局的列表容器顶边一致）
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    // 上下文用量统计在 ChatPageContent 已算好，直接传入（口径单源，见 computeTokenStats）
                    tokenStats = tokenStats,
                    previewMode = previewMode,
                    // 模式在用户发送第一条 USER 消息前可切换，发送后锁定仅展示。
                    // 不以 messageNodes 是否为空判断，避免助手初始消息（presetMessages）被当成已发送
                    modeSwitchEnabled = !(loadingJob?.isActive == true) &&
                        conversation.currentMessages.none { it.role == MessageRole.USER },
                    onSwitchMode = { ref ->
                        vm.updateConversation(conversation.copy(mode = ref))
                        vm.saveConversationAsync()
                    },
                    onOpenLeftDrawer = { onLeftDrawerOpenChange(true) },
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onCompressClick = {
                        showCompressDialog = true
                    },
                    onToggleContextPopover = { toggleContextPopover() },
                    onContextAnchorBottom = { contextAnchorBottomPx = it },
                    hazeState = hazeState,
                    modifier = Modifier
                        .onSizeChanged { topBarHeightPx = it.height }
                        .onGloballyPositioned { coords ->
                            thinkingFreezeState.topBarBottomY =
                                (coords.positionInWindow().y + coords.size.height).roundToInt()
                        },
                )
            }
        }

        // 网络搜索状态/服务更新：ChatInput 与「＋」更多选项（FilesPicker）共用
        val updateSearchMode: (SearchMode) -> Unit = { mode ->
            val current = setting.getCurrentAssistant()
            val model = setting.getCurrentChatModel()
            vm.updateSettings(
                setting.copy(
                    assistants = setting.assistants.map { assistant ->
                        if (assistant.id == current.id) {
                            assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                        } else {
                            assistant
                        }
                    },
                    providers = if (model == null) {
                        setting.providers
                    } else {
                        setting.providers.map { provider ->
                            provider.editModel(
                                model.copy(
                                    tools = if (mode == SearchMode.BUILT_IN) {
                                        model.tools + BuiltInTools.Search
                                    } else {
                                        model.tools - BuiltInTools.Search
                                    }
                                )
                            )
                        }
                    },
                )
            )
        }
        val updateSearchService: (Int) -> Unit = { index ->
            vm.updateSettings(
                setting.copy(
                    searchServiceSelected = index
                )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .onSizeChanged { inputBarHeightPx = it.height },
        ) {
            // TodolistBanner - 显示在聊天输入框上方（订阅 TodoStorage 实时刷新）
            // 空列表不展示（模型可能传空 items，渲染 0/0 空卡无意义）
            if (todolist != null && todolist!!.items.isNotEmpty()) {
                TodolistBanner(
                    todolist = todolist!!,
                    onDismiss = { todoStorage.saveDismissedFingerprint(conversation.id.toString(), todolist!!.fingerprint()) },
                    stateKey = "todo:${conversation.id}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (assistant.knowledgeBaseIds.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    KnowledgeBaseChips(
                        assistant = assistant,
                        onUpdateAssistant = { updatedAssistant ->
                            vm.updateSettings(
                                setting.copy(
                                    assistants = setting.assistants.map { a ->
                                        if (a.id == updatedAssistant.id) updatedAssistant else a
                                    }
                                )
                            )
                        },
                    )
                }
            }

            ChatInput(
                modifier = Modifier.fillMaxWidth(),
                state = inputState,
                loading = loadingJob?.isActive == true,
                settings = setting,
                hazeState = hazeState,
                conversation = conversation,
                completionProviders = completionProviders,
                onCancelClick = {
                    vm.stopGeneration()
                },
                onSendClick = {
                    performSend(appendOnly = false)
                },
                onLongSendClick = {
                    performSend(appendOnly = true)
                },
                onUpdateChatModel = {
                    vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                },
                onOpenProviderSettings = {
                    navController.navigate(Screen.SettingProvider)
                },
                onUpdateAssistant = {
                    vm.updateSettings(
                        setting.copy(
                            assistants = setting.assistants.map { assistant ->
                                if (assistant.id == it.id) {
                                    it
                                } else {
                                    assistant
                                }
                            }
                        )
                    )
                },
                onMoreClick = {
                    showFilesSheet = true
                },
                onOptimizePromptClick = {
                    showPromptOptimizeSheet = true
                },
                subAgentActive = subAgentActiveCount > 0,
                subAgentActiveCount = subAgentActiveCount,
                pendingGuidance = pendingGuidance,
                pendingSends = pendingSends,
                onSendPendingGuidance = { item ->
                    vm.sendGuidanceInterrupt(item.parts)
                },
                onCancelPendingGuidance = { item ->
                    vm.cancelPendingGuidance(item.id)
                },
                onCancelPendingSend = { item ->
                    vm.cancelPendingSend(item.id)
                },
                onEditPendingGuidance = { item ->
                    vm.editPendingGuidance(item.id, item.parts)
                },
                onOpenSubAgentPanel = {
                    navController.navigate(Screen.SubAgentPanel(conversation.id.toString()))
                },
            )
        }

        if (showCompressDialog) {
            val assistant = setting.getCurrentAssistant()
            val modelContextTokenLimit = setting.getCurrentChatModel()?.contextLengthOrDefault()
            CompressContextDialog(
                contextTokenLimit = assistant.contextTokenLimit,
                modelContextTokenLimit = modelContextTokenLimit,
                autoCompressEnabled = setting.autoCompressEnabled,
                autoCompressThreshold = setting.autoCompressThreshold,
                onAutoCompressEnabledChange = { enabled ->
                    vm.updateSettings(setting.copy(autoCompressEnabled = enabled))
                },
                onAutoCompressThresholdChange = { threshold ->
                    vm.updateSettings(
                        setting.copy(autoCompressThreshold = threshold.coerceIn(1, 100))
                    )
                },
                onSaveContextTokenLimit = { newLimit ->
                    val effectiveLimit = newLimit.takeIf { it > 0 } ?: 128_000
                    vm.updateSettings(
                        setting.copy(
                            assistants = setting.assistants.map { a ->
                                if (a.id == assistant.id) a.copy(contextTokenLimit = effectiveLimit) else a
                            }
                        )
                    )
                },
                onDismiss = { showCompressDialog = false },
                onCompress = { additionalPrompt, targetTokens, keepRecentTokens ->
                    vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentTokens)
                }
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                enableSearch = enableWebSearch,
                onUpdateSearchMode = updateSearchMode,
                onUpdateSearchService = updateSearchService,
                onDismiss = { showFilesSheet = false },
            )
        }

        if (showPromptOptimizeSheet) {
            PromptOptimizeSheet(
                state = inputState,
                vm = promptOptimizeVM,
                settings = setting,
                onConfirmReplace = { result ->
                    inputState.setMessageText(result)
                    showPromptOptimizeSheet = false
                },
                onDismiss = { showPromptOptimizeSheet = false },
            )
        }

        // 上下文状态浮窗（页内覆盖层）：置于根 Box 末尾 → 绘制在最上层（含输入栏之上）。
        // 展开状态由上层 toggle 门闩驱动；退出动画期间保留组成，播完由 currentState 归位移除
        contextPopoverTransition.targetState = showContextPopover
        if (contextPopoverTransition.currentState || contextPopoverTransition.targetState) {
            ContextStatusOverlay(
                transition = contextPopoverTransition,
                onDismiss = { dismissContextPopover() },
                settings = setting,
                conversation = conversation,
                contextTotalTokens = tokenStats.totalTokens,
                contextUsagePercent = tokenStats.usagePercent,
                contextLimitLabel = formatContextLength(tokenStats.contextTokenLimit),
                onCompressClick = {
                    dismissContextPopover()
                    showCompressDialog = true
                },
                onOpenConsole = {
                    dismissContextPopover()
                    navController.navigate(Screen.ManagementDashboard)
                },
                anchorBottomPx = contextAnchorBottomPx,
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    /** 网络搜索状态与服务更新：与输入栏搜索按钮共用同一组逻辑（ChatPage 顶层提取的 updateSearchMode/updateSearchService） */
    enableSearch: Boolean,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    context.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            context.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentTokens ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentTokens)
            },
            enableSearch = enableSearch,
            onUpdateSearchMode = onUpdateSearchMode,
            onUpdateSearchService = onUpdateSearchService,
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = {
                imagePickerLauncher.launch("image/*")
            },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    tokenStats: TokenStats,
    previewMode: Boolean,
    modeSwitchEnabled: Boolean,
    hazeState: HazeState,
    onSwitchMode: (String?) -> Unit,
    onOpenLeftDrawer: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onCompressClick: () -> Unit,
    onToggleContextPopover: () -> Unit,
    onContextAnchorBottom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    val hapticController = rememberHaptic()

    // 上下文用量：由 ChatPageContent 计算一次传入，顶栏圆圈与浮窗共用同一份统计，避免两处口径不一致
    val totalTokens = tokenStats.totalTokens
    val usagePercent = tokenStats.usagePercent
    // 无 provider 实测校准锚时加 "~" 前缀明示估算（输出首块后校准为实测，前缀消失，
    // 见 computeTokenStats.measured）；"~0" 不会出现（空会话不显示数字）
    val tokenText = (if (tokenStats.measured) "" else "~") + when {
        totalTokens >= 1000 -> "%.1fk".format(totalTokens / 1000f)
        else -> totalTokens.toString()
    }

    // 顶栏毛玻璃：与输入栏共用同一套样式（Material3 tint + 12dp 半径，见 barHazeBlurStyle）。
    // 不加底边渐隐：冻结条玻璃带与顶栏同强度相接，钉住时连成一张连续的玻璃面，
    // 若顶栏底边渐隐到透明，交界处会出现"清晰细缝"（详见 ThinkingFrozenBar 的注释）
    val topBarHazeStyle = barHazeBlurStyle()

    TopAppBar(
        modifier = modifier
            // 消费点击/长按，防止穿透到顶栏后方被模糊的消息；拖动不消费，仍可透过顶栏滚动列表
            .pointerInput(Unit) { detectTapGestures { } }
            .then(
                if (settings.displaySetting.enableBlurEffect) Modifier.hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = topBarHazeStyle,
                )
                else Modifier
            ),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (settings.displaySetting.enableBlurEffect) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        navigationIcon = {
            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onOpenLeftDrawer()
                }
            ) {
                Icon(HugeIcons.Menu03, "Messages")
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    hapticController.lightTap()
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 模式切换与显示：自输入框下方栏迁移至会话标题旁（锁定态仅展示）
                    TopBarModeChip(
                        conversation = conversation,
                        settings = settings,
                        modeSwitchEnabled = modeSwitchEnabled,
                        onSwitchMode = onSwitchMode,
                    )
                }
            }
        },
        actions = {
            // 上下文用量圆圈：点击从图标位置展开状态浮窗（上下文占用/指标/全会话用量/管理控制台）。
            // toggle 唯一入口收敛在 ChatPageContent（消抖门闩防连点闪烁）；
            // 图标底边（窗口坐标系）上报给覆盖层做锚定（面板顶部 = 图标底边）。
            // 浮窗打开时本图标被全窗点击拦截层罩住：点击会先落到拦截层收起浮窗，
            // 不会触发这里的 toggle（只有关闭态点击才会走到这里）。
            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onToggleContextPopover()
                },
                modifier = Modifier
                    .size(44.dp)
                    .onGloballyPositioned { coords ->
                        onContextAnchorBottom(
                            (coords.positionInWindow().y + coords.size.height).roundToInt()
                        )
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                    CircularProgressIndicator(
                        progress = { usagePercent },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 3.dp,
                        color = when {
                            usagePercent > 0.9f -> MaterialTheme.colorScheme.error
                            usagePercent > 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // 空会话不显示数字，只留圆环（0 无信息量）
                    if (totalTokens > 0) {
                        Text(
                            text = tokenText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hapticController.lightTap()
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hapticController.lightTap()
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/**
 * 顶栏会话标题旁的模式 chip：短按弹出模式选择（可切换时），长按进模式设置页；
 * 首条用户消息发送后锁定：灰色不可点（点击无反应），仅展示当前模式。
 */
@Composable
private fun TopBarModeChip(
    conversation: Conversation,
    settings: Settings,
    modeSwitchEnabled: Boolean,
    onSwitchMode: (String?) -> Unit,
) {
    val navController = LocalNavController.current
    val hapticController = rememberHaptic()
    var showModePicker by remember { mutableStateOf(false) }
    val modeLabel = modeRefDisplayName(conversation.mode, settings.customModes, settings.builtinModeOverrides)
    val followAssistantSummary = rememberFollowAssistantSummary(settings)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // 已发送消息/生成中：锁定态灰色展示，点击无反应（不再弹提示）
                    if (modeSwitchEnabled) {
                        hapticController.lightTap()
                        showModePicker = true
                    }
                },
                onLongClick = {
                    hapticController.lightTap()
                    navController.navigate(Screen.SettingModes)
                }
            )
            .padding(vertical = 2.dp, horizontal = 6.dp)
            // 锁定态用 Material 禁用透明度呈现灰色；长按进模式设置页与切换无关，保持可用
            .alpha(if (modeSwitchEnabled) 1f else 0.38f),
    ) {
        Icon(
            imageVector = HugeIcons.SlidersVertical,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = modeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 自定义模式名可很长：限制宽度，极端长名截断而非把会话标题挤没
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }

    if (showModePicker) {
        ModePickerSheet(
            selectedRef = conversation.mode,
            customModes = settings.customModes,
            builtinModeOverrides = settings.builtinModeOverrides,
            showFollowGlobal = true,
            followAssistantSummary = followAssistantSummary,
            onSelect = { ref ->
                showModePicker = false
                onSwitchMode(ref)
            },
            onDismiss = { showModePicker = false },
        )
    }
}

/**
 * 「跟随助手配置」的实时摘要：按当前助手/全局设置列出会生效的能力族。
 */
@Composable
private fun rememberFollowAssistantSummary(settings: Settings): String {
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow.collectAsState(initial = TrustedFolderSettings())
    val assistant = settings.getCurrentAssistant()
    val boundTrustedProject = assistant.trustedFolderProjectId
        ?.let { pid -> trustedSettings.projects.find { it.id == pid } }
    return remember(assistant, settings, boundTrustedProject) {
        val enabled = buildList {
            val mcpCount = settings.mcpServers.count { it.id in assistant.mcpServers && it.commonOptions.enable }
            if (mcpCount > 0) add("MCP $mcpCount")
            if (assistant.enableWebSearch) add("联网搜索")
            if (assistant.enableMemory) add("记忆")
            if (assistant.enabledSkills.isNotEmpty()) add("技能 ${assistant.enabledSkills.size}")
            if (assistant.knowledgeBaseIds.isNotEmpty()) add("知识库")
            if (assistant.workspaceId != null) add("工作区")
            if (boundTrustedProject != null) add("信任文件夹")
            if (assistant.enableRecentChatsReference) add("历史引用")
            if (assistant.enabledStudyTools.isNotEmpty()) add("学习工具")
            if (assistant.enableTimeReminder) add("时间提醒")
            if (settings.enableTodoList) add("Todo")
            if (settings.enableSubAgent) add("子代理")
        }
        if (enabled.isEmpty()) {
            "当前仅启用本地工具、附件解析等基础能力"
        } else {
            "当前已启用：${enabled.joinToString("、")}"
        }
    }
}

@Composable
private fun TodolistBanner(
    todolist: TodoList,
    onDismiss: () -> Unit,
    stateKey: String? = null,
    modifier: Modifier = Modifier,
) {
    val inProgressItems = todolist.items.filter { it.status == TodoStatus.in_progress }
    val pendingCount = todolist.items.count { it.status == TodoStatus.pending }
    val completed = todolist.items.count { it.status == TodoStatus.completed || it.status == TodoStatus.cancelled }
    val total = todolist.items.size
    val allDone = completed == total
    val hasActive = inProgressItems.isNotEmpty() || pendingCount > 0

    // 有活跃任务时默认展开，全部完成时默认折叠；用户手动展开/折叠过则优先恢复记忆
    var expanded by remember(hasActive) {
        mutableStateOf(stateKey?.let { getSectionExpanded(it) } ?: hasActive)
    }

    // todo 列表条目增删时自动重新显示（仅 ID 集合变化，内容变更不触发）
    val itemsFingerprint = todolist.items.map { it.id }.toSet().hashCode()
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(itemsFingerprint) {
        dismissed = false
    }

    // 本地状态层：用于支持条目的出场动画，key 为 todolist 确保切换会话时重新初始化
    val displayItems = remember(todolist) { mutableStateListOf<TodoItem>().apply { addAll(todolist.items) } }
    val removingIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(todolist.items) {
        val oldIds = displayItems.map { it.id }.toSet()
        val newIds = todolist.items.map { it.id }.toSet()

        val removedIds = oldIds - newIds
        val addedIds = newIds - oldIds

        // 清理不再需要移除的条目（被重新加入的），只保留当前仍在移除列表中的
        removingIds.value = removingIds.value.intersect(removedIds)

        // 标记已移除条目，触发出场动画（清理由各条目在动画完成后自行处理）
        if (removedIds.isNotEmpty()) {
            removingIds.value = removingIds.value + removedIds
        }

        // 添加新条目，触发入场动画
        displayItems.addAll(todolist.items.filter { it.id in addedIds })

        // 更新已存在的条目，触发颜色过渡
        todolist.items.filter { it.id in oldIds.intersect(newIds) }.forEach { updated ->
            val index = displayItems.indexOfFirst { it.id == updated.id }
            if (index >= 0) displayItems[index] = updated
        }
    }

    val progress = if (total > 0) completed.toFloat() / total else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "progress",
    )

    AnimatedVisibility(visible = !dismissed) {
        Card(
            modifier = modifier.animateContentSize(),
            shape = RoundedCornerShape(12.dp),
            onClick = {
                expanded = !expanded
                // 记录用户手动展开/折叠，切换窗口回来保持
                if (stateKey != null) setSectionExpanded(stateKey, expanded)
            },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (allDone) HugeIcons.Tick01 else HugeIcons.LeftToRightListBullet,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        if (!expanded) {
                            val collapsedText = when {
                                allDone -> stringResource(R.string.chat_message_tool_todo_all_done)
                                inProgressItems.isNotEmpty() -> inProgressItems.first().content
                                pendingCount > 0 -> stringResource(R.string.chat_message_tool_todo_pending_count, pendingCount)
                                else -> stringResource(R.string.chat_message_tool_todo_title)
                            }
                            Text(
                                text = collapsedText,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (allDone) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.chat_message_tool_todo_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (allDone) stringResource(R.string.chat_message_tool_todo_done_label)
                                else "$completed/$total",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (allDone) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = stringResource(
                                if (expanded) R.string.chat_message_tool_todo_collapse
                                else R.string.chat_message_tool_todo_expand
                            ),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_todo_close),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) {
                                    dismissed = true
                                    // 全部完成后关闭 = 彻底隐藏这个任务集：持久化指纹，
                                    // 切换界面 / 重启进程都不再显示；AI 换新任务后自动重新出现。
                                    // 任务未完成时关闭仍只是本次折叠（下轮提醒/更新后重新显示）。
                                    onDismiss()
                                },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 折叠态也显示细进度条，展开态显示完整进度条
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (expanded) 4.dp else 6.dp),
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                // 展开态：显示更新说明和任务列表
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.size(4.dp))
                        displayItems.forEach { item ->
                            key(item.id) {
                                val isRemoving = item.id in removingIds.value
                                val visibleState = remember { MutableTransitionState(false) }

                                LaunchedEffect(isRemoving) {
                                    visibleState.targetState = !isRemoving
                                }

                                // 出场动画完成后从 displayItems 中移除
                                LaunchedEffect(isRemoving, visibleState.isIdle) {
                                    if (isRemoving && visibleState.isIdle &&
                                        !visibleState.currentState && !visibleState.targetState
                                    ) {
                                        displayItems.removeAll { it.id == item.id }
                                    }
                                }

                                AnimatedVisibility(
                                    visibleState = visibleState,
                                    enter = fadeIn(animationSpec = tween(300)) +
                                        slideInVertically(animationSpec = tween(300)) { it },
                                    exit = fadeOut(animationSpec = tween(300)) +
                                        slideOutVertically(animationSpec = tween(300)) { -it },
                                ) {
                                    TodoItemRow(item)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoItemRow(item: TodoItem) {
    val iconTint by animateColorAsState(
        targetValue = when (item.status) {
            TodoStatus.completed -> MaterialTheme.colorScheme.primary
            TodoStatus.in_progress -> MaterialTheme.colorScheme.tertiary
            TodoStatus.cancelled -> MaterialTheme.colorScheme.error
            TodoStatus.pending -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(400),
        label = "iconTint",
    )
    val textColor by animateColorAsState(
        targetValue = when (item.status) {
            TodoStatus.completed, TodoStatus.cancelled ->
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(400),
        label = "textColor",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item.status) {
                TodoStatus.completed -> HugeIcons.Tick01
                TodoStatus.in_progress -> HugeIcons.Sparkles
                TodoStatus.cancelled -> HugeIcons.Cancel01
                TodoStatus.pending -> HugeIcons.ArrowRight01
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconTint,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodySmall,
            textDecoration = if (item.status == TodoStatus.completed || item.status == TodoStatus.cancelled)
                TextDecoration.LineThrough else TextDecoration.None,
            color = textColor,
        )
    }
}


/**
 * 按像素量平滑滚动 LazyListState。
 * 正数向上滚（内容上移），负数向下滚（内容下移）。
 * 此前用"首可见 item offset - delta"锚点换算 animateScrollToItem，
 * 折叠时 item 高度变化会换锚点，方向和落点都会失真；直接 scrollBy 不受锚点影响。
 */
private suspend fun scrollListByDelta(
    state: LazyListState,
    delta: Float,
) {
    if (delta == 0f) return
    if (state.isScrollInProgress) {
        return
    }
    state.scrollBy(delta)
}

/**
 * 汇总一条会话当前的 token 用量与上下文上限，供顶部用量圆圈与自动压缩共用，
 * 避免两处重复计算导致数值口径不一致。
 */
private fun formatContextLength(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}k"
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000f)
    else -> tokens.toString()
}

private data class TokenStats(
    val totalTokens: Int,
    val contextTokenLimit: Int,
    val usagePercent: Float,
    /** 总量是否已被最近一次 provider 实测输入量校准（无校准锚或锚已过期时为 false） */
    val measured: Boolean,
)

private fun computeTokenStats(
    conversation: Conversation,
    settings: Settings,
    /** 本会话是否有生成任务在跑：生成中且无快照时（新会话首轮）不显示兜底估算，
     *  避免「发送瞬间的伪低占用」——快照在请求构造时写入，随后显示真实构成 */
    generating: Boolean,
): TokenStats {
    val assistant = settings.getCurrentAssistant()
    val modelContextTokenLimit = settings.getCurrentChatModel()?.contextLengthOrDefault()
    val contextTokenLimit = resolveContextTokenLimit(
        modelContextTokenLimit = modelContextTokenLimit,
        assistantContextTokenLimit = assistant.contextTokenLimit,
    )
    // 上下文占用 = 当前请求构成估算（系统提示 + 系统工具 + MCP + 技能 + 消息，见
    // ContextComposition.kt），与浮窗「构成详情」共用同一数据源，数字必然自洽；
    // 有最近一次 provider 实测输入量时按实测校准总量（比例保持估算口径）。
    // 此前按消息 usage 求和会把每次请求的全量 prompt 重复累计，导致占用虚高、与
    // 实际窗口严重不符（主流 agent 展示的是当前上下文而非累计账单）。
    val snapshot = ContextCompositionStore.get(conversation.id.toString())
    // 预设剔除/兜底必须用会话绑定的助手（getCurrentAssistant 是全局当前助手，切换后与旧会话不一致）
    val assistantForPreset = settings.getAssistantById(conversation.assistantId)
        ?: settings.getCurrentAssistant()
    // 压缩后到下一次真实生成之间的快照已是压缩后估算，且当前 usage 锚点来自压缩前的旧请求
    // （hasStaleCalibrationAnchor），此时跳过校准——否则旧实测会把压缩后的占用重新拉回虚高；
    // 压缩点之后出现新生成（新 usage 锚点）即恢复 provider 实测校准
    val realPromptTokens = conversation.effectiveMessages().lastRealPromptTokens()
    // 实测口径：快照存在 + 校准锚未过期 + 确实有 provider 实测输入量；
    // 无锚（本会话还没生成过）或锚过期时的数字是估算，UI 标「估算」而非「实测」
    val measured = snapshot != null &&
        !conversation.hasStaleCalibrationAnchor() &&
        realPromptTokens != null
    val totalTokens = snapshot
        ?.let { s ->
            if (conversation.hasStaleCalibrationAnchor()) {
                s
            } else {
                s.calibratedWith(realPromptTokens)
            }
        }
        ?.totalTokens
        // 未开始的会话（无消息或仅预设开场展示）尚未发生过请求，占用为 0；
        // 已开始的会话且不在生成中才用兜底估算（历史消息下次发送时确实占用窗口）——
        // 生成中快照即将写入，兜底值（不含工具）短暂显示会误导（如新会话首轮闪「60」）
        ?: if (conversation.hasRealMessages(assistantForPreset.presetMessages) && !generating) {
            estimateFallbackComposition(conversation, settings).totalTokens
        } else {
            0
        }
    val usagePercent = if (contextTokenLimit > 0) {
        totalTokens / contextTokenLimit.toFloat()
    } else {
        0f
    }
    return TokenStats(totalTokens, contextTokenLimit, usagePercent, measured)
}
