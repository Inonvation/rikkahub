package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexesCached
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.LocalCardColor
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.math.abs
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
                // 生成结束先让位一帧，再折叠，避免高度动画与 LazyColumn 锚点调整抢同一帧
                withFrameNanos {}
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
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val surfaceColor = MaterialTheme.colorScheme.surface
    val reasoningTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
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

    // 吸顶冻结：思考头部滚到顶栏底边附近时，变成一条悬浮的“冻结条”停在顶栏下方，
    // 正文继续从它下面滚过，方便随时折叠/展开。
    // onLayoutRectChanged 在滚动时持续回调（onGloballyPositioned 只在布局时触发，滚动不重触发），
    // 用它实时跟踪步骤顶部/底部窗口坐标。
    val freezeState = LocalThinkingFreezeState.current
    val freezeEnabled = freezeState != null &&
        LocalSettings.current.displaySetting.thinkingFrozenBar
    val density = LocalDensity.current
    // 吸顶条与顶栏之间的参考间距（内容 2dp 留白）
    val frozenGapPx = remember(density) { with(density) { 2.dp.toPx() } }
    // 折叠后仅当头部实际漂移超过 2dp 才补滚一次，避免慢设备上的回弹
    val foldDriftPx = remember(density) { with(density) { 2.dp.toPx() } }
    val scrollHeaderToPin = LocalScrollThinkingHeaderToPin.current
    val stepScope = rememberCoroutineScope()

    // 注册到聊天页的悬浮吸顶条：坐标由 onLayoutRectChanged 实时更新，其余字段每次重组同步
    val section = remember(freezeState, stateKey) {
        ThinkingFrozenBarSection(
            key = stateKey ?: "reasoning:${reasoning.createdAt}",
        )
    }
    val stepModifier = if (freezeEnabled) {
        Modifier.onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { bounds ->
            val rect = bounds.boundsInWindow
            section.topY.value = rect.top
            section.bottomY.value = rect.bottom
            // 头部离开冻结区（回到条下方或整块划出）时重置折叠标记
            if (!(rect.top < freezeState.topBarBottomY && rect.bottom > freezeState.topBarBottomY)) {
                if (section.folded.value) section.folded.value = false
            }
        }
    } else {
        Modifier
    }

    // 实时同步吸顶条展示所需数据
    section.duration.value = state.duration
    section.streaming.value = loading
    section.title.value = if (loading && thinkingTitle != null) thinkingTitle else null
    section.cardColor.value = LocalCardColor.current
    section.contentVisible.value = state.expandState != ReasoningCardState.Collapsed
    section.collapsed.value = state.expandState == ReasoningCardState.Collapsed

    // 吸顶条点击：
    // - 生成中（loading）：状态机强制 Preview 无法真实折叠，退化为滚动折叠——
    //   内容滚出条上方 / 头部滚回条下方，item 高度不变，不写展开状态记忆；
    // - 生成完成：真实折叠/展开。先收起内容，等 item 高度动画与 LazyColumn 锚点调整
    //   落定，再按实测坐标一次性滚到吸顶线；避免"收起瞬间锚点重排把头部顶走"的跳变。
    section.onToggle = {
        val fs = freezeState
        if (fs != null) {
            val pin = fs.topBarBottomY
            if (loading) {
                if (section.folded.value) {
                    // 滚动展开：下滚，头部回到吸顶线下方，解除吸顶（条淡出、真实头部淡入）
                    stepScope.launch {
                        fs.scrollingByProgram = true
                        try {
                            scrollHeaderToPin?.invoke(section.topY.value - (pin + frozenGapPx))
                            section.folded.value = false
                        } finally {
                            fs.scrollingByProgram = false
                        }
                    }
                } else {
                    // 滚动折叠：上滚，思考内容滚出条上方，输出顶部落到条正下方
                    stepScope.launch {
                        fs.scrollingByProgram = true
                        try {
                            scrollHeaderToPin?.invoke(section.bottomY.value - (pin + frozenGapPx))
                            section.folded.value = true
                        } finally {
                            fs.scrollingByProgram = false
                        }
                    }
                }
            } else if (state.expandState != ReasoningCardState.Collapsed) {
                // 真实折叠：先把头部滚到吸顶线（item 未折叠、纯滚动，scrollBy 精确），
                // 再收起内容；若收起引发 LazyColumn 锚点重排，等稳定后按实测坐标校准。
                section.folded.value = false
                val target = pin + frozenGapPx
                stepScope.launch {
                    fs.scrollingByProgram = true
                    try {
                        scrollHeaderToPin?.invoke(section.topY.value - target)
                        state.onExpandedChange(false, loading)
                        if (stateKey != null) setSectionExpanded(stateKey, false)
                        // 等折叠动画驱动的锚点变化彻底落定，再按实测坐标一次性校准。
                        // 只等稳定，不追中间值；只有漂移超过阈值才补滚一次。
                        section.awaitLayoutStable(stepScope)
                        val drift = section.topY.value - target
                        if (abs(drift) > foldDriftPx) {
                            scrollHeaderToPin?.invoke(drift)
                        }
                    } finally {
                        fs.scrollingByProgram = false
                    }
                }
            } else {
                // 真实展开（防御分支：正常情况下折叠后的展开走真实头部点击）
                section.folded.value = false
                stepScope.launch {
                    fs.scrollingByProgram = true
                    try {
                        state.onExpandedChange(true, loading)
                        if (stateKey != null) setSectionExpanded(stateKey, true)
                    } finally {
                        fs.scrollingByProgram = false
                    }
                }
            }
        }
    }

    // 生成结束自动折叠（或手动折叠）时：若此前处于 loading 滚动折叠态，
    // 头部仍在视口上方，输出会藏在顶栏后面——把头部滚回吸顶线
    LaunchedEffect(section.contentVisible.value, section.folded.value) {
        if (!section.contentVisible.value && section.folded.value) {
            section.folded.value = false
            val fs = freezeState
            if (fs != null) {
                fs.scrollingByProgram = true
                try {
                    scrollHeaderToPin?.invoke(section.topY.value - (fs.topBarBottomY + frozenGapPx))
                } finally {
                    fs.scrollingByProgram = false
                }
            }
        }
    }

    val freezeOwner = freezeState?.takeIf { freezeEnabled }
    DisposableEffect(freezeOwner, section) {
        if (freezeOwner != null) {
            freezeOwner.sections[section.key] = section
        }
        onDispose {
            val owner = freezeOwner ?: return@onDispose
            if (owner.sections[section.key] === section) {
                owner.sections.remove(section.key)
            }
        }
    }

    // 冻结（吸顶条可见）或折叠滚动期间隐藏真实头部，避免双重渲染。
    // 与悬浮条 fadeIn/fadeOut 同步交叉：真实头部用 alpha 交叉淡入淡出，位置不变。
    val frozenNow = freezeState?.let { fs ->
        freezeEnabled &&
            section.topY.value < fs.topBarBottomY &&
            section.bottomY.value > fs.topBarBottomY &&
            section.contentVisible.value
    } == true
    val headerAlpha by animateFloatAsState(
        targetValue = if (frozenNow || section.folded.value) 0f else 1f,
        animationSpec = tween(180),
        label = "headerAlpha",
    )
    val headerModifier = if (freezeEnabled) {
        Modifier.graphicsLayer { alpha = headerAlpha }
    } else {
        Modifier
    }

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
            ReasoningHeaderLabel(
                title = if (showThinkingTitle) thinkingTitle else null,
                duration = state.duration,
                loading = loading,
                chatFontFamily = chatFontFamily ?: FontFamily.Default,
            )
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
        modifier = stepModifier,
        headerModifier = headerModifier,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
            )
        },
    )
}

/**
 * 等待折叠引起的布局变化稳定：连续两帧坐标一致才返回。
 * 单帧不可靠，因为 withFrameNanos 在下一帧开始时就会恢复，
 * 此时重组/布局尚未执行，读到的 topY 还是折叠前的旧值。
 */
private suspend fun ThinkingFrozenBarSection.awaitLayoutStable(scope: CoroutineScope) {
    var last = topY.value
    var stableFrames = 0
    var frames = 0
    // 最多等 120 帧（约 2 秒），防止流式内容持续改变布局时永远等不到稳定。
    while (scope.isActive && stableFrames < 2 && frames < 120) {
        withFrameNanos { }
        frames++
        val current = topY.value
        if (current == last) {
            stableFrames++
        } else {
            stableFrames = 0
            last = current
        }
    }
}
