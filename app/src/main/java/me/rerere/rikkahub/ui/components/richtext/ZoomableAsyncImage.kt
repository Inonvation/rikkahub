package me.rerere.rikkahub.ui.components.richtext

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 进程级图片尺寸缓存：url → 解码后图像像素尺寸（Coil 按约束解码，比例与源图一致）。
 *
 * 根因背景：气泡里的 markdown 图片在加载前只有 min 120dp 占位，加载完成后撑到图片尺寸
 * —— LazyColumn item 高度突变导致列表跳动 + 图片"闪现"；滚动历史时每个图片 item
 * 重建都重演一次。缓存命中后用 aspectRatio 预占比例尺寸，回看时首帧即最终高度。
 * 首次加载（无缓存）仍会撑开一次，属不可避免的一次性成本。
 */
private val imageSizeCache = LruCache<String, IntSize>(256)

/** 流式生成中文件「迟早出现」：失败后自动重试的次数上限与退避基数（3s/6s/12s/24s） */
private const val MAX_AUTO_RETRIES = 4
private const val AUTO_RETRY_BASE_MS = 3_000L

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    onClick: (() -> Unit)? = null,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    // 加载失败可视化 + 重试：静默占位会让「AI 路径拼错 / 文件缺失」看起来像 App 坏了。
    // 状态全部以 model 为 key：流式生成中 resolver 可能先返回 null（图片尚未落盘）、
    // 文件出现后返回 file:// —— model 变化时必须重置 failed/加载状态，否则错误框永久卡死。
    // 流式场景下文件「迟早出现」，失败后按指数退避自动重试（3/6/12/24s，共 4 次）；
    // 超过后停在错误框等手动点击，避免真坏路径无限轮询。
    var loading by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    var retryKey by remember(model) { mutableIntStateOf(0) }
    var autoRetries by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(failed, model) {
        if (failed && autoRetries < MAX_AUTO_RETRIES) {
            delay(AUTO_RETRY_BASE_MS shl autoRetries)
            autoRetries++
            failed = false
            loading = true
            retryKey++
        }
    }
    if (failed) {
        Box(
            modifier = modifier
                .heightIn(min = 60.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    MaterialTheme.shapes.small,
                )
                .clickable {
                    failed = false
                    loading = true
                    retryKey++
                    autoRetries = 0
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = buildString {
                    append(if (autoRetries < MAX_AUTO_RETRIES) "图片加载中，稍候自动重试" else "图片加载失败")
                    if (!model.isNullOrBlank()) {
                        append("\n")
                        append(model)
                    }
                    if (retryKey > 0) append("\n(已重试 $retryKey 次，点击再试)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        return
    }
    // 尺寸预占：命中缓存时首帧布局即最终尺寸（宽度取 min(源图宽, 气泡宽)，高度按比例），
    // 小图不放大、大图 fit 宽度，与 ContentScale.Fit 的最终呈现一致
    var cachedSize by remember(model) { mutableStateOf(model?.let { imageSizeCache.get(it) }) }
    val sizePreallocModifier = cachedSize?.takeIf { it.width > 0 && it.height > 0 }?.let { size ->
        with(density) {
            val intrinsicWidthDp = size.width.toFloat().toDp()
            Modifier
                .widthIn(max = intrinsicWidthDp)
                .aspectRatio(size.width.toFloat() / size.height)
        }
    } ?: Modifier
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        // 滚动回看时先查 memory cache 作为 placeholder：命中则首帧直接出图，
        // 避免「placeholder 一帧 → 正图一帧」的闪现
        .placeholderMemoryCacheKey(model)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    // key(retryKey) 在点击重试时强制重建 AsyncImage 子树：Coil3 的请求无参数差异化 API，
    // 靠组合重建让 painter 携带新请求重新执行（Coil 不缓存失败结果，重试即重新解码/下载）
    key(retryKey) {
        AsyncImage(
            model = coilModel,
            contentDescription = contentDescription,
            modifier = modifier
                .then(sizePreallocModifier)
                .shimmer(isLoading = loading)
                .clickable {
                    // 网格/组合场景由调用方接管点击（如整组预览）；null 时退回单张预览
                    if (onClick != null) onClick() else showImageViewer = true
                },
            contentScale = contentScale,
            alpha = alpha,
            alignment = alignment,
            onLoading = {
                loading = true
            },
            onSuccess = { state ->
                loading = false
                failed = false
                // 记录源图 intrinsic 尺寸（像素），供后续组合预占高度；缩略尺寸不含旋转信息
                if (model != null && cachedSize == null) {
                    val image = state.result.image
                    if (image.width > 0 && image.height > 0) {
                        val size = IntSize(image.width, image.height)
                        cachedSize = size
                        imageSizeCache.put(model, size)
                    }
                }
            },
            onError = {
                loading = false
                failed = true
            },
        )
    }
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
