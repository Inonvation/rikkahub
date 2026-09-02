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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration

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
    /** 列表当前是否钉在底部：仅贴底时才自动折叠思考，避免高度骤减触发 LazyColumn scrollBack 吸底 */
    atBottom: () -> Boolean,
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
                } else {
                    // 无记忆：已完成消息按 autoCloseThinking 决定初始展开态。
                    // 此前固定 Collapsed，消息被重新组合（打开历史对话/列表回收后回来）时
                    // 生成完的展开逻辑（只在 expanded 时才处理）不会生效，思考被固定折叠，
                    // 即"关闭自动折叠思考后内容仍自动折叠"的根因。
                    s.expandState = if (reasoning.finishedAt != null && !settings.displaySetting.autoCloseThinking) {
                        ReasoningCardState.Expanded
                    } else {
                        ReasoningCardState.Collapsed
                    }
                }
            }
        }
    }

    // 自动折叠/展开只在"本组合内 loading 由 true 翻转为 false"（即刚生成完）时执行：
    // 切走切回/滚出视口重建的已完成思考，形态在完成时刻已定（记忆恢复或开关推导），
    // 再次执行会把用户手动展开的思考重置（与 ChatMessage 过程区自动折叠守卫同源）。
    var prevReasoningLoading by remember(reasoning.createdAt) { mutableStateOf(loading) }
    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent) {
                state.expandState = ReasoningCardState.Preview
            }
            // 让位一帧，避免滚动抢占正文渲染与自动滚动，减少流式时的滚动卡顿
            yield()
            // 每 chunk 重启 animateScrollTo 会持续取消/重启动画占帧，改为无动画直接贴底。
            // 对齐上游：每 chunk 无条件贴底，不做"用户上翻/停在中途"判定——
            // maxValue 是布局后的快照、Markdown 流式重排还会把 value 回退（clamp），
            // 两类时序错位都会让基准判定失效，跟随一旦脱落无法自行恢复
            // （"思考预览概率性不跟随输出滚动"根因）。上翻预览被拽回是上游既有行为。
            state.scrollState.scrollTo(state.scrollState.maxValue)
        } else {
            if (prevReasoningLoading && state.expandState.expanded) {
                // 生成结束先让位一帧，再折叠，避免高度动画与 LazyColumn 锚点调整抢同一帧
                withFrameNanos {}
                // 对齐上游：思考内容自身的折叠只受"自动折叠思考"控制；
                // "自动折叠所有步骤"负责过程内容（思考链/工具链）整体折叠，不掺进这里。
                // 只有列表贴底时才自动折叠：贴底时折叠后由 scrollBack/贴底逻辑保持底部；
                // 用户在看历史时保持展开，避免 item 高度骤减触发 LazyColumn scrollBack
                // 把列表吸回底部（"生成完自动滚到底 + 下拉跳动"根因）。
                val autoClose = settings.displaySetting.autoCloseThinking
                state.expandState = when {
                    autoClose && atBottom() -> ReasoningCardState.Collapsed
                    !autoClose -> ReasoningCardState.Expanded
                    else -> state.expandState
                }
            }
        }
        prevReasoningLoading = loading
    }

    LaunchedEffect(loading) {
        if (loading) {
            // 生成中要实时显示已思考秒数（1 位小数），200ms 刷新足够，
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
    // 折叠后重新贴底/自动折叠判断用：组合期捕获，回调中调用（lambda 内部按调用时刻读当前布局）
    val isChatListAtBottom = LocalIsChatListAtBottom.current
    val scrollChatToBottom = LocalScrollChatToBottom.current
    // 用户是否正在控制列表（触碰中/滚动中/刚操作过）：自动折叠据此暂缓
    val isUserControlled = LocalIsChatListUserControlled.current
    val (state, loading) = rememberReasoningState(
        reasoning = reasoning,
        stateKey = stateKey,
        // 仅"钉在底部且用户未在控制列表"时才自动折叠：用户正在翻历史/刚触碰过列表时
        // 保持展开，避免 item 高度骤减触发 LazyColumn 锚点修正把列表吸回底部
        // （"生成完后下滑查看上方消息回弹抽搐"根因）。
        atBottom = { isChatListAtBottom?.invoke() == true && isUserControlled?.invoke() != true },
    )
    // 标题提取是对整段思考文本的 O(行数) 扫描，必须按文本本身缓存：
    // 下面同步吸顶条数据时读的是 state.duration，step 主体因此订阅了计时，
    // 生成期间每 200ms 就会重组一次——不缓存的话长思考文本会被每 200ms 全量扫一遍正则。
    // 按 reasoning 文本做 key：文本随流式 chunk 变时才重算，计时刷新命中缓存。
    val thinkingTitle = remember(reasoning.reasoning) {
        reasoning.reasoning.extractThinkingTitle()
    }
    // 标题只在生成中显示：生成结束后主文案换成"思考了 n 秒"，标题不再保留
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily

    // 吸顶冻结：思考头部滚到顶栏底边附近时，变成一条悬浮的“冻结条”停在顶栏下方，
    // 正文继续从它下面滚过，方便随时折叠/展开。
    // onLayoutRectChanged 在滚动时持续回调（onGloballyPositioned 只在布局时触发，滚动不重触发），
    // 用它实时跟踪步骤顶部/底部窗口坐标。
    val freezeState = LocalThinkingFreezeState.current
    val freezeEnabled = freezeState != null &&
        LocalSettings.current.displaySetting.thinkingFrozenBar
    // 用户手动展开/收起思考（或其它消息内折叠内容）时通知列表取消自动跟随。
    // 在组合期读取，供点击/协程回调捕获，避免在协程里访问 CompositionLocal.current。
    val onManualContentToggle = LocalOnManualContentToggle.current
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
    // 仅启用吸顶冻结时跟踪步骤窗口坐标
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

    // 实时同步吸顶条展示所需数据：仅吸顶冻结启用时执行。
    // 否则这些写入（尤其 section.duration = state.duration）会让 step 主体订阅计时状态，
    // 生成中每 200ms 重组整个头部步骤；而未启用冻结条时这些字段无人消费，纯属浪费。
    // 启用后各字段在首次进入时同步一次，后续由计时/状态变化驱动增量同步。
    if (freezeEnabled) {
        section.duration.value = state.duration
        section.streaming.value = loading
        section.title.value = if (showThinkingTitle) thinkingTitle else null
        section.cardColor.value = LocalCardColor.current
        section.contentVisible.value = state.expandState != ReasoningCardState.Collapsed
        section.collapsed.value = state.expandState == ReasoningCardState.Collapsed
    }

    // 吸顶条点击：
    // - 生成中（loading）：与真实头部一致，同样在 Preview↔Expanded 之间切换内容状态
    //   （生成完才能真正折叠到 Collapsed）。先滚到吸顶线让展开/收起时头部停在吸顶线，
    //   避免"箭头显示收起、实际却只是滚动折叠"的不一致；
    // - 生成完成：真实折叠/展开。普通布局下思考头部天然锚定（顶部固定），
    //   只需先把头部滚到吸顶线，再切换状态即可，折叠/展开时头部停在吸顶线。
    section.onToggle = {
        val fs = freezeState
        if (fs != null) {
            val pin = fs.topBarBottomY
            // 折叠/展开的程序滚动与高度动画窗口内置位 scrollingByProgram，
            // 抑制自动跟随抢滚（避免折叠后上滑被拽回底部）。
            suspend fun programScroll(block: suspend () -> Unit) {
                fs.scrollingByProgram = true
                try {
                    block()
                    // 等短高度动画（~200ms）窗口过去再释放
                    delay(250)
                } finally {
                    fs.scrollingByProgram = false
                }
            }
            if (loading) {
                // 与真实头部一致：生成中冻结条同样切换 Preview↔Expanded 内容状态，
                // 不再只做滚动折叠（否则箭头提示"收起"、实际却只是滚动折叠，行为不一致）。
                section.folded.value = false
                val nextExpanded = state.expandState != ReasoningCardState.Expanded
                stepScope.launch {
                    programScroll {
                        // 先把头部滚到吸顶线，让展开/收起时头部停在吸顶线
                        scrollHeaderToPin?.invoke(section.topY.value - (pin + frozenGapPx))
                        state.onExpandedChange(nextExpanded, loading)
                        if (stateKey != null) setSectionExpanded(stateKey, nextExpanded)
                        // 通知列表：用户手动展开/收起（加载中会取消自动跟随，避免高度骤增被拽底）
                        onManualContentToggle?.invoke()
                    }
                }
            } else if (state.expandState != ReasoningCardState.Collapsed) {
                // 真实折叠：把头部滚到吸顶线（纯滚动），再收起内容；头部在吸顶线停住
                section.folded.value = false
                val target = pin + frozenGapPx
                val wasAtBottom = isChatListAtBottom?.invoke() == true
                stepScope.launch {
                    programScroll {
                        scrollHeaderToPin?.invoke(section.topY.value - target)
                        state.onExpandedChange(false, loading)
                        if (stateKey != null) setSectionExpanded(stateKey, false)
                        // 等预滚动位置落定，按实测坐标做一次最终校准（漂移超阈值才补滚）
                        withFrameNanos { }
                        val drift = section.topY.value - target
                        if (abs(drift) > foldDriftPx) {
                            scrollHeaderToPin?.invoke(drift)
                        }
                    }
                    // programScroll 内含 250ms 动画等待；若折叠前在底部，重新贴底抵消 scrollBack 上移
                    if (wasAtBottom) {
                        scrollChatToBottom?.invoke()
                    }
                }
            } else {
                // 真实展开（防御分支）：把头部滚到吸顶线并展开内容
                section.folded.value = false
                val target = pin + frozenGapPx
                stepScope.launch {
                    programScroll {
                        scrollHeaderToPin?.invoke(section.topY.value - target)
                        state.onExpandedChange(true, loading)
                        if (stateKey != null) setSectionExpanded(stateKey, true)
                    }
                }
            }
        }
    }

    // 生成结束自动折叠（或手动折叠）时：若此前处于 loading 滚动折叠态，
    // 头部仍在视口上方，输出会藏在顶栏后面——把头部滚回吸顶线。
    LaunchedEffect(section.contentVisible.value, section.folded.value) {
        if (!section.contentVisible.value && section.folded.value) {
            section.folded.value = false
            val fs = freezeState
            if (fs != null) {
                fs.scrollingByProgram = true
                try {
                    val target = fs.topBarBottomY + frozenGapPx
                    scrollHeaderToPin?.invoke(section.topY.value - target)
                    withFrameNanos { }
                    val drift = section.topY.value - target
                    if (abs(drift) > foldDriftPx) {
                        scrollHeaderToPin?.invoke(drift)
                    }
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
            val wasAtBottom = !next && (isChatListAtBottom?.invoke() == true)
            state.onExpandedChange(next, loading)
            // 用户手动操作才记录；生成中自动 preview/autoClose 不经过此回调，不影响记忆
            if (stateKey != null) setSectionExpanded(stateKey, next)
            // 用户手动展开/折叠：通知列表取消自动跟随（加载中生效），
            // 避免 reasoning item 高度骤增后自动跟随把列表硬拽到内容底部（"展开后突然跳到底部"根因）。
            onManualContentToggle?.invoke()
            // 折叠若发生在底部：等高度动画落定后重新贴底，抵消 LazyColumn scrollBack 的上移
            if (wasAtBottom) {
                stepScope.launch {
                    delay(250)
                    scrollChatToBottom?.invoke()
                }
            }
        },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.7f),
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
            ReasoningElapsedLabel(
                loading = loading,
                duration = state.duration,
                chatFontFamily = chatFontFamily ?: FontFamily.Default,
            )
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
