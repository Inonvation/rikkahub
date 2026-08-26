package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.service.PendingGuidanceItem
import me.rerere.rikkahub.service.PendingSendItem
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTabletAdaptation
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    enableSearch: Boolean,
    onUpdateSearchMode: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
    conversation: Conversation,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onOpenProviderSettings: () -> Unit = {},
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onMoreClick: () -> Unit,
    onOptimizePromptClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    /** 当前会话是否有活跃子代理任务（运行中显示输入框左侧的子代理图标替代快捷消息按钮） */
    subAgentActive: Boolean = false,
    /** 活跃子代理任务数（用于图标右上角数量角标，并行多个子代理时显示） */
    subAgentActiveCount: Int = 0,
    onOpenSubAgentPanel: (() -> Unit)? = null,
    /** 排队中的引导消息列表（生成中发送后以独立气泡显示在输入框上方右对齐，等 AI 回合结束依次自动注入） */
    pendingGuidance: List<PendingGuidanceItem> = emptyList(),
    /** 排队中的待发送消息（生成中发附件/仅追加消息时显示卡片，等 AI 回合结束依次发送） */
    pendingSends: List<PendingSendItem> = emptyList(),
    /** 排队引导旁的「打断并发送」：中断当前生成，立即注入该条引导 */
    onSendPendingGuidance: ((PendingGuidanceItem) -> Unit)? = null,
    /** 取消排队中的引导 */
    onCancelPendingGuidance: ((PendingGuidanceItem) -> Unit)? = null,
    /** 取消排队中的待发送消息 */
    onCancelPendingSend: ((PendingSendItem) -> Unit)? = null,
    /** 点击气泡文本编辑：取消排队并把文本回填输入框 */
    onEditPendingGuidance: ((PendingGuidanceItem) -> Unit)? = null,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val inputHazeStyle = HazeBlurStyle.Material3 {
        blurRadius(12.dp)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 输入框整体始终是圆角矩形，不随键盘状态改变形状；
    // 避免"贴合键盘变直角 + 收起时突变跳一下"。
    val density = LocalDensity.current
    // Unlike isImeVisible, the target changes as soon as the IME animation starts.
    val imeTargetVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    // 模型选择：把 state 提升到 ChatInput 顶层，ModelListSheet 在顶层弹出，
    // 避免输入框焦点/IME 变化导致搜索模型时键盘被自动收起。
    val modelListState = rememberModelListState(
        modelId = assistant.chatModelId ?: settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        // 生成中且输入框无文字 → 停止；否则（含生成中有文字）→ 发送
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsStateWithLifecycle()
    val hapticController = rememberHaptic()
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticController.perform(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 4.dp)
                // 多行文本增长 / 附件行出现/移除时平滑过渡高度，避免瞬时跳变。
                // 用克制 tween 而非默认 spring，避免高度轻微过冲回弹的粘滞感。
                .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 排队中的引导：独立于输入框的气泡，位于输入框上方、右对齐（对齐 Codex 样式）
            if (pendingGuidance.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    pendingGuidance.forEach { item ->
                        PendingGuidanceBubble(
                            text = item.text,
                            onSendNow = { onSendPendingGuidance?.invoke(item) },
                            onCancel = { onCancelPendingGuidance?.invoke(item) },
                            onEdit = { onEditPendingGuidance?.invoke(item) },
                        )
                    }
                }
            }

            // 排队中的待发送消息：独立于输入框，显示在输入框上方，等 AI 回合结束按序发送
            if (pendingSends.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    pendingSends.forEach { item ->
                        PendingSendBubble(
                            item = item,
                            onCancel = { onCancelPendingSend?.invoke(item) },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.largeIncreased)
                    .then(
                        if (settings.displaySetting.enableBlurEffect) Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = inputHazeStyle,
                        )
                        else Modifier
                    ),
                shape = MaterialTheme.shapes.largeIncreased,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        completionProviders = completionProviders,
                        onSendMessage = { sendMessage() },
                        subAgentActive = subAgentActive,
                        subAgentActiveCount = subAgentActiveCount,
                        onOpenSubAgentPanel = onOpenSubAgentPanel,
                        trailingContent = {
                            if (imeTargetVisible && !asrState.isRecording) {
                                SendButton(
                                    loading = loading,
                                    empty = state.isEmpty(),
                                    onClick = { sendMessage() },
                                    onLongClick = { sendMessageWithoutAnswer() },
                                )
                            }
                        },
                    )

                    AnimatedVisibility(
                        visible = !imeTargetVisible,
                        enter = EnterTransition.None,
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 禁用 Material3 默认 48dp 最小触摸尺寸，让按钮高度由内容决定，垂直对齐
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    // Model Picker
                                    ModelSelectorButton(
                                        state = modelListState,
                                        onlyIcon = true,
                                        onLongClick = onOpenProviderSettings,
                                        modifier = Modifier,
                                    )

                                    // Search
                                    val enableSearchMsg = stringResource(R.string.web_search_enabled)
                                    val disableSearchMsg = stringResource(R.string.web_search_disabled)
                                    val chatModel = settings.getCurrentChatModel()
                                    SearchPickerButton(
                                        enableSearch = enableSearch,
                                        settings = settings,
                                        onUpdateSearchMode = { mode ->
                                            onUpdateSearchMode(mode)
                                            val enabled = mode != SearchMode.OFF
                                            toaster.show(
                                                message = if (enabled) enableSearchMsg else disableSearchMsg,
                                                duration = 1.seconds,
                                                type = if (enabled) {
                                                    ToastType.Success
                                                } else {
                                                    ToastType.Normal
                                                }
                                            )
                                        },
                                        onUpdateSearchService = onUpdateSearchService,
                                        model = chatModel,
                                        compact = true,
                                    )

                                    // Reasoning
                                    val model = settings.getCurrentChatModel()
                                    if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                        ReasoningButton(
                                            reasoningLevel = assistant.reasoningLevel,
                                            onUpdateReasoningLevel = {
                                                onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                            },
                                            onlyIcon = true,
                                            compact = true,
                                        )
                                    }
                                }
                            }

                            ActionIconButton(
                                onClick = onOptimizePromptClick
                            ) {
                                Icon(
                                    imageVector = HugeIcons.MagicWand01,
                                    contentDescription = stringResource(R.string.prompt_optimize),
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            ActionIconButton(
                                onClick = onMoreClick
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }

                            if (asrState.isAvailable || asrState.isRecording) {
                                AsrButton(
                                    state = asrState,
                                    onClick = {
                                        when (asrState.status) {
                                            ASRStatus.Listening -> asr.stop()
                                            ASRStatus.Idle, ASRStatus.Error -> {
                                                if (!asrPermission.allRequiredPermissionsGranted) {
                                                    asrPermission.requestPermissions()
                                                } else {
                                                    asrBaseText = state.textContent.text.toString()
                                                    asr.start { transcript ->
                                                        val spacer =
                                                            if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                        state.setMessageText(asrBaseText + spacer + transcript)
                                                    }
                                                }
                                            }

                                            ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                        }
                                    }
                                )
                            }

                            if (!imeTargetVisible) {
                                AnimatedVisibility(
                                    visible = !asrState.isRecording,
                                    enter = fadeIn() + scaleIn(),
                                    exit = fadeOut() + scaleOut(),
                                ) {
                                    SendButton(
                                        loading = loading,
                                        empty = state.isEmpty(),
                                        onClick = { sendMessage() },
                                        onLongClick = { sendMessageWithoutAnswer() },
                                    )
                                }
                            }
                        }
                    }
                    WorkspaceFooterBar(
                        assistant = assistant,
                        conversation = conversation,
                        settings = settings,
                        // 会话无消息且未生成中时可切换模式；首条消息发送后锁定，仅展示
                        // 模式在用户发送第一条 USER 消息前可切换，发送后锁定仅展示。不以 messageNodes 是否为空判断，避免助手初始消息（presetMessages）被当成已发送
                        modeSwitchEnabled = !loading && conversation.currentMessages.none { it.role == MessageRole.USER },
                        onSwitchMode = { ref ->
                            onUpdateConversation(conversation.copy(mode = ref))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    ModelListSheet(
        state = modelListState,
        onSelect = onUpdateChatModel,
    )
}

/**
 * 「引导已排入」气泡：独立于输入框，显示在输入框上方、右对齐（对齐 Codex 样式）。
 * 默认等 AI 回合输出完成后由 ChatService 依次自动注入（气泡自动消失）；点发送按钮则
 * 打断当前生成并立即注入；点编辑按钮/文本可回填输入框编辑；点取消则丢弃这条排队引导。
 */
@Composable
private fun PendingGuidanceBubble(
    text: String,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.pending_guidance_queued, text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clickable(onClick = onEdit),
            )
            IconButton(
                onClick = onSendNow,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp02,
                    contentDescription = stringResource(R.string.send),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = stringResource(R.string.edit),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.stop),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 「待发送已排入」气泡：生成中发附件/仅追加消息时显示，等 AI 回合结束后按序发送。
 * 点叉取消这条排队消息。
 */
@Composable
private fun PendingSendBubble(
    item: PendingSendItem,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Files02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = item.previewText(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.stop),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun PendingSendItem.previewText(): String {
    val text = content.filterIsInstance<UIMessagePart.Text>()
        .joinToString(" ") { it.text }
        .trim()
    val attachmentCount = content.count { it !is UIMessagePart.Text }
    return buildString {
        if (text.isNotBlank()) append(text)
        if (attachmentCount > 0) {
            if (isNotBlank()) append(" · ")
            append("$attachmentCount 个附件")
        }
        if (isBlank()) append("待发送消息")
    }
}

@Composable
private fun SendButton(
    loading: Boolean,
    empty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticController = rememberHaptic()
    val stopping = loading && empty
    val containerColor = when {
        stopping -> MaterialTheme.colorScheme.errorContainer
        empty -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        stopping -> MaterialTheme.colorScheme.onErrorContainer
        empty -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .testTag("chat_send_button")
            .clip(CircleShape)
            .combinedClickable(
                enabled = stopping || !empty,
                onClick = { hapticController.tap(); onClick() },
                onLongClick = { hapticController.tap(); onLongClick() },
            )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = containerColor,
            content = {},
        )
        if (loading) {
            KeepScreenOn()
        }
        if (stopping) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = stringResource(R.string.stop),
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = HugeIcons.ArrowUp02,
                contentDescription = stringResource(R.string.send),
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hapticController = rememberHaptic()
    Surface(
        onClick = {
            hapticController.lightTap()
            onClick()
        },
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
    subAgentActive: Boolean = false,
    subAgentActiveCount: Int = 0,
    onOpenSubAgentPanel: (() -> Unit)? = null,
    trailingContent: @Composable () -> Unit = {},
) {
    val settings = LocalSettings.current
    val hapticController = rememberHaptic()
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // 点击用户消息进入编辑态时，自动聚焦输入框并弹出键盘，避免"已进入编辑却还要再点一下输入框"。
    // 编辑态从 false→true 时触发一次；取消编辑（true→false）无需处理，保持用户当前焦点即可。
    // 等一帧让编辑条/输入框完成布局再请求焦点，规避首帧 FocusRequester 尚未绑定导致的异常。
    LaunchedEffect(state.isEditing()) {
        if (state.isEditing()) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { hapticController.lightTap(); state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        // 输入框当前高度(px): 用于把补全弹窗上移到输入框顶边, 避免盖住正在输入的文字
        var textFieldHeightPx by remember { mutableStateOf(0) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        // 补全弹窗悬浮覆盖(独立 Popup 窗口, 不参与 Column 布局不推挤输入框)。
        // alignment = BottomStart: 弹窗底边先对齐锚点底边(输入框底边), offset 再上移
        // 一个输入框高度, 使弹窗底边贴住输入框顶边、从输入框边缘向上展开, 不盖住输入文字。
        // 非常驻: 随 completionList 出现/销毁, 收进即移除窗口, 不留常驻窗口截取焦点
        // (此前 focusable=false 的常驻 Popup 在部分 ROM 上会僵死输入法键盘)。
        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -textFieldHeightPx),
                properties = PopupProperties(focusable = false),
            ) {
                // 淡入: 首次组合播一次 fadeIn 柔和出现; 无 exit 动画(条件渲染销毁即移除)
                val transitionState = remember {
                    MutableTransitionState(initialState = false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                ) {
                    CompletionPopup(
                        completionList = list,
                        onItemClick = { item ->
                            state.applyCompletion(list.replacementRange, item)
                            completionList = null
                        },
                    )
                }
            }
        }

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .focusRequester(focusRequester)
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                }
                .onSizeChanged {
                    // 输入框高度变化(换行/全屏)时更新, 保持弹窗贴住顶边
                    textFieldHeightPx = it.height
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                // 子代理运行中：主输入框发送走引导逻辑，placeholder 提示当前处于引导模式
                Text(
                    stringResource(
                        if (subAgentActive) R.string.chat_input_placeholder_guidance
                        else R.string.chat_input_placeholder
                    )
                )
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isFocused) {
                        IconButton(
                            onClick = {
                                hapticController.lightTap()
                                isFullScreen = !isFullScreen
                            }) {
                            Icon(HugeIcons.Fullscreen, null)
                        }
                    }
                    trailingContent()
                }
            },
            leadingIcon = when {
                // 子代理任务运行时：输入框左侧显示子代理图标（替代快捷消息按钮），点击进面板
                subAgentActive && onOpenSubAgentPanel != null -> {
                    {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            SubAgentActiveButton(onClick = onOpenSubAgentPanel, count = subAgentActiveCount)
                        }
                    }
                }

                quickMessages.isNotEmpty() -> {
                    {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            QuickMessageButton(quickMessages = quickMessages, state = state)
                        }
                    }
                }

                else -> null
            },
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

