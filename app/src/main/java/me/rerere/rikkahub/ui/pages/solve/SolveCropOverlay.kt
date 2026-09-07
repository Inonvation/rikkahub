package me.rerere.rikkahub.ui.pages.solve

import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CameraRotated01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import kotlin.math.abs

/**
 * 框选确认层（CropConfirm 阶段）：全屏显示拍到的原图，拖拽矩形框选题目区域。
 *
 * 坐标系约定（根因：显示层与裁切层必须同一朝向）：
 * 原图经 coil 按 EXIF 转正显示，VM 裁切前也用 ImageUtils 把 EXIF 旋转烘焙进位图，
 * 两侧朝向一致 → 这里只需在「预览区内图片的渲染矩形（renderedRect，等比 fit）」上操作，
 * 确认时把矩形换算成相对位图的归一化坐标 (0..1) 传给 VM，按像素比例直接映射即可。
 *
 * 视口变换（screen = fit * viewScale + viewOffset，fit 即 scale=1 时的渲染矩形）：
 * 根因：fit 模式下横图在竖屏上只剩半屏大小，四角手柄过小难以微调，且框选体验
 * 与「看清笔画」的目标相悖。因此进入时自动以填满(fill)缩放居中显示，并支持：
 * 双指捏合缩放（以质心为锚点）、单指平移视口、双击在整图(fit)与填满(fill)间切换。
 * 视口偏移被 clamp 在「缩放后图片覆盖视口」的范围内（图片不足以覆盖时回中），
 * 保证框选矩形永远可达、不会落到图片可视范围之外。
 *
 * 框选手势始终工作在 fit 空间（屏幕坐标按视口变换逆映射），与 VM 的归一化映射解耦；
 * 遮罩/框线/手柄绘制在未变换的覆盖层上、以屏幕空间坐标绘制——线宽与手柄尺寸不随缩放变化。
 *
 * 手势：框内拖动 = 平移；四角圆柄 = 单角两维缩放；拖拽始终 clamp 在图片区域内，
 * 并有最小边长约束，防止把脏点误当题目。
 */
