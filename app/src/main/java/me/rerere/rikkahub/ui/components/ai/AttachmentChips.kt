package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.koin.compose.koinInject

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
) {
    val filesManager: FilesManager = koinInject()
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    // 预览器打开的附件下标（messageContent 下标）；null 表示未打开。
    // 附件重排/删除后 messageContent 变化，这里只负责"显示哪个预览"与越界收敛。
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.messageContent.size) {
        val current = previewIndex ?: return@LaunchedEffect
        if (state.messageContent.isEmpty()) {
            previewIndex = null
        } else if (current > state.messageContent.lastIndex) {
            previewIndex = state.messageContent.lastIndex
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        // 多附件时每个 chip 带 1-based 序号徽章：序号即附件在 messageContent 中的位置，
        // 也就是发送给模型时图片/文件的先后顺序，用于修正"系统相册返回顺序≠点选顺序"的错位感知。
        val showBadge = state.messageContent.size > 1
        state.messageContent.forEachIndexed { index, part ->
            val badgeNumber = if (showBadge) index + 1 else null
            when (part) {
                is UIMessagePart.Image -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        badgeNumber = badgeNumber,
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                AsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        onPreview = { previewIndex = index },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        badgeNumber = badgeNumber,
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onPreview = { previewIndex = index },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        badgeNumber = badgeNumber,
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onPreview = { previewIndex = index },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        badgeNumber = badgeNumber,
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onPreview = { previewIndex = index },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }

    val parts = state.messageContent
    val startIndex = previewIndex
    if (startIndex != null && startIndex in parts.indices) {
        AttachmentPreviewDialog(
            parts = parts,
            initialIndex = startIndex,
            onMove = { from, to -> state.moveAttachment(from, to) },
            onRemove = { index ->
                if (index in parts.indices) {
                    val part = parts[index]
                    removePart(part, part.attachmentUrl())
                }
            },
            onDismiss = { previewIndex = null },
        )
    }
}

/**
 * 待发送附件全屏预览器（黑底 pager）。
 * 用于：核对附件顺序、左右滑动查看、点击"前移/后移"调整顺序、删除。
 * 数据源是调用方传入的 parts（与 messageContent 同引用），删除/调序后由调用方更新列表，
 * 本组件通过引用跟踪当前预览项，列表变化后自动定位到该项的新位置；若当前项被删除则顺延到相邻项。
 */
@Composable
private fun AttachmentPreviewDialog(
    parts: List<UIMessagePart>,
    initialIndex: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var tracked by remember { mutableStateOf(parts[initialIndex]) }
    val pagerState = rememberPagerState(initialPage = initialIndex) { parts.size }

    // 列表变化（外部删除/调序）后：当前预览项仍存在则跟随其新位置；被删除则顺延到当前页相邻项
    LaunchedEffect(parts) {
        if (parts.isEmpty()) return@LaunchedEffect
        val idx = parts.indexOfFirst { it === tracked }
        val target = if (idx >= 0) {
            idx
        } else {
            tracked = parts[pagerState.currentPage.coerceIn(parts.indices)]
            pagerState.currentPage.coerceIn(parts.indices)
        }
        if (target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val currentPage = pagerState.currentPage
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                PreviewPageContent(part = parts[page])
            }

            // 顶栏：关闭 + 当前序号（仅在多个附件时展示计数，单张不显示以免噪音）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (parts.size > 1) {
                    Text(
                        text = "${currentPage + 1} / ${parts.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                // 右侧占位与关闭按钮视觉对称，避免序号整体偏左
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            // 底部操作排：前移 / 删除 / 后移（顺序即发送顺序）
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewActionButton(
                    enabled = currentPage > 0,
                    icon = HugeIcons.ArrowLeft01,
                    onClick = { onMove(currentPage, currentPage - 1) },
                )
                PreviewActionButton(
                    enabled = true,
                    icon = HugeIcons.Delete01,
                    onClick = { onRemove(currentPage) },
                )
                PreviewActionButton(
                    enabled = currentPage < parts.lastIndex,
                    icon = HugeIcons.ArrowRight01,
                    onClick = { onMove(currentPage, currentPage + 1) },
                )
            }
        }
    }
}

@Composable
private fun PreviewPageContent(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Image -> {
            AsyncImage(
                model = part.url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 24.dp),
            )
        }

        else -> {
            val (icon, name) = when (part) {
                is UIMessagePart.Video -> HugeIcons.Video01 to "视频"
                is UIMessagePart.Audio -> HugeIcons.MusicNote03 to "音频"
                is UIMessagePart.Document -> HugeIcons.Files02 to part.fileName
                else -> HugeIcons.Files02 to "附件"
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    text = name,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                        .widthIn(max = 320.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewActionButton(
    enabled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    badgeNumber: Int?,
    leading: @Composable () -> Unit,
    onPreview: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(
                    enabled = onPreview != null,
                    // 点开全屏预览核对顺序/内容；删除按钮在内层独立消费点击，不会触发预览
                    onClick = { onPreview?.invoke() },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.size(34.dp)) {
                leading()
                if (badgeNumber != null) {
                    // 序号徽章叠在缩略图/图标左上角，标出"第几张选择/第几个发送"
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(18.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 1.dp,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$badgeNumber",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun UIMessagePart.attachmentUrl(): String {
    return when (this) {
        is UIMessagePart.Image -> url
        is UIMessagePart.Video -> url
        is UIMessagePart.Audio -> url
        is UIMessagePart.Document -> url
        else -> ""
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}
