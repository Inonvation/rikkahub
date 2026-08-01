package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.ai.tools.TodoItem
import me.rerere.rikkahub.data.ai.tools.TodoList
import me.rerere.rikkahub.data.ai.tools.TodoStatus
import me.rerere.rikkahub.data.ai.tools.TodoStorage
import me.rerere.rikkahub.data.ai.tools.extractLatestTodoListFromConversation
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.CompressContextDialog
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.KnowledgeBaseChips
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
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

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
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
    val errors by vm.errors.collectAsStateWithLifecycle()

    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    // 侧边栏状态保存在 ChatVM 中，导航到子页面返回后仍保持展开
    var leftDrawerOpen by remember { mutableStateOf(vm.leftDrawerOpen) }
    var rightDrawerOpen by remember { mutableStateOf(vm.rightDrawerOpen) }

    // 双向同步到 ViewModel，导航离开/返回时状态保留
    LaunchedEffect(leftDrawerOpen) { vm.leftDrawerOpen = leftDrawerOpen }
    LaunchedEffect(rightDrawerOpen) { vm.rightDrawerOpen = rightDrawerOpen }

    // Handle back press when left drawer is open
    BackHandler(enabled = leftDrawerOpen) {
        leftDrawerOpen = false
    }

    // Handle back press when right drawer is open
    BackHandler(enabled = rightDrawerOpen) {
        rightDrawerOpen = false
    }

    // 互斥
    LaunchedEffect(rightDrawerOpen) {
        if (rightDrawerOpen && leftDrawerOpen) leftDrawerOpen = false
    }
    LaunchedEffect(leftDrawerOpen) {
        if (leftDrawerOpen && rightDrawerOpen) rightDrawerOpen = false
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏模式时重置抽屉状态
    LaunchedEffect(isBigScreen) {
        if (isBigScreen) {
            leftDrawerOpen = false
            rightDrawerOpen = false
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

    // AI 消息生成过程中触发触感反馈
    var lastMessageGenerationHapticTime by remember { mutableStateOf(0L) }
    LaunchedEffect(conversation.currentMessages.size) {
        if (loadingJob != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastMessageGenerationHapticTime > 150) {
                if (setting.displaySetting.enableHapticFeedback &&
                    setting.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                }
                lastMessageGenerationHapticTime = now
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

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
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

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    // 切回正在生成中的对话时，滚动到底部以显示最新消息
    LaunchedEffect(loadingJob) {
        if (vm.chatListInitialized && loadingJob != null) {
            chatListState.scrollToItem(conversation.currentMessages.size + 5)
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                Row(Modifier.fillMaxSize()) {
                    ChatPageContent(
                        inputState = inputState,
                        loadingJob = loadingJob,
                        processingStatus = processingStatus,
                        setting = setting,
                        conversation = conversation,
                        leftDrawerOpen = false,
                        onLeftDrawerOpenChange = {},
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        enableWebSearch = enableWebSearch,
                        currentChatModel = currentChatModel,
                        bigScreen = true,
                        errors = errors,
                        onDismissError = { vm.dismissError(it) },
                        onClearAllErrors = { vm.clearAllErrors() },
                        rightDrawerOpen = false,
                        onRightDrawerOpenChange = {},
                        modifier = Modifier.weight(1f),
                    )
                    // 右侧永久面板
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(280.dp),
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            bottomStart = 16.dp,
                        ),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        StudyDrawerContent(navController = navController)
                    }
                }
            }
        }

        else -> {
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
                    StudyDrawerContent(
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
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    rightDrawerOpen = rightDrawerOpen,
                    onRightDrawerOpenChange = { rightDrawerOpen = it },
                )
            }
        }
    }

}
@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    leftDrawerOpen: Boolean,
    onLeftDrawerOpenChange: (Boolean) -> Unit,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    rightDrawerOpen: Boolean,
    onRightDrawerOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val todoStorage: TodoStorage = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    // 自动滚动：检测用户是否滚离底部
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = chatListState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= chatListState.layoutInfo.totalItemsCount - 2
        }
    }
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(chatListState.isScrollInProgress) {
        if (!chatListState.isScrollInProgress) {
            userScrolledUp = !isAtBottom
        }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && userScrolledUp) {
            userScrolledUp = false
        }
    }

    // AI 生成时自动滚动（仅在用户未手动滚离时）
    LaunchedEffect(conversation.currentMessages.size, loadingJob) {
        if (loadingJob != null && !userScrolledUp) {
            chatListState.animateScrollToItem(conversation.currentMessages.size + 5)
        }
    }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    previewMode = previewMode,
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
                    onOpenRightDrawer = {
                        onRightDrawerOpenChange(true)
                    },
                    onCompressClick = {
                        showFilesSheet = true
                    },
                )
            },
            bottomBar = {
                val todolist = conversation.currentMessages.extractLatestTodoListFromConversation(
                    todoStorage = todoStorage,
                    conversationId = conversation.id.toString(),
                )
                Column {
                    // TodolistBanner - 显示在聊天输入框上方
                    if (todolist != null) {
                        TodolistBanner(
                            todolist = todolist,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (assistant.knowledgeBaseIds.isNotEmpty()) {
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    conversation = conversation,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        val current = setting.getCurrentAssistant()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = !enableWebSearch)
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
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
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onUpdateConversation = { newConversation ->
                        vm.updateConversation(newConversation)
                        vm.saveConversationAsync()
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = conversation.id,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                },
                label = "ChatContent",
            ) {
                ChatList(
                    innerPadding = innerPadding,
                    conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
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
                    if (loadingJob != null) {
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
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
            }

            // 回到底部浮动按钮
            AnimatedVisibility(
                visible = userScrolledUp,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                FloatingActionButton(
                    onClick = {
                        userScrolledUp = false
                        scope.launch {
                            chatListState.animateScrollToItem(conversation.currentMessages.size + 5)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = "回到底部",
                    )
                }
            }
            }
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }
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
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
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
            onPickImage = { imagePickerLauncher.launch("image/*") },
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
    bigScreen: Boolean,
    previewMode: Boolean,
    onOpenLeftDrawer: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onOpenRightDrawer: () -> Unit,
    onCompressClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    val hapticController = rememberHaptic()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        onOpenLeftDrawer()
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            // 上下文用量圆圈
            val assistant = settings.getCurrentAssistant()
            val sizeInfo = rememberConversationSizeInfo(conversation)
            val contextLimit = assistant.contextMessageLimit.takeIf { it > 0 } ?: MESSAGE_NODE_WARNING_THRESHOLD
            val usagePercent = (sizeInfo.nodeCount.toFloat() / contextLimit).coerceIn(0f, 1f)
            if (usagePercent > 0.5f) {
                IconButton(
                    onClick = {
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        onCompressClick()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                        CircularProgressIndicator(
                            progress = { usagePercent },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.5.dp,
                            color = when {
                                usagePercent > 0.9f -> MaterialTheme.colorScheme.error
                                usagePercent > 0.7f -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            text = "${(usagePercent * 100).toInt()}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconButton(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    onOpenRightDrawer()
                }
            ) {
                Icon(HugeIcons.BookOpen01, "Study Panel")
            }

            IconButton(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
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
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun TodolistBanner(
    todolist: TodoList,
    modifier: Modifier = Modifier,
) {
    val inProgressItems = todolist.items.filter { it.status == TodoStatus.in_progress }
    val pendingCount = todolist.items.count { it.status == TodoStatus.pending }
    val completed = todolist.items.count { it.status == TodoStatus.completed || it.status == TodoStatus.cancelled }
    val total = todolist.items.size
    val allDone = completed == total
    val hasActive = inProgressItems.isNotEmpty() || pendingCount > 0

    // 有活跃任务时默认展开，全部完成时默认折叠
    var expanded by remember(hasActive) { mutableStateOf(hasActive) }

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
            onClick = { expanded = !expanded },
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
                                allDone -> "全部完成"
                                inProgressItems.isNotEmpty() -> inProgressItems.first().content
                                todolist.message != null -> todolist.message
                                pendingCount > 0 -> "$pendingCount 项待处理"
                                else -> "TodoList"
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
                                text = "TodoList",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (allDone) "完成" else "$completed/$total",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (allDone) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = if (expanded) "折叠" else "展开",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "关闭",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { dismissed = true },
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
                        todolist.message?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
