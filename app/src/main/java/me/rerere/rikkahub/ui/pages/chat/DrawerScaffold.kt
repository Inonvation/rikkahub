package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import kotlin.math.abs
import kotlin.math.sqrt

private enum class DrawerState { Closed, LeftOpen, RightOpen }

// 落定动画统一参数：临界阻尼（无回弹）。visibilityThreshold 必须远小于默认 0.01：
// 弹簧在阈值处提前结束、剩余位移会在最后一帧被一次性补齐（约 8-10px 的"吸附"跳变，
// 且恰与落定触感同帧）。阈值 0.002 让弹簧自然减速滑入终点，最终修正 ≤2px 不可见。
private val SettleSpringSpec: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.002f,
)
// 临界阻尼固有频率 ω = sqrt(stiffness)，用于把松手速度收敛到无超调区间
private val SettleOmega = sqrt(Spring.StiffnessMediumLow)

/**
 * 左右双抽屉容器（纯位移动画）。
 *
 * 唯一动画源 [progress]（-1..1，负=右抽屉、正=左抽屉、0=全关）派生一切位移：
 * - 左抽屉  滑入量 = 左宽 * (1 - p)，同时卡片让位 = 左宽 * p，两者同宽严格同步
 * - 右抽屉  滑入量 = 右宽 * (1 + p)，同时卡片让位 = -右宽 * p
 * - 聊天卡片 只做 offset 平移，无缩放/圆角/阴影，避免"矩形框"感
 *
 * 抽屉与卡片之间用 scrim 区分层次。手势方向驱动：
 * 拖动开始锁定起始状态，范围锁死（左开只能 [0,1]，右开只能 [-1,0]）。
 * 松手位移超小阈值或快速甩动即触发；松手速度带入落定弹簧（按无超调上限收敛），
 * 手势与动画速度连续，松手不急停、到边不回弹。
 */
