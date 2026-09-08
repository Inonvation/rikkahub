package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog

/**
 * 聊天正文 Markdown 图片的统一排版（方案 A+B 混合，见 docs/image-layout-proposal.html）：
 *
 * - 单图：独立成行，宽度上限为气泡宽的 85%（小图不放大，受 120dp 最小占位约束）；
 * - 连续 ≥2 张：两列方格网格（九宫格风格，ContentScale.Crop 裁切填满，点击看全图）；
 * - 圆角 8dp、图间距 4dp、图文间距 8dp，节奏统一。
 *
 * 不再与文字 FlowRow 混排——图片独立块由两套 Markdown 渲染器（MarkdownNew / Markdown）
 * 在遍历层拆分出来后传入。
 */

/** 一张待渲染的 Markdown 图片（src 为 Markdown 原始引用，渲染时再走 workspace 降级解析） */
data class MarkdownImageRef(val src: String, val alt: String)

private const val SINGLE_IMAGE_WIDTH_FRACTION = 0.85f
private const val GRID_COLUMNS = 2

@Composable
fun MarkdownImageBlock(
    images: List<MarkdownImageRef>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (images.size == 1) {
            SingleMarkdownImage(image = images.single())
        } else {
            ImageGrid(images = images)
        }
    }
}

/** 单图：独立成行，宽度 = min(intrinsic, 气泡宽 × 85%)，高度按比例（缓存命中时首帧即最终高度） */
@Composable
private fun SingleMarkdownImage(image: MarkdownImageRef) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxImageWidth = maxWidth * SINGLE_IMAGE_WIDTH_FRACTION
        ZoomableAsyncImage(
            model = resolveWorkspaceImage(image.src) ?: image.src,
            contentDescription = image.alt.takeIf { it.isNotEmpty() },
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .widthIn(min = 120.dp, max = maxImageWidth),
        )
    }
}

/** 连续多图：两列方格网格，Crop 裁切填满；奇数行末位留空格子保持半宽对齐 */
@Composable
private fun ImageGrid(images: List<MarkdownImageRef>) {
    // 预解析全部 src（resolver 有 TTL 缓存，重组无额外 IO），供整组点击预览
    val resolvedUrls = images.map { resolveWorkspaceImage(it.src) ?: it.src }
    var previewIndex by remember(images) { mutableStateOf(-1) }
    images.chunked(GRID_COLUMNS).forEachIndexed { rowIndex, rowImages ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(GRID_COLUMNS) { col ->
                // chunked 保序：全局下标 = 行号 × 列数 + 列号
                val globalIndex = rowIndex * GRID_COLUMNS + col
                val image = rowImages.getOrNull(col)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    if (image != null) {
                        ZoomableAsyncImage(
                            model = resolvedUrls[globalIndex],
                            contentDescription = image.alt.takeIf { it.isNotEmpty() },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            onClick = { previewIndex = globalIndex },
                        )
                    }
                }
            }
        }
    }
    if (previewIndex >= 0) {
        ImagePreviewDialog(images = resolvedUrls) {
            previewIndex = -1
        }
    }
}
