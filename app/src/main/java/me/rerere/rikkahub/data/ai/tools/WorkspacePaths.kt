package me.rerere.rikkahub.data.ai.tools

/**
 * 工作区工具的路径语义：cwd 归一化、工具输入路径解析、子树/可写区判定。
 * 纯字符串运算，不触碰文件系统。审批判定与执行检查都基于解析后的 Rootfs 绝对路径，
 * 保证审批门看到的路径与实际操作的路径一致。
 */
internal const val WORKSPACE_ROOT = "/workspace"

/** 沙盒可写安全区：工作区文件目录与临时目录；其余 Rootfs 路径的写入由可写区检查拦截 */
private val WRITABLE_ROOTS = listOf(WORKSPACE_ROOT, "/tmp")

/**
 * 词法归一 Rootfs 路径：折叠 "."、".." 与重复分隔符。
 * 绝对路径在根处截断向上越界（"/a/../../b" → "/b"）；不解析符号链接、不触碰文件系统。
 */
internal fun normalizeWorkspacePath(path: String): String {
    val absolute = path.startsWith("/")
    val out = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> {
                if (out.isNotEmpty()) out.removeLast()
                else if (!absolute) out.addLast("..")
            }
            else -> out.addLast(segment)
        }
    }
    val joined = out.joinToString("/")
    return if (absolute) "/$joined" else joined.ifEmpty { "." }
}

/**
 * 归一化 cwd 为 Rootfs 绝对路径；null/空白/根目录返回 null（= /workspace 根语义）。
 * 接受 "/workspace/..." 绝对写法或相对 files 根的写法（与 workspace_shell 的 cwd 参数语义一致）。
 */
internal fun normalizeWorkspaceCwd(cwd: String?): String? {
    val raw = cwd?.trim().orEmpty()
    if (raw.isEmpty()) return null
    // 先判绝对性再统一分隔符：避免 "docs\sub" 这类 Windows 风格相对路径被转换后的
    // 前导 "/" 误判为绝对路径（绝对性以原始输入的首个字符是否为 "/" 为准）
    val absolute = raw.startsWith("/")
    val unified = raw.replace('\\', '/')
    if (unified.isEmpty()) return null
    val normalized = normalizeWorkspacePath(if (absolute) unified else "$WORKSPACE_ROOT/$unified")
    return normalized.takeUnless { it == WORKSPACE_ROOT || it == "/" }
}

/**
 * 解析工具输入路径为 Rootfs 绝对路径：绝对输入（/ 开头）归一后原样采用；
 * 相对输入以 [cwd] 为基准解析（cwd 为 null 时以 /workspace 根为基准）。
 */
internal fun resolveWorkspaceToolPath(raw: String, cwd: String?): String {
    val trimmed = raw.replace('\\', '/').trim()
    require(trimmed.isNotEmpty()) { "path is required" }
    require(!trimmed.contains('\u0000')) { "path contains invalid character" }
    return if (trimmed.startsWith("/")) {
        normalizeWorkspacePath(trimmed)
    } else {
        normalizeWorkspacePath("${cwd ?: WORKSPACE_ROOT}/$trimmed")
    }
}

/** [path] 是否位于 [base] 子树内（含 base 本身） */
internal fun isUnderWorkspaceTree(path: String, base: String): Boolean =
    path == base || path.startsWith("$base/")

/** [path] 是否在沙盒可写安全区（/workspace、/tmp）之外 */
internal fun isOutsideWorkspaceWritableRoots(path: String): Boolean =
    WRITABLE_ROOTS.none { isUnderWorkspaceTree(path, it) }
