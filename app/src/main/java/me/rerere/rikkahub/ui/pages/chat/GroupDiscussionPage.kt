package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.discussion.DiscussionPhase
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DISCUSSION_MODERATOR_ID
import me.rerere.rikkahub.data.model.DiscussionMember
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ai.FilePickButton
import me.rerere.rikkahub.ui.components.ai.ImagePickButton
import me.rerere.rikkahub.ui.components.ai.MediaFileInputRow
import me.rerere.rikkahub.ui.components.ai.TakePicButton
import me.rerere.rikkahub.ui.components.message.ChatMessageAssistantAvatar
import me.rerere.rikkahub.ui.components.message.ChatMessageReasoningStep
import me.rerere.rikkahub.ui.components.message.ChatMessageServerToolStep
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTabletAdaptation
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

/**
 * 独立群组讨论页。
 *
 * 不复用 ChatPage 布局：顶部群名+成员头像、发言记录（按成员头像/名字/气泡）、
 * 底部控制条（输入 + 附件 + 暂停/继续 + 指定下一位）。
 * LazyColumn 按 node.id 做 key——配合 orchestrator 保留节点 id，流式更新不闪烁。
 *
 * 附件支持：输入栏可挂载图片/拍照/文件，随消息一起发送；消息气泡按 parts 渲染
 * 文本/图片/文件/视频/音频。复用主聊天页的 ChatInputState + FilesManager 链路。
 */
