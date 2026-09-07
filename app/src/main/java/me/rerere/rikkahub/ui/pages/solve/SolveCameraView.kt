package me.rerere.rikkahub.ui.pages.solve

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.FlashOff
import me.rerere.hugeicons.stroke.Image03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "SolveCameraView"

/**
 * 拍照解题的取景主视图（ViewFinder 阶段的全屏内容）：
 * CameraX 实时预览 + 相册/快门/闪光灯，权限引导与相机启动失败兜底。
 *
 * 根因：v1 拍照走系统相机 TakePicture + uCrop，两次跳转且无取景语境；
 * 这里把「对准题目 → 拍下」收拢为应用内一步，拍完直接进框选确认。
 *
 * 绑定策略：DisposableEffect 内异步 bindToLifecycle，随组合离开自动解绑
 * （页面独占相机，dispose 时 unbindAll 避免相机句柄滞留导致再次进入黑屏）。
 */
@Composable
internal fun SolveCameraView(
    onImageCaptured: (Uri) -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toaster = LocalToaster.current
    // 媒体读取（最近照片缩略图）：33+ 用 READ_MEDIA_IMAGES，32- 用 READ_EXTERNAL_STORAGE。
    // 标记为可选权限（required=false）：不授权不影响拍照主流程，仅最近照片缩略图退化为相册图标。
    val mediaReadPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val cameraPermission = rememberPermissionState(
        permissions = setOf(
            PermissionCamera,
            PermissionInfo(
                permission = mediaReadPermission,
                displayName = { Text(stringResource(R.string.permission_media_read)) },
                usage = { Text(stringResource(R.string.permission_media_read_desc)) },
                required = false,
            )
        )
    )
    val mediaGranted = mediaReadPermission in cameraPermission.grantedPermissionNames
    PermissionManager(permissionState = cameraPermission)

    // PreviewView 是平台 View：remember 一次复用同一实例，避免重组反复创建/绑定
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    // 点按对焦指示圈的屏幕位置（null = 无）；双指变焦的当前/最大倍率
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    val haptic = rememberHaptic()
    // 相机绑定失败态：spinner 只表达「启动中」，失败必须独立表达并给出重试入口，
    // 否则 bindToLifecycle 抛异常后用户面对的是永不停转的加载指示
    var bindError by remember { mutableStateOf(false) }
    var bindRetryKey by remember { mutableStateOf(0) }

    val granted = cameraPermission.allRequiredPermissionsGranted

    // 相册最近一张照片的缩略图 uri（媒体权限就绪时查询；否则 null，相册按钮退化为图标）
    var recentPhotoUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaGranted) {
        if (!mediaGranted) {
            recentPhotoUri = null
            return@LaunchedEffect
        }
        recentPhotoUri = withContext(Dispatchers.IO) { queryLatestMediaUri(context) }
    }

    DisposableEffect(lifecycleOwner, granted, bindRetryKey) {
        if (!granted) {
            onDispose { }
        } else {
            bindError = false
            val future = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    val camera: Camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    imageCapture = capture
                    cameraControl = camera.cameraControl
                    hasFlash = camera.cameraInfo.hasFlashUnit()
                    maxZoomRatio = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    zoomRatio = 1f
                    cameraReady = true
                } catch (e: Exception) {
                    Log.e(TAG, "bindToLifecycle failed", e)
                    cameraReady = false
                    bindError = true
                }
            }
            future.addListener(listener, ContextCompat.getMainExecutor(context))
            onDispose {
                if (future.isDone) {
                    runCatching { future.get().unbindAll() }
                }
                imageCapture = null
                cameraControl = null
                cameraReady = false
            }
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        haptic.lightTap()
        capturing = true
        val dir = File(context.cacheDir, "solve_camera").apply { mkdirs() }
        val output = File(dir, "solve_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    capturing = false
                    // 统一转 content://（FileProvider 已覆盖 cacheDir）：
                    // 后续裁切链路走 contentResolver，与相册选图 uri 类型一致
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        output,
                    )
                    onImageCaptured(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    output.delete()
                    Log.e(TAG, "takePicture failed", exception)
                    toaster.show(
                        context.getString(R.string.photo_solve_camera_capture_failed),
                        type = ToastType.Error,
                    )
                }
            },
        )
    }

    fun toggleFlash() {
        val control = cameraControl ?: return
        torchOn = !torchOn
        control.enableTorch(torchOn)
    }

    /** 点按对焦：AF/AE/AWB 三区联动测光，指示圈由 focusPoint 驱动 */
    fun focusAt(pos: Offset) {
        val control = cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
        control.startFocusAndMetering(
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or
                    FocusMeteringAction.FLAG_AE or
                    FocusMeteringAction.FLAG_AWB,
            ).build()
        )
        focusPoint = pos
    }

    /** 双指变焦：以手势增量驱动 setZoomRatio，clamp 到相机能力范围 */
    fun pinchZoom(zoom: Float) {
        val control = cameraControl ?: return
        val next = (zoomRatio * zoom).coerceIn(1f, maxZoomRatio)
        if (next != zoomRatio) {
            zoomRatio = next
            control.setZoomRatio(next)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    // 点按对焦与双指变焦并存：tap 检测不动指即不触发，transform 检测
                    // 在多指缩放时消费事件，二者在各自 pointerInput 中互不干扰
                    .pointerInput(cameraControl) {
                        detectTapGestures { pos -> focusAt(pos) }
                    }
                    .pointerInput(cameraControl) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) pinchZoom(zoom)
                        }
                    },
            )

            // 点按对焦指示圈：出现即对焦开始，1.2s 后淡出（位置取最近一次点按处）
            var lastFocusPos by remember { mutableStateOf(Offset.Zero) }
            focusPoint?.let { lastFocusPos = it }
            LaunchedEffect(focusPoint) {
                if (focusPoint != null) {
                    delay(1200)
                    focusPoint = null
                }
            }
            val ringSize = with(LocalDensity.current) { 72.dp.toPx() }.roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            lastFocusPos.x.roundToInt() - ringSize / 2,
                            lastFocusPos.y.roundToInt() - ringSize / 2,
                        )
                    },
            ) {
                AnimatedVisibility(
                    visible = focusPoint != null,
                    enter = fadeIn(tween(80)) + scaleIn(initialScale = 1.4f, animationSpec = tween(150)),
                    exit = fadeOut(tween(300)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                    )
                }
            }

            // 变焦倍率 chip：变焦后常驻可点（点击回 1x），微超 1x 即隐藏避免取景干扰
            AnimatedVisibility(
                visible = zoomRatio > 1.02f && !bindError,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, bottom = 120.dp),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120)),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.clickable {
                        zoomRatio = 1f
                        cameraControl?.setZoomRatio(1f)
                    },
                ) {
                    Text(
                        text = "%.1fx".format(zoomRatio),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // 相机启动中（bind 未完成）的兜底指示
            AnimatedVisibility(
                visible = !cameraReady && !bindError,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.photo_solve_camera_starting),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            // 相机绑定失败：错误说明 + 重试（重试 key 变化触发 DisposableEffect 重新 bind）
            AnimatedVisibility(
                visible = bindError,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.photo_solve_camera_bind_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Surface(
                        color = Color.White,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.photo_solve_camera_retry),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clickable { bindRetryKey++ }
                                .padding(horizontal = 28.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // 顶部取景引导（状态栏 inset 已由上方顶栏消费，无需再避让）
            AnimatedVisibility(
                visible = cameraReady,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.photo_solve_viewfinder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            // 底部控制条：闪光灯 / 快门 / 相册（右侧相册按钮带最近照片缩略图，交换后与
            // 主流相机布局一致——左工具、中快门、右最近图入口，便于快捷回选刚拍的题图）
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(56.dp),
            ) {
                CameraControlIcon(
                    icon = if (torchOn) HugeIcons.Flash else HugeIcons.FlashOff,
                    contentDescription = stringResource(
                        if (torchOn) R.string.photo_solve_flash_off else R.string.photo_solve_flash_on
                    ),
                    enabled = cameraReady && hasFlash,
                    onClick = ::toggleFlash,
                )
                ShutterButton(
                    enabled = cameraReady && !capturing,
                    capturing = capturing,
                    onClick = ::takePhoto,
                )
                CameraControlIcon(
                    icon = HugeIcons.Image03,
                    contentDescription = stringResource(R.string.photo_solve_from_gallery),
                    enabled = cameraReady,
                    onClick = onPickFromGallery,
                    // 媒体权限就绪时用最近照片缩略图替代纯图标（点击行为仍是打开相册选择）
                    thumbUri = if (mediaGranted) recentPhotoUri else null,
                )
            }
        } else {
            // 权限未授予：全屏引导（PermissionManager 会处理 rationale / 永久拒绝引导）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.photo_solve_camera_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.photo_solve_camera_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Surface(
                    color = Color.White,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.photo_solve_camera_permission_allow),
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable { cameraPermission.requestPermissions() }
                            .padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** 取景引导与顶部悬浮控件外的通用小按钮（相机页白色图标；支持叠加最近照片缩略图） */
@Composable
private fun CameraControlIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    thumbUri: String? = null,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbUri != null) {
                // 最近照片缩略图替代纯图标：圆角小图 + 白色细描边（黑底取景下可辨）
                AsyncImage(
                    model = thumbUri,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.5.dp,
                            color = Color.White.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                )
                // 未就绪时压暗提示
                if (!enabled) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * 快门：外环 + 内圆，经典相机造型。
 * 根因：拍照延迟期间无视觉反馈时用户会以为没拍到而连点，内圆按下缩放表达曝光中。
 */
@Composable
private fun ShutterButton(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    val innerScale by animateFloatAsState(
        targetValue = if (capturing) 0.72f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "shutterInnerScale",
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(58.dp)
                .graphicsLayer {
                    scaleX = innerScale
                    scaleY = innerScale
                }
                .background(
                    if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                    CircleShape,
                ),
        )
    }
}

/**
 * 查询系统相册最近一张图片的 content:// uri（供取景页相册按钮显示缩略图）。
 * 无媒体读取权限 / 查询失败时返回 null（调用方退化为纯图标，不影响主流程）。
 * 在 IO 线程调用。
 */
private fun queryLatestMediaUri(context: Context): String? {
    return try {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0)).toString()
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "query latest media failed", e)
        null
    }
}
