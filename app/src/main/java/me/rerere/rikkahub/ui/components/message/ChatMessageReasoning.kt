package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(
    reasoning: UIMessagePart.Reasoning,
    stateKey: String?,
): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = ScrollState(0),
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        ).also { s ->
            // 恢复用户手动记忆的展开/折叠（仅用户操作过才记录，生成中自动预览不干扰）
            if (stateKey != null) {
                val remembered = getSectionExpanded(stateKey)
                if (remembered != null) {
                    s.expandState = if (remembered) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
                }
            }
        }
    }

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent) {
                state.expandState = ReasoningCardState.Preview
            }
            // 让位一帧，避免滚动动画抢占正文渲染与自动滚动，减少流式时的滚动卡顿
            yield()
            state.scrollState.animateScrollTo(state.scrollState.maxValue)
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCollapseAllSteps || settings.displaySetting.autoCloseThinking) {
                    ReasoningCardState.Collapsed
                } else {
                    ReasoningCardState.Expanded
                }
            }
        }
    }

    LaunchedEffect(loading) {
        if (loading) {
            // 时长标签只显示到秒（1 位小数），200ms 刷新足够，
            // 比 50ms 轮询减少 4 倍重组频率，降低推理流式期间的消息重组开销。
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(200)
            }
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    loading: Boolean,
    onCollapse: () -> Unit,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val surfaceColor = MaterialTheme.colorScheme.surface
    val reasoningTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
    )
    val collapseOnDoubleTap = LocalSettings.current.displaySetting.doubleTapCollapseThinking
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!loading && collapseOnDoubleTap) {
                    // 生成完成后，双击内容区任意处即可折叠思考内容（无需滑到顶部点按钮）。
                    // 只监听 onDoubleTap：单击无延迟、不触发；长按复制、点链接、滑动滚动
                    // 都会被内层消费/取消，不会误触发折叠。
                    Modifier.pointerInput(collapseOnDoubleTap) {
                        detectTapGestures(onDoubleTap = {
                            onCollapse()
                        })
                    }
                } else {
                    Modifier
                }
            )
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to surfaceColor,
                                    (1 - fadeHeight / size.height) to surfaceColor,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        val reasoningContent = @Composable {
            MarkdownBlock(
                content = reasoning.reasoning.replaceRegexesCached(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                ),
                style = reasoningTextStyle,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val conversationId = LocalConversationId.current
    val stateKey = remember(reasoning.createdAt, conversationId) {
        conversationId?.let { "reasoning:$it:${reasoning.createdAt}" }
    }
    val (state, loading) = rememberReasoningState(reasoning, stateKey)
    val thinkingTitle = reasoning.reasoning.extractThinkingTitle()
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { next ->
            state.onExpandedChange(next, loading)
            // 用户手动操作才记录；生成中自动 preview/autoClose 不经过此回调，不影响记忆
            if (stateKey != null) setSectionExpanded(stateKey, next)
        },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (showThinkingTitle) {
                ReasoningTitle(title = thinkingTitle!!)
            } else {
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            if (showThinkingTitle && state.duration > 0.seconds) {
                Text(
                    text = state.duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
                onCollapse = {
                    state.onExpandedChange(false, loading)
                    // 双击折叠与点击 label 折叠语义一致：写入进程级记忆，切换界面后保持折叠
                    if (stateKey != null) setSectionExpanded(stateKey, false)
                },
            )
        },
    )
}


@Composable
private fun ReasoningTitle(title: String) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