@Composable
fun GroupDiscussionPage(
    id: String,
    vm: GroupDiscussionVM = koinViewModel(
        parameters = { parametersOf(id) }
    ),
) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val discussionState by vm.discussionState.collectAsStateWithLifecycle()
    val generationJob by vm.generationJob.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val config = group?.config
    val isGenerating = generationJob?.isActive == true
    val navController = LocalNavController.current

    val inputState = remember { ChatInputState() }
    var showNextSpeakerSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 自动跟随滚动 + 是否在底部（决定跳底按钮显隐）。
    // 用 derivedStateOf 同步检测用户是否滚离底部（对齐 ChatPage 同款逻辑）：
    // 旧实现用 snapshotFlow 异步 collect 更新 wasAtBottom，存在延迟——用户一滑离底部、
    // 下一个流式 chunk 到达时 wasAtBottom 仍是旧值 true，会被强拉回底部（"生成中一直被强制跳底"）。
    // derivedStateOf 在布局变化时立即重算，userScrolledUp 立即变 true，自动滚动随之停止。
    val userScrolledUp by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last != null && last.index < listState.layoutInfo.totalItemsCount - 2
        }
    }
    val lastFingerprint = remember(conversation.messageNodes) {
        conversation.messageNodes.lastOrNull()?.currentMessage?.toText() ?: ""
    }
    // 生成中/新消息时自动跟随到底：仅当用户仍在底部（未手动上滑）时
    LaunchedEffect(conversation.messageNodes.size, lastFingerprint) {
        if (!userScrolledUp && listState.layoutInfo.totalItemsCount > 0) {
            listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    val hasMemberSpeech = conversation.messageNodes.any {
        it.role == MessageRole.ASSISTANT && it.currentMessage.speakerId != null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = group?.name?.ifBlank { conversation.title } ?: conversation.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = discussionStateText(discussionState, config?.mode),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    // 讨论页总是从群主页 push 而来（或创建流从列表来），back 天然回上一级，
                    // 无需顶栏"历史对话"图标。此处保留 guard：仅群组会话显示这些按钮。
                    if (conversation.groupId != null) {
                        IconButton(onClick = {
                            vm.createNewConversation { convId ->
                                // 替换当前讨论页而不是叠一层：反复新建时返回栈不会无限变深，
                                // 返回一步即回到群主页。
                                val currentScreen = Screen.GroupDiscussion(id)
                                navController.navigate(Screen.GroupDiscussion(convId.toString())) {
                                    popUpTo(currentScreen) { inclusive = true }
                                }
                            }
                        }) {
                            Icon(
                                imageVector = HugeIcons.MessageAdd01,
                                contentDescription = "新建对话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 成员头像立体堆叠（后面的压前面的，视觉层级清晰）；
                    // 点击头像 = 编辑群组成员（编辑入口合并进头像区，替代独立编辑图标）。
                    // remember 缓存成员列表 + 每项 key：成员发言流式更新 conversation 时顶栏不会重建头像。
                    // UIAvatar 内部固定 32dp（modifier.then(size(32.dp)) 覆盖外部尺寸），容器统一按 32dp 布局避免测量跳动。
                    // 点击目标 = UIAvatar 内层 Surface 本身：Surface(onClick) 的 lambda 恒非空，
                    // 它永远是可点击组件并消费手势，外层 Box.clickable 因 z 序永远收不到——根因。
                    // 这里直接给每个头像传 onClick，让内层 Surface 成为点击入口。
                    val stackedMembers = remember(config?.enabledMembers) { config?.enabledMembers.orEmpty() }
                    if (stackedMembers.isNotEmpty()) {
                        val gid = conversation.groupId
                        val shown = stackedMembers.take(4)
                        Box(
                            modifier = Modifier
                                .width((16 * (shown.size - 1) + 32).dp)
                                .height(32.dp),
                        ) {
                            shown.forEachIndexed { index, member ->
                                key(member.assistantId) {
                                    Box(
                                        modifier = Modifier.offset(x = (index * 16).dp),
                                    ) {
                                        UIAvatar(
                                            name = member.name,
                                            modifier = Modifier.size(32.dp),
                                            value = member.avatar,
                                            loading = false,
                                            onClick = {
                                                gid?.let { navController.navigate(Screen.GroupDiscussionEdit(it.toString())) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            DiscussionControlBar(
                inputState = inputState,
                isGenerating = isGenerating,
                onSend = {
                    val parts = inputState.getContents()
                    // 解析输入文本里的 @成员名：命中则指定该成员先发言；未命中/被删则全组讨论。
                    // 以文本实际内容为准，而非残留的点击状态（用户删掉 @ 即取消指定）。
                    val targetId = resolveAtMember(
                        text = parts.filterIsInstance<UIMessagePart.Text>().joinToString(" "),
                        members = config?.enabledMembers.orEmpty(),
                    )
                    inputState.clearInput()
                    vm.send(parts, nextSpeakerId = targetId)
                },
                onPause = { vm.pause() },
                onNextSpeaker = { showNextSpeakerSheet = true },
                onShowAttachmentSheet = { showAttachmentSheet = true },
                placeholder = when {
                    !hasMemberSpeech -> "输入讨论主题开始讨论"
                    isGenerating -> "插话（将中断当前发言）"
                    discussionState.phase == DiscussionPhase.COMPLETED -> "讨论已结束，输入消息开启新一轮"
                    else -> "继续讨论（输入补充或插话）"
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    items(
                        items = conversation.messageNodes,
                        key = { it.id },
                    ) { node ->
                        val isLoading = isGenerating && node.id == conversation.messageNodes.lastOrNull()?.id
                        DiscussionMessageRow(
                            node = node,
                            loading = isLoading,
                        )
                    }
                }

                // 离开底部时显示「回到底部」悬浮按钮
                if (userScrolledUp) {
                    val scope = rememberCoroutineScope()
                    Surface(
                        onClick = {
                            scope.launch {
                                if (listState.layoutInfo.totalItemsCount > 0) {
                                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                }
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                            .size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = "回到底部",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAttachmentSheet) {
        GroupAttachmentSheet(
            inputState = inputState,
            onDismiss = { showAttachmentSheet = false },
        )
    }

    if (showNextSpeakerSheet) {
        ModalBottomSheet(onDismissRequest = { showNextSpeakerSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "指定发言人（点击后在输入框填 @名字，可附消息一起发送）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                config?.enabledMembers?.forEach { member ->
                    Surface(
                        onClick = {
                            showNextSpeakerSheet = false
                            // 填入 @名字 到输入框末尾；发送时解析 @名字 指定该成员先发言
                            inputState.appendText("@${member.name} ")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            UIAvatar(
                                name = member.name,
                                modifier = Modifier.size(28.dp),
                                value = member.avatar,
                                loading = false,
                            )
                            Text(member.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** 群组附件选择面板：拍照 / 相册 / 文件（复用主聊天页的 FilesManager 落盘链路） */
@Composable
private fun GroupAttachmentSheet(
    inputState: ChatInputState,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
            onDismiss()
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = File(context.cacheDir, "camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                onDismiss()
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
                                toaster.show("读取文件失败：$fileName", type = ToastType.Error)
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show("不支持的文件类型：$fileName", type = ToastType.Error)
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    onDismiss()
                }
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TakePicButton(onLaunchCamera = onLaunchCamera)
            ImagePickButton(onClick = {
                imagePickerLauncher.launch("image/*")
            })
            FilePickButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) })
        }
    }
}

@Composable
private fun DiscussionControlBar(
    inputState: ChatInputState,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onPause: () -> Unit,
    onNextSpeaker: () -> Unit,
    onShowAttachmentSheet: () -> Unit,
    placeholder: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 已选择的附件预览（图片/文件/视频/音频），复用餐选 chips 行
        if (inputState.messageContent.isNotEmpty()) {
            MediaFileInputRow(state = inputState)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onShowAttachmentSheet) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = "添加附件",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            TextField(
                state = inputState.textContent,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                placeholder = { Text(placeholder) },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                colors = TextFieldDefaults.colors().copy(
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            // 发送键固定在整行最右侧；指定下一位在发送键左侧。
            IconButton(onClick = onNextSpeaker) {
                Icon(HugeIcons.UserGroup, contentDescription = "指定下一位", tint = MaterialTheme.colorScheme.primary)
            }
            if (isGenerating) {
                IconButton(onClick = onPause) {
                    Icon(HugeIcons.Pause, contentDescription = "暂停", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                val canSend = inputState.textContent.text.toString().isNotBlank() || inputState.messageContent.isNotEmpty()
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(HugeIcons.ArrowUp02, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun DiscussionMessageRow(
    node: MessageNode,
    loading: Boolean,
) {
    val message = node.currentMessage
    when (message.role) {
        MessageRole.USER -> {
            ChatBubble(isUser = true) {
                DiscussionUserContent(message = message)
            }
        }

        MessageRole.ASSISTANT -> {
            // 主持人总结：渲染带"主持人"标识的可折叠卡片
            if (message.speakerId == DISCUSSION_MODERATOR_ID) {
                ModeratorSummaryCard(
                    message = message,
                    loading = loading,
                )
                return
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ChatMessageAssistantAvatar(
                        message = message,
                        loading = loading,
                        model = null,
                        assistant = null,
                    )
                    DiscussionMessageContent(
                        message = message,
                        loading = loading,
                    )
                }
            }
        }

        else -> {}
    }
}

/**
 * 主持人总结卡片：带"主持人"标识，可点击展开/收起完整总结。
 * 生成中（loading）始终展开显示流式内容；生成完成后自动收起为一行摘要。
 */
@Composable
private fun ModeratorSummaryCard(
    message: UIMessage,
    loading: Boolean,
) {
    val fullText = message.toText()
    var expanded by remember(message.id) {
        // 进入时若在流式生成中（无完整内容）先展开，生成完才收起
        mutableStateOf(loading)
    }
    // 生成中始终展开（让用户看到流式内容）；生成完成后自动收起
    LaunchedEffect(loading, fullText) {
        if (!loading && fullText.isNotBlank()) {
            expanded = false
        }
    }

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.UserGroup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "主持人总结",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp02 else HugeIcons.ArrowDown01,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (fullText.isNotBlank()) {
                if (expanded || loading) {
                    MarkdownBlock(
                        content = fullText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 收起态：只显示第一行（摘要）
                    Text(
                        text = fullText.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                            ?: fullText.take(60),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 用户消息内容：文本气泡 + 附件（图片/文件/视频/音频） */
@Composable
private fun DiscussionUserContent(message: UIMessage) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    if (part.text.isNotBlank()) {
                        MarkdownBlock(
                            content = part.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is UIMessagePart.Image -> {
                    if (part.url.startsWith("data:image")) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    androidx.compose.ui.graphics.Color.LightGray
                                ),
                        )
                    } else {
                        ZoomableAsyncImage(
                            model = part.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                }

                is UIMessagePart.Document -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Files02,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = part.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is UIMessagePart.Video -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Video01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text("视频附件", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is UIMessagePart.Audio -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.MusicNote03,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text("音频附件", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                else -> {}
            }
        }
    }
}

/**
 * 发言内容：完全复用主聊天区的 groupMessageParts 渲染管线
 * （思考/工具交错时间线 + 文本气泡，与 SubAgentDetailPage 同手法）。
 */
@Composable
private fun DiscussionMessageContent(
    message: UIMessage,
    loading: Boolean,
) {
    val blocks = remember(message.parts) { message.parts.groupMessageParts() }
    blocks.forEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    ChainOfThought(
                        modifier = Modifier.fillMaxWidth(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = false,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> key(step.reasoning.createdAt) {
                                ChatMessageReasoningStep(
                                    reasoning = step.reasoning,
                                    model = null,
                                    assistant = null,
                                    collapsedAdaptiveWidth = false,
                                )
                            }

                            is ThinkingStep.ToolStep -> key(
                                step.tool.toolCallId.ifBlank { step.hashCode().toString() }
                            ) {
                                ChatMessageToolStep(
                                    tool = step.tool,
                                    loading = loading && !step.tool.isExecuted,
                                    onToolApproval = null,
                                    onToolAnswer = null,
                                )
                            }

                            is ThinkingStep.ServerToolStep -> key(
                                step.tool.toolCallId.ifBlank { step.hashCode().toString() }
                            ) {
                                ChatMessageServerToolStep(tool = step.tool)
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotBlank()) {
                            ChatBubble(isUser = false) {
                                MarkdownBlock(
                                    content = part.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(isUser: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (isUser) 16.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 16.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = if (LocalTabletAdaptation.current) 640.dp else 460.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

private fun discussionStateText(
    state: me.rerere.rikkahub.data.ai.discussion.DiscussionState,
    mode: DiscussionMode?,
): String {
    val modeText = when (mode) {
        DiscussionMode.ROUND_ROBIN -> "轮流"
        DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> "轮流+收束"
        DiscussionMode.SELECTOR -> "主持人调度"
        null -> ""
    }
    return when (state.phase) {
        // turnIndex 是"发言总数"（每成员发言一次 +1），roundIndex 才是真正的轮次（每成员一轮算一轮）
        DiscussionPhase.GENERATING -> "第 ${state.roundIndex} 轮 · ${state.currentSpeakerName ?: "…"} 发言中"
        DiscussionPhase.SCHEDULING -> "$modeText · 调度中"
        DiscussionPhase.PAUSED -> "$modeText · 已暂停"
        DiscussionPhase.COMPLETED -> "$modeText · 讨论完成"
        DiscussionPhase.ERROR -> "讨论出错"
        DiscussionPhase.IDLE -> "$modeText · 等待开始"
    }
}

/** 解析输入文本里的 @成员名，命中返回该成员 id（发送时指定其先发言），未命中返回 null */
private fun resolveAtMember(text: String, members: List<DiscussionMember>): Uuid? {
    if (text.isBlank()) return null
    // 优先精确匹配 @名字（名字含空格/特殊字符也能命中），取最后一个 @ 引用
    return members.mapNotNull { member ->
        if (text.contains("@${member.name}")) member.assistantId else null
    }.lastOrNull()
}