@Composable
fun DrawerScaffold(
    modifier: Modifier = Modifier,
    leftDrawerWidth: Dp = 300.dp,
    rightDrawerWidth: Dp = 280.dp,
    leftDrawerOpen: Boolean,
    rightDrawerOpen: Boolean,
    onLeftDrawerOpenChange: (Boolean) -> Unit,
    onRightDrawerOpenChange: (Boolean) -> Unit,
    leftDrawer: @Composable () -> Unit,
    rightDrawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val leftWidthPx = with(density) { leftDrawerWidth.toPx() }
    val rightWidthPx = with(density) { rightDrawerWidth.toPx() }
    // 拖动换算基准（左右平均），progress 增量 = deltaPx / 基准
    val dragScale = (leftWidthPx + rightWidthPx) / 2f
    val dragThreshold = with(density) { 32.dp.toPx() }
    val directionLock = with(density) { 20.dp.toPx() }

    val progress = remember { Animatable(0f) }
    // 导航返回时 Animatable 会被重置为 0f，但 drawer 状态仍是打开；
    // 记录"上次 drawer 是否打开"，restore 时直接 snap 而非 animate，避免和 nav pop 过渡重复
    val drawerWasOpen = rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = rememberHaptic()

    var isDragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var dragBaseProgress by remember { mutableFloatStateOf(0f) }
    var dragTotalDelta by remember { mutableFloatStateOf(0f) }
    var dragBaseState by remember { mutableStateOf(DrawerState.Closed) }
    var dragDirection by remember { mutableFloatStateOf(0f) }
    // 侧栏刚被打开后的"保护期"：期间忽略卡片点击关闭。
    // 打开侧栏会收起键盘（见下方 hide），键盘收起伴随窗口 resize，
    // 内容卡片的位置/布局随之变化，clickable 可能把这次布局变化误判成一次点击，
    // 立即把刚展开的侧栏关掉（表现为"输入法收回、侧栏又折叠回去"）。
    // 用手势打开（settle 落定非 0）时记录时间戳，400ms 内点击一律忽略。
    // 初始 0 作哨兵：elapsedRealtime 恒为正，0 表示"从未手势打开过"，保护不生效。
    var drawerOpenedAt by remember { mutableLongStateOf(0L) }

    // 布尔状态 → 目标进度（非拖动时由动画驱动）
    val target = when {
        leftDrawerOpen -> 1f
        rightDrawerOpen -> -1f
        else -> 0f
    }
    // 互斥兜底：两个抽屉同时为 true 时左优先，关闭右侧（内部归一，替代调用方的互斥 LaunchedEffect）
    LaunchedEffect(leftDrawerOpen, rightDrawerOpen) {
        if (leftDrawerOpen && rightDrawerOpen) onRightDrawerOpenChange(false)
    }
    LaunchedEffect(leftDrawerOpen, rightDrawerOpen) {
        if (!isDragging) {
            // 导航返回后 restore 时 drawer 应仍处于打开状态，直接 snap 跳过动画
            if (drawerWasOpen.value && (leftDrawerOpen || rightDrawerOpen)) {
                progress.snapTo(target)
            } else {
                progress.animateTo(target, SettleSpringSpec)
            }
            drawerWasOpen.value = leftDrawerOpen || rightDrawerOpen
        }
    }

    // 打开时收起键盘
    LaunchedEffect(leftDrawerOpen, rightDrawerOpen) {
        if (leftDrawerOpen || rightDrawerOpen) keyboardController?.hide()
    }

    // 位移/圆角/阴影全部由 progress 派生，但只在 graphicsLayer 的 draw 阶段 lambda 里读取，
    // 避免在组合期读取 progress.value 导致每帧重组整个界面（聊天列表也随之参与重排）。

    // 当前 progress 对应的状态（拖动基准判定用）
    fun currentProgressState(): DrawerState = when {
        progress.value > 0f -> DrawerState.LeftOpen
        progress.value < 0f -> DrawerState.RightOpen
        else -> DrawerState.Closed
    }

    // 拖动换算比例：当前手势实际驱动的那一侧宽度（progress 增量 = deltaPx / 该值），
    // 使抽屉位移与手指 1:1；方向未锁定时退回左右平均
    fun currentDragScale(): Float = when {
        dragBaseState == DrawerState.LeftOpen -> leftWidthPx
        dragBaseState == DrawerState.RightOpen -> rightWidthPx
        dragDirection > 0f -> leftWidthPx
        dragDirection < 0f -> rightWidthPx
        else -> dragScale
    }

    fun beginDrag() {
        isDragging = true
        settleJob?.cancel()
        // 用当前 progress 判定基准状态，避免展开动画进行中（布尔已 true 但 progress 未到 1）
        // 被误判为 Closed，从而在动画中段反向滑就能直接开另一侧
        dragBaseState = currentProgressState()
        dragBaseProgress = progress.value
        dragTotalDelta = 0f
        dragDirection = 0f
    }

    fun updateProgress() {
        val scale = currentDragScale()
        val newProgress = when (dragBaseState) {
            DrawerState.LeftOpen -> (dragBaseProgress + dragTotalDelta / scale).coerceIn(0f, 1f)
            DrawerState.RightOpen -> (dragBaseProgress + dragTotalDelta / scale).coerceIn(-1f, 0f)
            // Closed 下 dragBaseProgress 恒为 0，位移直接驱动进度；方向锁定后范围受限，不会误开另一侧
            DrawerState.Closed -> when {
                dragDirection > 0f -> (dragTotalDelta / scale).coerceIn(0f, 1f)
                dragDirection < 0f -> (dragTotalDelta / scale).coerceIn(-1f, 0f)
                else -> (dragTotalDelta / scale).coerceIn(-1f, 1f)
            }
        }
        scope.launch { progress.snapTo(newProgress) }
    }

    fun settle(velocity: Float) {
        isDragging = false
        val settledTarget = decideSettleTarget(
            baseState = dragBaseState,
            dragDirection = dragDirection,
            netDelta = dragTotalDelta,
            velocity = velocity,
            threshold = dragThreshold,
        )
        settleJob?.cancel()
        settleJob = scope.launch {
            // 松手速度带入落定动画（换算为 progress/s），消除"先急停、再被弹簧拉走"的突兀感；
            // 朝目标方向的速度按无超调上限收敛，保证到达终点不冲过头再弹回
            val initialVelocity = capSettleVelocity(
                velocity = velocity / currentDragScale(),
                current = progress.value,
                target = settledTarget,
                omega = SettleOmega,
            )
            progress.animateTo(settledTarget, SettleSpringSpec, initialVelocity = initialVelocity)
            onLeftDrawerOpenChange(settledTarget > 0f)
            onRightDrawerOpenChange(settledTarget < 0f)
            if (settledTarget != 0f) {
                // 记录"刚由手势打开的侧栏"，供内容卡片点击保护使用
                drawerOpenedAt = android.os.SystemClock.elapsedRealtime()
            }
            haptic.perform(
                if (settledTarget != 0f) HapticFeedbackType.GestureThresholdActivate
                else HapticFeedbackType.GestureEnd
            )
        }
    }

    // 卡片拖动带方向锁定；抽屉拖动直接以起始状态为基准
    val cardDragState = rememberDraggableState { delta ->
        dragTotalDelta += delta
        if (dragDirection == 0f && abs(dragTotalDelta) > directionLock) {
            dragDirection = if (dragTotalDelta > 0f) 1f else -1f
        }
        updateProgress()
    }
    val panelDragState = rememberDraggableState { delta ->
        dragTotalDelta += delta
        updateProgress()
    }

    val cardDragModifier = Modifier
        .draggable(
            orientation = Orientation.Horizontal,
            state = cardDragState,
            onDragStarted = { beginDrag() },
            onDragStopped = { v: Float -> settle(v) },
        )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 左侧抽屉（底层，与卡片让位同宽滑入）
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(leftDrawerWidth)
                .graphicsLayer {
                    translationX = -leftWidthPx * (1f - progress.value)
                }
                .then(
                    if (leftDrawerOpen) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = panelDragState,
                            onDragStarted = { beginDrag() },
                            onDragStopped = { v: Float -> settle(v) },
                        )
                    } else {
                        Modifier
                    }
                )
                .drawWithContent {
                    if (progress.value > 0.01f) drawContent()
                },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        ) {
            leftDrawer()
        }

        // 2. 右侧抽屉（底层，与卡片让位同宽滑入）
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(rightDrawerWidth)
                .graphicsLayer {
                    translationX = rightWidthPx * (1f + progress.value)
                }
                .then(
                    if (rightDrawerOpen) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = panelDragState,
                            onDragStarted = { beginDrag() },
                            onDragStopped = { v: Float -> settle(v) },
                        )
                    } else {
                        Modifier
                    }
                )
                .drawWithContent {
                    if (progress.value < -0.01f) drawContent()
                },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        ) {
            rightDrawer()
        }

        // 3. 内容卡片（顶层，圆角 + 真实阴影随展开渐显）
        // 位移/圆角/阴影都在 graphicsLayer 的 draw 阶段读取 progress 派生，避免每帧重组。
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val eased = FastOutSlowInEasing.transform(abs(p))
                    translationX = when {
                        p > 0f -> leftWidthPx * p
                        p < 0f -> rightWidthPx * p
                        else -> 0f
                    }
                    // 圆角随展开渐显（关闭 0、全开 24dp），让卡片呈浮起形态
                    shape = RoundedCornerShape(24.dp * eased)
                    // 卡片真实阴影，投影自然落在底层抽屉上（单位为像素）
                    shadowElevation = (12.dp * eased).toPx()
                    clip = true
                }
                .then(cardDragModifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    // 抽屉开着时点卡片空白处关闭；兜底消费触控阻止穿透。
                    // 保护期（打开后 400ms 内）忽略点击：手势打开侧栏伴随键盘收起 + resize，
                    // 该阶段的 clickable 点击多是"布局变化被误判为点击"，放行会把刚展开的侧栏又关掉。
                    val now = android.os.SystemClock.elapsedRealtime()
                    val insideProtection = drawerOpenedAt != 0L && now - drawerOpenedAt < 400L
                    if (!insideProtection) {
                        if (leftDrawerOpen) onLeftDrawerOpenChange(false)
                        else if (rightDrawerOpen) onRightDrawerOpenChange(false)
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * 方向驱动松手判定。
 *
 * 以手势开始时的 [baseState] + 手势锁定方向 [dragDirection] 为基准，确定可落区间：
 * - baseState=LeftOpen（左栏已开或展开中，progress≥0）：
 *   - 手势方向为右（dragDirection>0，继续拉大展开）→ 只有「保持开(1) / 关闭(0)」，永不落负值
 *   - 手势方向为左（dragDirection<0，反向拉回）→ 只关闭（0），回不到 1，也绝不跨到右栏
 * - baseState=RightOpen 同理对称。
 * - baseState=Closed（手势开始时全关）：右滑开左(1)、左滑开右(-1)、无方向回 0。
 *
 * 关闭判定只看方向信号（位移超阈值或快速甩动）。关键：**任何一次手势都绝不产生对侧结果**，
 * 即使手势中途反向或展开动画进行中被打断，也不会出现"左开动画中右滑打开右栏"。
 */
private fun decideSettleTarget(
    baseState: DrawerState,
    dragDirection: Float,
    netDelta: Float,
    velocity: Float,
    threshold: Float,
): Float {
    val intendLeft = netDelta < -threshold || (abs(netDelta) <= threshold && velocity < -800f)
    val intendRight = netDelta > threshold || (abs(netDelta) <= threshold && velocity > 800f)
    return when (baseState) {
        DrawerState.LeftOpen -> when {
            dragDirection < 0f -> 0f // 反向拉回：关闭当前栏，不跨栏
            intendLeft -> 0f        // 左滑：关闭
            else -> 1f              // 右滑或未超阈值：保持展开
        }
        DrawerState.RightOpen -> when {
            dragDirection > 0f -> 0f // 反向拉回：关闭当前栏，不跨栏
            intendRight -> 0f        // 右滑：关闭
            else -> -1f              // 左滑或未超阈值：保持展开
        }
        DrawerState.Closed -> when {
            intendRight && !intendLeft -> 1f
            intendLeft && !intendRight -> -1f
            else -> 0f
        }
    }
}

/**
 * 松手速度收敛：把手指速度（已换算到 progress 单位）带入落定动画的同时，保证临界阻尼
 * 弹簧不越过目标点（视觉上"弹一下"）。
 *
 * 临界阻尼弹簧从 x0（= current - target）带初速 v0 出发，只有"朝向目标的速度超过
 * ω·|x0|"时才会越过目标再折返。因此：
 * - 朝目标方向的速度按 ω·|remaining| 封顶；
 * - 背离目标的速度不封顶（弹簧先减速再折返，不会越过目标）；
 * - 已落在目标上时速度清零（贴边松手直接停住，不冲出去再回来）。
 */
internal fun capSettleVelocity(
    velocity: Float,
    current: Float,
    target: Float,
    omega: Float,
): Float {
    val remaining = target - current
    if (remaining == 0f) return 0f
    if (velocity * remaining <= 0f) return velocity
    val cap = abs(remaining) * omega
    return velocity.coerceIn(-cap, cap)
}