// 注: 曾用常驻 Popup + AnimatedVisibility 的 scale/slide/fade 组合优化进出动画,
// 但在部分 ROM(如 MIUI) 上收回后输入法键盘僵死(焦点被 focusable=false 的常驻 Popup 截走),
// 且 scale+slide 在独立窗口上观感是乱层缩放而非从下往上展开。已改为非常驻 Popup:
// 随列表出现/销毁, BottomStart 从输入框向上展开, 首次组合 fadeIn 柔和出现, 无残留窗口。
@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    val hapticController = rememberHaptic()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { hapticController.lightTap(); onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun SubAgentActiveButton(onClick: () -> Unit, count: Int) {
    val hapticController = rememberHaptic()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                hapticController.lightTap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(HugeIcons.Bot, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        // 并行多个子代理时：右上角数量角标
        if (count > 1) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    val hapticController = rememberHaptic()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                hapticController.lightTap()
                expanded = !expanded
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(HugeIcons.Zap, null, modifier = Modifier.size(20.dp))
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(
                    min = 200.dp,
                    max = if (LocalTabletAdaptation.current) 560.dp else 360.dp,
                )
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        hapticController.lightTap()
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    val hapticController = rememberHaptic()
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = if (LocalTabletAdaptation.current) 1000.dp else 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                hapticController.lightTap()
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
