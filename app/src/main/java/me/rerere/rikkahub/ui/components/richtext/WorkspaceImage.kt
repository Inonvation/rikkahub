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
 * 根因背景：模型经常把路径拼错（相对 cwd 写、多写/少写前缀、URL 编码、携带 query 等），
 * 严格匹配失败会导致气泡图片永远占位空白。解析层按
 * 「规范化 → cwd 相对 → workspace 根相对 → 文件名唯一模糊匹配」逐级降级兜底，
 * 见 [buildWorkspaceImageCandidates] 与 [workspaceImageResolver]。
 *
 * 未提供解析器（非聊天上下文，如导出）或解析失败时，路径保持原样（Coil 显示加载失败提示）。
 */
val LocalWorkspaceImageResolver = staticCompositionLocalOf<(String) -> String?> { { null } }

/** workspace 沙箱内可展示的图片扩展名（与 WorkspaceTools.IMAGE_EXTENSIONS 保持一致） */
private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

/** 判断文件路径是否为工作区可预览的图片（按扩展名，供文件变更点击直接预览等场景复用） */
fun isWorkspaceImagePath(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

/**
 * 构造 Markdown 图片 src → 可加载 Uri 字符串的解析器（含拼错路径降级链）。
 *
 * @param workspaceManager workspace 管理器（解析 Rootfs 路径用）
 * @param root workspace 磁盘目录名（createWorkspace 时 root = id，故 == workspaceId）
 * @param cwd 会话当前工作目录（Rootfs 绝对路径，如 /workspace/project）；
 *   AI 用相对路径引用图片时优先按它解析——模型的多半站在 cwd 视角（shell 工具都按 cwd 解析）
 * @return 输入图片 src，输出文件 Uri；root 为空、带非 file scheme 或解析失败时返回 null
 */
fun workspaceImageResolver(
    workspaceManager: WorkspaceManager,
    root: String?,
    cwd: String? = null,
): (String) -> String? {
    if (root.isNullOrBlank()) return { null }
    // /workspace 区域根目录（模糊匹配的遍历起点），构造时解析一次
    val workspaceRootDir = runCatching {
        val location = workspaceManager.resolveRootfsPath(root, WorkspaceManager.ROOTFS_WORKSPACE_DIR)
        File(location.rootDir, location.relativePath).canonicalFile
    }.getOrNull()
    return ResolverCache(cwd, workspaceRootDir) { rootfsPath ->
        resolveRootfsImageFile(workspaceManager, root, rootfsPath)
    }
}

/**
 * 解析降级链执行体（含结果缓存）：
 * 1. 规范化 src 产出候选路径，逐个精确解析（cwd 相对候选优先）；
 * 2. 全部失败时取文件名做唯一模糊匹配（多命中视为歧义，放弃）；
 * 3. 结果带 TTL 缓存——同一 src 每次重组都会重新解析，必须缓存；
 *    成功结果 TTL 30s；失败结果 TTL 5s——流式生成中图片常在正文之后才落盘，
 *    失败若也缓存 30s，文件出现后气泡会多等半分钟才自愈。
 */
private class ResolverCache(
    private val cwd: String?,
    private val workspaceRootDir: File?,
    private val resolve: (String) -> String?,
) : Function1<String, String?> {
    private val results = HashMap<String, Pair<Long, String?>>()
    private var nameIndex: Pair<Long, Map<String, List<String>>>? = null

    override fun invoke(src: String): String? {
        // 带 scheme 的引用（http/data/content/file 等）不经过 workspace 沙箱，交给 Coil 原样处理
        if (NON_FILE_URI_SCHEME.containsMatchIn(src)) return null
        val now = System.currentTimeMillis()
        results[src]?.let { (ts, v) ->
            if (now - ts < (if (v == null) FAILURE_TTL_MS else TTL_MS)) return v
            results.remove(src)
        }
        val resolved = resolveWithFallback(src, cwd) { name -> nameIndexOf(now)[name] }
        if (results.size > MAX_RESULT_ENTRIES) results.clear()
        results[src] = now to resolved
        return resolved
    }

    private fun resolveWithFallback(
        src: String,
        cwd: String?,
        nameIndex: (String) -> List<String>?,
    ): String? {
        val candidates = buildWorkspaceImageCandidates(src, cwd)
        candidates.firstNotNullOfOrNull { resolve(it) }?.let { return it }

        // 模糊匹配兜底：workspace 根下按文件名找；唯一命中才采用，避免同名歧义
        val name = candidates.lastOrNull()?.substringAfterLast('/')?.lowercase() ?: return null
        if (name.isBlank() || '.' !in name) return null
        val hits = nameIndex(name) ?: return null
        if (hits.size != 1) return null
        return resolve(hits.single())
    }

    /** 构建/复用 /workspace 区域的图片文件名索引（rootfs 路径为值），TTL 同结果缓存 */
    private fun nameIndexOf(now: Long): Map<String, List<String>> {
        nameIndex?.let { (ts, idx) -> if (now - ts < TTL_MS) return idx }
        val index = if (workspaceRootDir?.isDirectory == true) {
            buildWorkspaceImageNameIndex(workspaceRootDir)
        } else {
            emptyMap()
        }
        nameIndex = now to index
        return index
    }
}

private const val TTL_MS = 30_000L
private const val FAILURE_TTL_MS = 5_000L
private const val MAX_RESULT_ENTRIES = 512
private const val MAX_INDEX_DEPTH = 6
private const val MAX_INDEX_FILES = 5000

/** http:/data:/content:/file: 等 scheme 引用不走 workspace 沙箱解析（workspace: 除外，已在前缀归一处理） */
private val NON_FILE_URI_SCHEME = Regex("^(?!workspace:)[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * Markdown 图片 src → Rootfs 候选路径列表（纯字符串处理，JVM 可测）。
 *
 * 候选顺序：绝对引用（/workspace/... 或 workspace: 前缀或其他 Rootfs 区域绝对路径）单候选——
 * 明确写出的绝对路径不插 cwd 候选，避免 cwd 下同名文件劫持；
 * 相对写法为 [cwd 拼接, workspace 根拼接] 两候选（cwd 优先）。
 */
internal fun buildWorkspaceImageCandidates(src: String, cwd: String?): List<String> {
    val normalized = normalizeWorkspaceImageSrc(src) ?: return emptyList()
    val isAbsoluteRef = normalized.startsWith("/") ||
        src.trim().let { it.startsWith("/") || it.startsWith("workspace:", ignoreCase = true) }
    return when {
        normalized.startsWith("/") -> listOf(normalized)
        isAbsoluteRef -> listOf(joinRootfsPath(WorkspaceManager.ROOTFS_WORKSPACE_DIR, normalized))
        else -> buildList {
            if (!cwd.isNullOrBlank()) add(joinRootfsPath(cwd, normalized))
            add(joinRootfsPath(WorkspaceManager.ROOTFS_WORKSPACE_DIR, normalized))
        }.distinct()
    }
}

/**
 * 规范化 Markdown 图片 src（纯字符串处理，JVM 可测）：
 * - 去掉 URL query/fragment（模型常拼 `xxx.gif?v=1`）
 * - 仅解 percent 编码（不解 `+`，路径中 + 是合法字符）
 * - 前缀别名归一：`workspace://`、`workspace:/`、`workspace:`、`/workspaces/`（模型常见笔误）
 * - 压缩重复斜杠、丢弃 `.` 段（`..` 段保留，交由 canonicalFile + 防穿越校验处理）
 * - `/workspace/...` 前缀剥离成 bare 相对路径；其他绝对路径原样保留；相对路径原样返回
 * - 带 scheme 的引用（http:/data:/file: 等）返回 null，交给 Coil 原样处理
 */
internal fun normalizeWorkspaceImageSrc(src: String): String? {
    var s = src.trim()
    if (s.isEmpty()) return null
    // workspace 引用之外，带 scheme 的 URL 一律不是沙箱路径（Windows 盘符 C:/ 同样命中）
    if (!s.startsWith("/") && !s.startsWith("workspace", ignoreCase = true)) {
        val schemeEnd = s.indexOf(':')
        if (schemeEnd in 1..16 && s.take(schemeEnd).all { it.isLetterOrDigit() || it in "+-." }) return null
    }
    s = s.substringBefore('#').substringBefore('?')
    s = decodePercent(s)
    // 前缀别名统一映射成 /workspace/ 形式；workspace:/ 与 workspace: 是同义（Rootfs 绝对 = workspace 根相对）
    s = when {
        s.startsWith("workspace://", ignoreCase = true) -> "/workspace/" + s.removePrefixIgnoreCase("workspace://")
        s.startsWith("workspace:/", ignoreCase = true) -> "/workspace/" + s.removePrefixIgnoreCase("workspace:/")
        s.startsWith("workspace:", ignoreCase = true) -> "/workspace/" + s.removePrefixIgnoreCase("workspace:")
        s.startsWith("/workspaces/", ignoreCase = true) -> "/workspace" + s.removePrefixIgnoreCase("/workspaces")
        else -> s
    }
    s = s.replace(Regex("/{2,}"), "/")

    val isAbsolute = s.startsWith("/")
    val kept = ArrayList<String>()
    for (seg in s.split('/')) {
        when {
            seg.isEmpty() || seg == "." -> Unit
            seg == ".." -> if (kept.isNotEmpty() && kept.last() != "..") kept.removeAt(kept.size - 1) else kept.add(seg)
            else -> kept.add(seg)
        }
    }
    val joined = kept.joinToString("/")
    if (joined.isEmpty()) return null
    val path = (if (isAbsolute) "/" else "") + joined
    return when {
        // /workspace 前缀剥离为 bare（workspace 根相对），便于 cwd/根两级候选；
        // 消解 `..` 之后再剥，保证 /workspace/imgs/../a.gif 也能落到 bare
        path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
        path == "/workspace" -> null
        else -> path
    }
}

/** cwd/bare 的 Rootfs 路径拼接；bare 为绝对路径时直接返回 */
internal fun joinRootfsPath(cwd: String, bare: String): String {
    if (bare.startsWith("/")) return bare
    val base = cwd.trimEnd('/')
    return "$base/$bare"
}

/** 大小写不敏感的去前缀（startsWith(ignoreCase) 与 removePrefix 必须配套，否则大写前缀会归一失败） */
private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (length >= prefix.length && substring(0, prefix.length).equals(prefix, ignoreCase = true)) {
        substring(prefix.length)
    } else {
        this
    }

/** 仅解码 %xx（连续 %xx 攒成字节段按 UTF-8 解码），不处理 `+`；非法序列按替换字符落地 */
private fun decodePercent(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    val bytes = ArrayList<Byte>(4)
    fun flush() {
        if (bytes.isEmpty()) return
        out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        bytes.clear()
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 <= s.lastIndex) {
            val hi = s[i + 1].digitToIntOrNull(16)
            val lo = s[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        flush()
        out.append(c)
        i++
    }
    flush()
    return out.toString()
}

/** Rootfs 路径 → 沙箱内图片文件的 Uri；非图片/不存在/穿越时返回 null */
private fun resolveRootfsImageFile(
    workspaceManager: WorkspaceManager,
    root: String,
    rootfsPath: String,
): String? = runCatching {
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

/**
 * 构建 /workspace 区域「文件名(小写) → rootfs 路径列表」索引，供唯一模糊匹配兜底。
 * 深度 [MAX_INDEX_DEPTH]、文件数 [MAX_INDEX_FILES] 有上限，避免大工作区拖慢渲染线程；
 * 跳过回收站 .trash；遍历在渲染线程同步执行（一次性，且有 TTL 缓存），控制在上限内可接受。
 */
private fun buildWorkspaceImageNameIndex(rootDir: File): Map<String, List<String>> {
    val index = HashMap<String, MutableList<String>>()
    var count = 0
    runCatching {
        val walk = rootDir.walkTopDown().maxDepth(MAX_INDEX_DEPTH).onFail { _, _ -> /* 忽略不可读条目 */ }
        val iter = walk.iterator()
        while (iter.hasNext()) {
            if (count >= MAX_INDEX_FILES) break
            val file = iter.next()
            if (!file.isFile) continue
            val relative = file.relativeToOrNull(rootDir)?.invariantSeparatorsPath ?: continue
            if (relative.startsWith(".trash/")) continue
            if (file.extension.lowercase() !in IMAGE_EXTENSIONS) continue
            index.getOrPut(file.name.lowercase()) { mutableListOf() }.add("/workspace/$relative")
            count++
        }
    }
    return index
}

/** 是否 workspace 引用（/workspace/ 或 workspace:// 前缀） */
fun isWorkspaceLink(src: String): Boolean =
    src.startsWith("/workspace/") || src.startsWith("workspace://")

/**
 * 解析 workspace/相对路径引用成可加载的 file:// URI（含拼错路径降级链，见 [workspaceImageResolver]）。
 * 组合上下文渲染图片时使用：相对路径也交给 resolver 降级（AI 常相对 cwd 引用），
 * 解析失败返回 null，由调用方 fallback 原 src。
 */
fun resolveWorkspaceImage(src: String, resolver: (String) -> String?): String? = resolver(src)

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
