package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * 工作区图片在聊天正文里的渲染约定。
 *
 * AI 在回复正文写 Markdown 图片链接 `![描述](/workspace/相对路径)`（相对路径是
 * /workspace 工作区根下的路径），界面解析成 workspace 沙箱内的实际文件显示。
 * 这让 AI 能在输出里直接展示工作区图片，而不只局限于工具气泡里 read_file 读出的图。
 *
 * 未提供解析器（非聊天上下文，如导出）或解析失败时，路径保持原样（Coil 加载失败显示占位）。
 */
val LocalWorkspaceImageResolver = staticCompositionLocalOf<(String) -> String?> { { null } }

/** 工作区沙箱内可展示的图片扩展名（与 WorkspaceTools.IMAGE_EXTENSIONS 保持一致） */
private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

/** 判断文件路径是否为工作区可预览的图片（按扩展名，供文件变更点击直接预览等场景复用） */
fun isWorkspaceImagePath(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

/**
 * 构造 /workspace/ 图片路径 → 可加载 Uri 字符串的解析器。
 *
 * @param workspaceManager workspace 管理器（解析 Rootfs 路径用）
 * @param root workspace 磁盘目录名（createWorkspace 时 root = id，故 == workspaceId）
 * @return 输入 Rootfs 路径，输出文件 Uri；root 为空、路径非图片文件、解析失败时返回 null
 */
fun workspaceImageResolver(
    workspaceManager: WorkspaceManager,
    root: String?,
): (String) -> String? {
    if (root.isNullOrBlank()) return { null }
    return { rootfsPath ->
        runCatching {
            val location = workspaceManager.resolveRootfsPath(root, rootfsPath)
            val base = location.rootDir.canonicalFile
            val file = File(base, location.relativePath).canonicalFile
            // 防路径穿越：解析结果必须落在 Rootfs 解析区域内
            if (!file.path.startsWith(base.path + File.separator)) {
                null
            } else if (file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS) {
                file.toUri().toString()
            } else {
                null
            }
        }.getOrNull()
    }
}

/** 是否 workspace 引用（/workspace/ 或 workspace:// 前缀） */
fun isWorkspaceLink(src: String): Boolean =
    src.startsWith("/workspace/") || src.startsWith("workspace://")

/**
 * 解析 workspace 引用成可加载的 file:// URI。
 * 非组合版本：resolver 由组合上下文捕获传入，可在链接点击回调（非组合）中使用。
 * 非 workspace 路径返回 null。
 */
fun resolveWorkspaceImage(src: String, resolver: (String) -> String?): String? {
    if (!isWorkspaceLink(src)) return null
    val rootfsPath = if (src.startsWith("/workspace/")) {
        src
    } else {
        "/workspace/" + src.removePrefix("workspace://").removePrefix("/")
    }
    return resolver(rootfsPath)
}

/** @Composable 便捷版：直接读 LocalWorkspaceImageResolver（用于图片渲染等组合上下文） */
@Composable
fun resolveWorkspaceImage(src: String): String? = resolveWorkspaceImage(src, LocalWorkspaceImageResolver.current)

/**
 * 打开工作区图片预览的入口，由聊天页等提供实现（弹出 ImagePreviewDialog）。
 * 链接点击回调（非组合上下文）需在组合时捕获该引用，点击时再调用。
 */
val LocalOpenWorkspaceImagePreview = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * 打开工作区文件/目录的入口（非图片链接），由聊天页提供实现：
 * 文本/可编辑文件跳转文件编辑器，目录跳转工作区详情并定位。
 * 入参：Rootfs 相对路径（/workspace 下的相对路径）。
 */
val LocalOpenWorkspaceFile = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }
