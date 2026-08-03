package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.discussion.DiscussionPhase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessageAssistantAvatar
import me.rerere.rikkahub.ui.components.message.ChatMessageReasoningStep
import me.rerere.rikkahub.ui.components.message.ChatMessageToolStep
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 独立群组讨论页。
 *
 * 不复用 ChatPage 布局：顶部群名+成员头像、发言记录（按成员头像/名字/气泡）、
 * 底部控制条（输入 + 暂停/继续 + 指定下一位）。
 * LazyColumn 按 node.id 做 key——配合 orchestrator 保留节点 id，流式更新不闪烁。
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

    var input by remember { mutableStateOf("") }
    var showNextSpeakerSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 自动跟随滚动 + 是否在底部（决定跳底按钮显隐）
    var wasAtBottom by remember { mutableStateOf(true) }
    val lastFingerprint = remember(conversation.messageNodes) {
        conversation.messageNodes.lastOrNull()?.currentMessage?.toText() ?: ""
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visible ->
            val last = visible.lastOrNull()
            wasAtBottom = last != null && last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(conversation.messageNodes.size, lastFingerprint) {
        if (wasAtBottom && !listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
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
                    conversation.groupId?.let { gid ->
                        IconButton(onClick = {
                            navController.navigate(Screen.GroupDiscussionEdit(gid.toString()))
                        }) {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = "编辑群组设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            vm.createNewConversation { convId ->
                                navController.navigate(Screen.GroupDiscussion(convId.toString()))
                            }
                        }) {
                            Icon(
                                imageVector = HugeIcons.MessageAdd01,
                                contentDescription = "新建对话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    config?.enabledMembers?.take(4)?.forEach { member ->
                        UIAvatar(
                            name = member.name,
                            modifier = Modifier.size(24.dp),
                            value = member.avatar,
                            loading = false,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            DiscussionControlBar(
                input = input,
                onInputChange = { input = it },
                onSend = {
                    val text = input
                    input = ""
                    vm.send(text)
                },
                isGenerating = isGenerating,
                onPause = { vm.pause() },
                onResume = { vm.resume() },
                onNextSpeaker = { showNextSpeakerSheet = true },
                placeholder = when {
                    !hasMemberSpeech -> "输入讨论主题开始讨论"
                    isGenerating -> "插话（将中断当前发言）"
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
                if (!wasAtBottom) {
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
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
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

    if (showNextSpeakerSheet) {
        ModalBottomSheet(onDismissRequest = { showNextSpeakerSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "指定下一位发言",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                config?.enabledMembers?.forEach { member ->
                    Surface(
                        onClick = {
                            showNextSpeakerSheet = false
                            vm.setNextSpeaker(member.assistantId)
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

@Composable
private fun DiscussionControlBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNextSpeaker: () -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        IconButton(onClick = onSend, enabled = input.isNotBlank()) {
            Icon(HugeIcons.ArrowUp02, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            onClick = { if (isGenerating) onPause() else onResume() },
        ) {
            Icon(
                imageVector = if (isGenerating) HugeIcons.Pause else HugeIcons.Play,
                contentDescription = if (isGenerating) "暂停" else "继续",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onNextSpeaker) {
            Icon(HugeIcons.UserGroup, contentDescription = "指定下一位", tint = MaterialTheme.colorScheme.primary)
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
                MarkdownBlock(
                    content = message.toText(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        MessageRole.ASSISTANT -> {
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
            modifier = Modifier.widthIn(max = 460.dp),
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
        DiscussionPhase.GENERATING -> "第 ${state.turnIndex} 轮 · ${state.currentSpeakerName ?: "…"} 发言中"
        DiscussionPhase.SCHEDULING -> "$modeText · 调度中"
        DiscussionPhase.PAUSED -> "$modeText · 已暂停"
        DiscussionPhase.COMPLETED -> "$modeText · 讨论完成"
        DiscussionPhase.ERROR -> "讨论出错"
        DiscussionPhase.IDLE -> "$modeText · 等待开始"
    }
}
