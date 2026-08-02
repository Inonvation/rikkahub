package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DrawerState { Closed, LeftOpen, RightOpen }

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
 * 松手位移超小阈值或快速甩动即触发。
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

    // 布尔状态 → 目标进度（非拖动时由动画驱动）
    val target = when {
        leftDrawerOpen -> 1f
        rightDrawerOpen -> -1f
        else -> 0f
    }
    LaunchedEffect(leftDrawerOpen, rightDrawerOpen) {
        if (!isDragging) {
            // 导航返回后 restore 时 drawer 应仍处于打开状态，直接 snap 跳过动画
            if (drawerWasOpen.value && (leftDrawerOpen || rightDrawerOpen)) {
                progress.snapTo(target)
            } else {
                progress.animateTo(target, tween(300, easing = FastOutSlowInEasing))
            }
            drawerWasOpen.value = leftDrawerOpen || rightDrawerOpen
        }
    }

    // 打开时收起键盘
    LaunchedEffect(leftDrawerOpen, rightDrawerOpen) {
        if (leftDrawerOpen || rightDrawerOpen) keyboardController?.hide()
    }

    // 位移由 progress 派生，三元素同宽同步
    val p = progress.value
    val pAbs = abs(p)
    // 卡片让位：正=左开（右移），负=右开（左移）
    val cardOffsetX = when {
        p > 0f -> leftWidthPx * p
        p < 0f -> rightWidthPx * p
        else -> 0f
    }
    // 抽屉滑入：左抽屉左缘从 -leftWidth 滑到 0，右抽屉右缘从 rightWidth 滑到 0
    val leftSlide = -leftWidthPx * (1f - p)
    val rightSlide = rightWidthPx * (1f + p)
    // 卡片圆角随展开渐显（关闭 0、全开 24dp），让卡片呈浮起形态
    val cardCornerRadius = 24.dp * FastOutSlowInEasing.transform(pAbs)
    // 卡片真实阴影，投影自然落在底层抽屉上
    val cardElevation = 12.dp * FastOutSlowInEasing.transform(pAbs)

    fun beginDrag() {
        isDragging = true
        settleJob?.cancel()
        dragBaseState = when {
            leftDrawerOpen -> DrawerState.LeftOpen
            rightDrawerOpen -> DrawerState.RightOpen
            else -> DrawerState.Closed
        }
        dragBaseProgress = progress.value
        dragTotalDelta = 0f
        dragDirection = 0f
    }

    fun updateProgress() {
        val newProgress = when (dragBaseState) {
            DrawerState.LeftOpen -> (dragBaseProgress + dragTotalDelta / dragScale).coerceIn(0f, 1f)
            DrawerState.RightOpen -> (dragBaseProgress + dragTotalDelta / dragScale).coerceIn(-1f, 0f)
            DrawerState.Closed -> when {
                dragDirection > 0f -> (dragTotalDelta / dragScale).coerceIn(0f, 1f)
                dragDirection < 0f -> (dragTotalDelta / dragScale).coerceIn(-1f, 0f)
                else -> (dragTotalDelta / dragScale).coerceIn(-1f, 1f)
            }
        }
        scope.launch { progress.snapTo(newProgress) }
    }

    fun settle(velocity: Float) {
        isDragging = false
        val settledTarget = decideSettleTarget(
            baseState = dragBaseState,
            netDelta = dragTotalDelta,
            velocity = velocity,
            threshold = dragThreshold,
        )
        settleJob?.cancel()
        settleJob = scope.launch {
            progress.animateTo(settledTarget, tween(300, easing = FastOutSlowInEasing))
            onLeftDrawerOpenChange(settledTarget > 0f)
            onRightDrawerOpenChange(settledTarget < 0f)
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
                .offset { IntOffset(leftSlide.roundToInt(), 0) }
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
                    if (p > 0.01f) drawContent()
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
                .offset { IntOffset(rightSlide.roundToInt(), 0) }
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
                    if (p < -0.01f) drawContent()
                },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        ) {
            rightDrawer()
        }

        // 3. 内容卡片（顶层，圆角 + 真实阴影随展开渐显）
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(cardOffsetX.roundToInt(), 0) }
                .then(cardDragModifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    // 抽屉开着时点卡片空白处关闭；兜底消费触控阻止穿透
                    if (leftDrawerOpen) onLeftDrawerOpenChange(false)
                    else if (rightDrawerOpen) onRightDrawerOpenChange(false)
                },
            shape = RoundedCornerShape(cardCornerRadius),
            shadowElevation = cardElevation,
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * 方向驱动松手判定：
 * - 位移超小阈值，或快速甩动（|velocity| > 800），即视为有方向信号。
 * - LeftOpen 左滑 → 关闭；RightOpen 右滑 → 关闭。
 * - Closed 右滑 → 开左，左滑 → 开右。
 * - 无方向信号 → 回弹到起始状态。
 */
private fun decideSettleTarget(
    baseState: DrawerState,
    netDelta: Float,
    velocity: Float,
    threshold: Float,
): Float {
    val intendLeft = netDelta < -threshold || (abs(netDelta) <= threshold && velocity < -800f)
    val intendRight = netDelta > threshold || (abs(netDelta) <= threshold && velocity > 800f)
    return when (baseState) {
        DrawerState.LeftOpen -> if (intendLeft) 0f else 1f
        DrawerState.RightOpen -> if (intendRight) 0f else -1f
        DrawerState.Closed -> when {
            intendRight && !intendLeft -> 1f
            intendLeft && !intendRight -> -1f
            else -> 0f
        }
    }
}