@Composable
internal fun SolveCropOverlay(
    imageUri: String?,
    noteText: String,
    modelConfigured: Boolean,
    onNoteChange: (String) -> Unit,
    onConfirm: (RectF) -> Unit,
    onRetake: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minEdgePx = with(density) { 44.dp.toPx() }
    val handleHitPx = with(density) { 26.dp.toPx() }
    // 双击判定间距：当前 ViewConfiguration 无 doubleTapTapSlop，用固定 dp 阈值
    val doubleTapSlopPx = with(density) { 32.dp.toPx() }
    // 拖框自动跟随的边缘感应区宽度与每事件最大平移速度（px/event ≈ 每帧）
    val edgeZonePx = with(density) { 40.dp.toPx() }
    val haptic = rememberHaptic()

    // 预览区尺寸与图片真实宽高比（fit 后等比，renderedRect 与位图同朝向）
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageAspect by remember { mutableStateOf(1f) }
    var imageLoaded by remember { mutableStateOf(false) }

    // 以 imageUri 为 key：CropConfirm 内相册换图时（phase 不变、组合不销毁）重置框选与视口
    var cropLeft by remember(imageUri) { mutableStateOf(0f) }
    var cropTop by remember(imageUri) { mutableStateOf(0f) }
    var cropRight by remember(imageUri) { mutableStateOf(0f) }
    var cropBottom by remember(imageUri) { mutableStateOf(0f) }
    var viewScale by remember(imageUri) { mutableStateOf(1f) }
    var viewOffset by remember(imageUri) { mutableStateOf(Offset.Zero) }

    val renderedRect: Rect? = remember(containerSize, imageAspect, imageLoaded) {
        if (!imageLoaded || containerSize == IntSize.Zero) null
        else fitRect(containerSize, imageAspect)
    }

    fun containerWidth(): Float = containerSize.width.toFloat()
    fun containerHeight(): Float = containerSize.height.toFloat()

    /**
     * clamp 视口偏移：让缩放后的图片尽量覆盖视口。
     * 推导：图片屏幕区间 = [r.left*s+o, r.right*s+o]，要求 left<=0 且 right>=cw
     * → o ∈ [cw - r.right*s, -r.left*s]；区间不存在（缩放后有黑边）时回中，
     * 回中值 = cw*(1-s)/2，与 fit 布局（renderedRect 已居中）在 s=1 处连续。
     */
    fun clampOffset() {
        val r = renderedRect ?: return
        val minX = containerWidth() - r.right * viewScale
        val maxX = -r.left * viewScale
        val minY = containerHeight() - r.bottom * viewScale
        val maxY = -r.top * viewScale
        viewOffset = Offset(
            if (minX <= maxX) viewOffset.x.coerceIn(minX, maxX) else containerWidth() * (1f - viewScale) / 2f,
            if (minY <= maxY) viewOffset.y.coerceIn(minY, maxY) else containerHeight() * (1f - viewScale) / 2f,
        )
    }

    /** 以填满(fill)缩放并居中（进入页面与双击放大共用） */
    fun zoomToFill() {
        val r = renderedRect ?: return
        val scale = (containerWidth() / r.width).coerceAtLeast(containerHeight() / r.height)
        viewScale = scale.coerceAtMost(MAX_VIEW_SCALE)
        val minX = containerWidth() - r.right * viewScale
        val maxX = -r.left * viewScale
        val minY = containerHeight() - r.bottom * viewScale
        val maxY = -r.top * viewScale
        viewOffset = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
    }

    /**
     * 应用视口缩放/平移。缩放锚定质心：保持质心下的 fit 点不动
     * （screen = fit*s + o → o' = c - (c - o) * s'/s），随后叠加平移并 clamp。
     */
    fun applyViewportTransform(zoom: Float, centroid: Offset, pan: Offset) {
        if (renderedRect == null || containerSize == IntSize.Zero) return
        val newScale = (viewScale * zoom).coerceIn(1f, MAX_VIEW_SCALE)
        val actual = newScale / viewScale
        viewOffset = Offset(
            centroid.x - (centroid.x - viewOffset.x) * actual,
            centroid.y - (centroid.y - viewOffset.y) * actual,
        )
        viewScale = newScale
        viewOffset += pan
        clampOffset()
    }

    /** 双击切换：非整图态回 fit（全图概览），整图态切填满缩放 */
    fun toggleZoom() {
        if (viewScale > 1.05f) {
            viewScale = 1f
            viewOffset = Offset.Zero
        } else {
            zoomToFill()
        }
    }

    /**
     * 拖框至视口边缘时的自动跟随平移量（根因：填满放大后要框远端题目，
     * 得「先平移视口、再回来拖框」两步走；指针深入边缘感应区越深平移越快，
     * 方向按「露出指针所指侧的更多内容」推导——右缘 → 视口内容左移 → offset 减小。
     */
    fun autoPanDelta(pos: Offset): Offset {
        if (containerSize == IntSize.Zero) return Offset.Zero
        val cw = containerWidth()
        val ch = containerHeight()
        val rate = 14f
        var dx = 0f
        var dy = 0f
        val overRight = pos.x - (cw - edgeZonePx)
        if (overRight > 0f) dx -= rate * (overRight / edgeZonePx).coerceIn(0f, 1f)
        val overLeft = edgeZonePx - pos.x
        if (overLeft > 0f) dx += rate * (overLeft / edgeZonePx).coerceIn(0f, 1f)
        val overBottom = pos.y - (ch - edgeZonePx)
        if (overBottom > 0f) dy -= rate * (overBottom / edgeZonePx).coerceIn(0f, 1f)
        val overTop = edgeZonePx - pos.y
        if (overTop > 0f) dy += rate * (overTop / edgeZonePx).coerceIn(0f, 1f)
        return Offset(dx, dy)
    }

    // 图片加载/预览区确定后初始化：自动居中放大 + 默认框取「当前可视区域 ∩ 图片」内缩 8%
    // （根因：fit 全图下默认框的用户感知与最终发送内容割裂，按可视区初始化所见即所得）
    LaunchedEffect(renderedRect, imageUri) {
        val r = renderedRect ?: return@LaunchedEffect
        if (cropRight - cropLeft < 1f || cropBottom - cropTop < 1f) {
            zoomToFill()
            val visible = Rect(
                -viewOffset.x / viewScale, -viewOffset.y / viewScale,
                (containerWidth() - viewOffset.x) / viewScale,
                (containerHeight() - viewOffset.y) / viewScale,
            )
            val base = Rect(
                maxOf(visible.left, r.left), maxOf(visible.top, r.top),
                minOf(visible.right, r.right), minOf(visible.bottom, r.bottom),
            )
            if (base.width >= minEdgePx && base.height >= minEdgePx) {
                val insetX = base.width * 0.08f
                val insetY = base.height * 0.08f
                cropLeft = base.left + insetX
                cropTop = base.top + insetY
                cropRight = base.right - insetX
                cropBottom = base.bottom - insetY
            } else {
                cropLeft = r.left
                cropTop = r.top
                cropRight = r.right
                cropBottom = r.bottom
            }
        }
    }

    fun clampCrop(rect: Rect) {
        val min = minOf(minEdgePx, rect.width * 0.5f, rect.height * 0.5f)
        cropLeft = cropLeft.coerceIn(rect.left, cropRight - min)
        cropRight = cropRight.coerceIn(cropLeft + min, rect.right)
        cropTop = cropTop.coerceIn(rect.top, cropBottom - min)
        cropBottom = cropBottom.coerceIn(cropTop + min, rect.bottom)
    }

    /** 命中测试在 fit 空间做：屏幕命中半径 / viewScale，保证手柄热区不随缩放缩小 */
    fun hitMode(pos: Offset): DragMode {
        if (renderedRect == null) return DragMode.None
        val fit = Offset(
            (pos.x - viewOffset.x) / viewScale,
            (pos.y - viewOffset.y) / viewScale,
        )
        val hit = handleHitPx / viewScale
        val crop = Rect(cropLeft, cropTop, cropRight, cropBottom)
        fun near(px: Float, py: Float, hx: Float, hy: Float): Boolean =
            abs(px - hx) <= hit && abs(py - hy) <= hit
        return when {
            near(fit.x, fit.y, crop.left, crop.top) -> DragMode.ResizeTopLeft
            near(fit.x, fit.y, crop.right, crop.top) -> DragMode.ResizeTopRight
            near(fit.x, fit.y, crop.left, crop.bottom) -> DragMode.ResizeBottomLeft
            near(fit.x, fit.y, crop.right, crop.bottom) -> DragMode.ResizeBottomRight
            crop.contains(fit) -> DragMode.Move
            else -> DragMode.None
        }
    }

    fun applyCropDrag(mode: DragMode, deltaFit: Offset) {
        when (mode) {
            DragMode.Move -> {
                cropLeft += deltaFit.x
                cropRight += deltaFit.x
                cropTop += deltaFit.y
                cropBottom += deltaFit.y
            }
            DragMode.ResizeTopLeft -> {
                cropLeft += deltaFit.x
                cropTop += deltaFit.y
            }
            DragMode.ResizeTopRight -> {
                cropRight += deltaFit.x
                cropTop += deltaFit.y
            }
            DragMode.ResizeBottomLeft -> {
                cropLeft += deltaFit.x
                cropBottom += deltaFit.y
            }
            DragMode.ResizeBottomRight -> {
                cropRight += deltaFit.x
                cropBottom += deltaFit.y
            }
            DragMode.None -> Unit
        }
        renderedRect?.let { clampCrop(it) }
    }

    // 双击检测用的跨手势状态
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 预览区（黑底，图片按视口变换显示）----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                // clipToBounds（根因：graphicsLayer 缩放后的图片会溢出布局边界绘制，
                // 盖住上方顶栏与下方操作面板；裁剪到容器边界即消除）
                .clipToBounds()
                .onSizeChanged { containerSize = it }
                .pointerInput(renderedRect) {
                    // 单一手势循环统一裁决：多指/缩放事件 → 视口变换（框选拖拽中第二根
                    // 手指落下即接管，mode 归 None 防止抬指后跳变）；
                    // 单指 → down 时命中测试决定走框选拖拽还是视口平移，越过 touchSlop 才生效。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startTime = down.uptimeMillis
                        var mode = hitMode(down.position)
                        // 抓住框/手柄给轻触感，确认手势已被识别
                        if (mode != DragMode.None) haptic.lightTap()
                        var multiTouch = false
                        var moved = false
                        var slopDistance = 0f
                        val touchSlop = viewConfiguration.touchSlop
                        var lastUptime = startTime
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            lastUptime = event.changes.maxOf { it.uptimeMillis }
                            val zoomChange = event.calculateZoom()
                            if (event.changes.size > 1 || zoomChange != 1f) {
                                multiTouch = true
                                mode = DragMode.None
                                event.changes.forEach { it.consume() }
                                applyViewportTransform(
                                    zoom = zoomChange,
                                    centroid = event.calculateCentroid(useCurrent = false),
                                    pan = event.calculatePan(),
                                )
                            } else {
                                val change = event.changes.first()
                                // 未消费前的位移 = positionChange（该版本 API 差异，直接做差更稳）
                                val pan = change.position - change.previousPosition
                                slopDistance += offsetDistance(pan)
                                if (slopDistance > touchSlop) moved = true
                                if (moved) {
                                    change.consume()
                                    if (mode != DragMode.None) {
                                        applyCropDrag(mode, pan / viewScale)
                                        // 指针贴近视口边缘时自动跟随平移，框远端题目不必两步走
                                        val autoPan = autoPanDelta(change.position)
                                        if (autoPan != Offset.Zero) {
                                            applyViewportTransform(1f, change.position, autoPan)
                                        }
                                    } else {
                                        applyViewportTransform(1f, change.position, pan)
                                    }
                                }
                            }
                        }
                        // 双击：无拖动、无多指、单击时长内，且与上一次点击在双击间距内
                        if (!multiTouch && !moved &&
                            lastUptime - startTime <= viewConfiguration.doubleTapTimeoutMillis
                        ) {
                            val withinSlop = offsetDistance(down.position - lastTapPosition) <= doubleTapSlopPx
                            if (lastTapTime > 0 &&
                                lastUptime - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                                withinSlop
                            ) {
                                lastTapTime = 0L
                                toggleZoom()
                            } else {
                                lastTapTime = lastUptime
                                lastTapPosition = down.position
                            }
                        }
                    }
                },
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = stringResource(R.string.photo_solve_crop_image_desc),
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        val success = state as? AsyncImagePainter.State.Success
                        if (success != null) {
                            val intrinsic = success.painter.intrinsicSize
                            if (intrinsic.width > 0f && intrinsic.height > 0f) {
                                imageAspect = intrinsic.width / intrinsic.height
                            }
                            imageLoaded = true
                        } else if (state is AsyncImagePainter.State.Error) {
                            imageLoaded = false
                        }
                    },
                    // 视口变换：screen = fit * scale + offset（原点左上，offset 即视口偏移）
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = viewScale
                            scaleY = viewScale
                            translationX = viewOffset.x
                            translationY = viewOffset.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                )
            }

            val r = renderedRect
            if (r != null) {
                val crop = Rect(cropLeft, cropTop, cropRight, cropBottom)
                // Canvas 的 onDraw 非 Composable 上下文，主题色需提前取出
                val handleAccent = MaterialTheme.colorScheme.primary
                val handleAccentText = MaterialTheme.colorScheme.onPrimary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // fit 空间 → 屏幕空间（与 graphicsLayer 的视口变换一致）
                    fun toScreenRect(rect: Rect): Rect = Rect(
                        rect.left * viewScale + viewOffset.x,
                        rect.top * viewScale + viewOffset.y,
                        rect.right * viewScale + viewOffset.x,
                        rect.bottom * viewScale + viewOffset.y,
                    )
                    val cropScreen = toScreenRect(crop)
                    // 遮罩压暗「框选矩形之外」的全部区域（根因：原先只压暗图片外黑边，
                    // 框内与图片其余部分同样高亮，用户感知不到框住的到底是哪一段；
                    // 框外压暗让已框选部分从整图中直接跳出来，这也是各类裁剪器的通用范式）
                    val dim = Color.Black.copy(alpha = 0.62f)
                    drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, cropScreen.top))
                    drawRect(dim, topLeft = Offset(0f, cropScreen.bottom), size = Size(size.width, size.height - cropScreen.bottom))
                    drawRect(dim, topLeft = Offset(0f, cropScreen.top), size = Size(cropScreen.left, cropScreen.height))
                    drawRect(dim, topLeft = Offset(cropScreen.right, cropScreen.top), size = Size(size.width - cropScreen.right, cropScreen.height))
                    // 框线：主色粗框（白纸可见）叠加内侧 1.5dp 白高光（暗背景可见）
                    // 绘制在未变换覆盖层，线宽/手柄为屏幕空间常量，不随缩放变粗
                    val borderPx = 3.5.dp.toPx()
                    drawRect(handleAccent, topLeft = cropScreen.topLeft, size = cropScreen.size, style = Stroke(borderPx))
                    drawRect(Color.White, topLeft = cropScreen.topLeft, size = cropScreen.size, style = Stroke(1.5.dp.toPx()))
                    // 四角手柄：白外圈（暗侧可见）+ 主色内芯 + 主色细描边（白纸可见）
                    val radius = 9.dp.toPx()
                    val handles = listOf(
                        Offset(cropScreen.left, cropScreen.top),
                        Offset(cropScreen.right, cropScreen.top),
                        Offset(cropScreen.left, cropScreen.bottom),
                        Offset(cropScreen.right, cropScreen.bottom),
                    )
                    handles.forEach { h ->
                        drawCircle(Color.White, radius = radius, center = h)
                        drawCircle(
                            handleAccentText.copy(alpha = 0.35f),
                            radius = radius,
                            center = h,
                            style = Stroke(2.dp.toPx()),
                        )
                        drawCircle(handleAccent, radius = radius * 0.55f, center = h)
                    }
                }
            }
        }

        // ---- 底部操作面板：divider 与预览区分行，去除整块悬浮面板感 ----
        // 布局重构（根因：原面板纵向堆叠引导/错误/输入/按钮四类元素、输入框还"卡中卡"；
        // 现改为两行满载——状态提示与工具图标同排、补充说明输入与「解题」主操作同排）。
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // safeDrawing.Bottom = 导航条与 IME 的并集：键盘弹出时面板随之上移，
                        // 补充说明输入框不被键盘遮挡（原 navigationBarsPadding 不含 IME，会被盖住）
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 状态行：默认引导文案；模型未配置时替换为错误提示（互斥占一行，不叠行）
                    // （根因：此前仅靠确认按钮禁用表达，用户无从得知原因；顶栏本就有模型
                    //   选择器，提示给出就地可完成的动作）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (!modelConfigured) R.string.photo_solve_crop_model_hint
                                else R.string.photo_solve_crop_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!modelConfigured) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 工具图标保留默认最小尺寸（48dp 命中区），不再缩成小图标
                        IconButton(onClick = onRetake) {
                            Icon(
                                imageVector = HugeIcons.CameraRotated01,
                                contentDescription = stringResource(R.string.photo_solve_crop_retake),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onPickFromGallery) {
                            Icon(
                                imageVector = HugeIcons.Image03,
                                contentDescription = stringResource(R.string.photo_solve_from_gallery),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 主行：补充说明输入（选填，如「第 3 问」「求详细步骤」）+ 「解题」同排，
                    // 唯一输入与唯一主操作在同一视觉层，缩短操作路径
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                BasicTextField(
                                    value = noteText,
                                    onValueChange = onNoteChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                        .requiredHeightIn(min = 0.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 1,
                                    decorationBox = { inner ->
                                        Box {
                                            if (noteText.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.photo_solve_note_placeholder),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val rect = renderedRect ?: return@Button
                                if (rect.width <= 0f || rect.height <= 0f) return@Button
                                val crop = Rect(cropLeft, cropTop, cropRight, cropBottom)
                                val norm = RectF(
                                    ((crop.left - rect.left) / rect.width).coerceIn(0f, 1f),
                                    ((crop.top - rect.top) / rect.height).coerceIn(0f, 1f),
                                    ((crop.right - rect.left) / rect.width).coerceIn(0f, 1f),
                                    ((crop.bottom - rect.top) / rect.height).coerceIn(0f, 1f),
                                )
                                // 确认解题：中档触感，与抓取手柄的轻档区分
                                haptic.tap()
                                onConfirm(norm)
                            },
                            // 模型未配置不禁用：点击走 VM 的报错提示闭环，禁用会让用户无从排查
                            enabled = imageLoaded && renderedRect != null,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Text(stringResource(R.string.photo_solve_crop_confirm))
                        }
                    }
                }
            }
        }
    }
}

/** Offset 模长（该 Compose 版本无 Offset.getDistance 扩展，手动计算） */
private fun offsetDistance(offset: Offset): Float =
    kotlin.math.sqrt(offset.x * offset.x + offset.y * offset.y)

/** 预览区内图片等比 fit 的渲染矩形 */
private fun fitRect(container: IntSize, aspect: Float): Rect {
    val cw = container.width.toFloat()
    val ch = container.height.toFloat()
    val containerAspect = cw / ch
    return if (aspect > containerAspect) {
        // 图更宽：宽满，上下留黑
        val h = cw / aspect
        Rect(0f, (ch - h) / 2f, cw, (ch + h) / 2f)
    } else {
        // 图更高：高满，左右留黑
        val w = ch * aspect
        Rect((cw - w) / 2f, 0f, (cw + w) / 2f, ch)
    }
}

/** 视口最大缩放倍数（相对 fit 基准），过大会把位图放糊且无框选收益 */
private const val MAX_VIEW_SCALE = 8f

/** 拖拽模式：框内平移 / 四角缩放 / 无命中（无命中时单指拖动 = 平移视口） */
private enum class DragMode {
    None, Move, ResizeTopLeft, ResizeTopRight, ResizeBottomLeft, ResizeBottomRight,
}
