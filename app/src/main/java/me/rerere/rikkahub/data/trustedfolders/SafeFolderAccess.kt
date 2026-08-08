package me.rerere.rikkahub.data.trustedfolders

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * SAF（Storage Access Framework）封装 —— 信任文件夹唯一真正读写真实文件系统的类。
 *
 * 每个项目持有系统文件选择器签发的目录树 URI（"门禁卡"），所有操作都限定在这棵树内：
 * - 只接收**相对路径**（相对项目根），统一经 [validateRelPath] 校验，拒绝 `..` 逃逸、绝对路径、NUL 字符。
 * - 底层由系统 content provider 做 URI→文件映射，App 拿不到真实路径，天然无法越出所选目录。
 * - 每次操作前校验授权是否仍有效（用户可能收回授权）。
 */
class SafeFolderAccess(private val context: Context) {
    companion object {
        /** 单文件读取上限，防 AI 拉爆内存 */
        const val MAX_READ_BYTES = 512 * 1024L

        /** 单文件写入上限 */
        const val MAX_WRITE_BYTES = 2 * 1024 * 1024L

        /** 内容搜索/扫描最多遍历的文件数（防整库太大时卡死） */
        const val MAX_SCAN_FILES = 3000

        /** 按笔记名查找时最多遍历的文件数（只比较文件名、不读内容，上限放宽） */
        const val MAX_NAME_SCAN_FILES = 20000

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
            "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
            "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
            "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
            "diff", "patch", "srt", "vtt",
        )

        /** 是否适合按文本搜索（已知文本扩展名或无扩展名） */
        private fun isSearchableTextFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext.isEmpty() || ext in TEXT_EXTENSIONS
        }

        /** 是否 Markdown 笔记（.md / .markdown） */
        private fun isMarkdownFile(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
        )

        /** 是否图片文件（Obsidian 附件常见格式） */
        private fun isImageFile(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        /** 文件名匹配键：去 md 后缀 + Unicode 规范化(NFC) + 转小写。按笔记名定位时两侧都用它比较 */
        private fun String.toFileNameKey(): String =
            removeSuffix(".md").removeSuffix(".markdown")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
                .trim()
                .lowercase()

        private val MIME_BY_EXT = mapOf(
            "md" to "text/markdown", "markdown" to "text/markdown",
            "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain",
            "json" to "application/json", "xml" to "application/xml",
            "yaml" to "application/yaml", "yml" to "application/yaml",
            "html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "csv" to "text/csv",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        )

        /** 按扩展名推断 mime；未知返回通用二进制类型 */
        fun mimeOf(name: String): String =
            MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

        /** 规范化相对路径并校验：空串=根目录；拒绝绝对路径、`..`、`.`、`/` 开头、NUL */
        fun validateRelPath(path: String): String {
            val normalized = path.replace('\\', '/').trim()
            if (normalized.isEmpty()) return ""
            require(!normalized.startsWith("/")) { "path must be relative to the trusted folder root" }
            require(!normalized.contains('\u0000')) { "path contains invalid character" }
            val segments = normalized.split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
                "path must not escape the trusted folder"
            }
            return normalized
        }
    }

    private fun rootOf(uri: String): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(uri))
            ?: throw IllegalStateException("信任文件夹授权已失效，请重新信任")

    /** 授权是否仍有效（用户可能收回） */
    fun isAuthorized(uri: String): Boolean =
        runCatching { rootOf(uri).exists() }.getOrDefault(false)

    private fun resolve(root: DocumentFile, relPath: String): DocumentFile? {
        if (relPath.isEmpty()) return root
        var current: DocumentFile = root
        for (segment in relPath.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** 列目录。空串 [relPath] 表示项目根 */
    suspend fun list(uri: String, relPath: String): List<TrustedFolderEntry> = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val rel = validateRelPath(relPath)
        val dir = resolve(root, rel) ?: throw IllegalArgumentException("目录不存在: $relPath")
        require(dir.isDirectory) { "不是目录: $relPath" }
        dir.listFiles()
            .mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                val isDir = doc.isDirectory
                // 目录不统计占用（避免大目录卡顿），只计直接子项数
                TrustedFolderEntry(
                    name = name,
                    path = if (rel.isEmpty()) name else "$rel/$name",
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else doc.length(),
                    updatedAt = doc.lastModified(),
                    childCount = if (isDir) directoryChildCount(doc) else 0,
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** 目录的直接子项数（文件+子文件夹）；轻量，仅一次 listFiles 计数，不做递归遍历 */
    private fun directoryChildCount(dir: DocumentFile): Int =
        dir.listFiles().count { it.name != null }

    /** 读文件字节。超出 [MAX_READ_BYTES] 拒绝 */
    suspend fun readBytes(uri: String, relPath: String): ByteArray = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val rel = validateRelPath(relPath)
        val doc = resolve(root, rel) ?: throw IllegalArgumentException("文件不存在: $relPath")
        require(doc.isFile) { "不是文件: $relPath" }
        val size = doc.length()
        require(size <= MAX_READ_BYTES) {
            "文件过大无法读取: $relPath (${size / 1024}KB, 上限 ${MAX_READ_BYTES / 1024}KB)"
        }
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("无法打开文件: $relPath")
        input.use { it.readBytes() }
    }

    suspend fun readText(uri: String, relPath: String): String =
        readBytes(uri, relPath).toString(Charsets.UTF_8)

    /**
     * 写文本文件。不存在则新建（[overwrite] 无效），已存在且 [overwrite]=true 则覆盖，否则报错。
     * 自动创建中间目录。
     */
    suspend fun writeText(
        uri: String,
        relPath: String,
        text: String,
        overwrite: Boolean,
    ): TrustedFolderEntry = withContext(Dispatchers.IO) {
        val rel = validateRelPath(relPath)
        require(rel.isNotEmpty()) { "不能写入信任文件夹根目录" }
        val name = rel.substringAfterLast('/')
        require(name.isNotEmpty() && name != "." && name != "..") { "非法文件名: $relPath" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) {
            "写入内容过大: ${bytes.size / 1024}KB, 上限 ${MAX_WRITE_BYTES / 1024}KB"
        }
        val root = rootOf(uri)
        val parentRel = rel.substringBeforeLast('/', "")
        val parent = resolve(root, parentRel) ?: throw IllegalArgumentException("目录不存在: $parentRel")
        require(parent.isDirectory) { "父路径不是目录: $parentRel" }

        val existing = parent.findFile(name)
        val targetDoc: DocumentFile = if (existing != null) {
            require(existing.isFile) { "路径已存在且不是文件: $relPath" }
            require(overwrite) { "文件已存在: $relPath (overwrite=false)" }
            existing
        } else {
            parent.createFile(mimeOf(name), name) ?: throw IOException("无法创建文件: $relPath")
        }
        val output = context.contentResolver.openOutputStream(targetDoc.uri, "wt")
            ?: throw IOException("无法写入文件: $relPath")
        output.use { it.write(bytes) }
        targetDoc.toEntry(parentRel, name)
    }

    /** 新建文件夹（可多级，自动创建中间目录） */
    suspend fun createFolder(uri: String, relPath: String): TrustedFolderEntry = withContext(Dispatchers.IO) {
        val rel = validateRelPath(relPath)
        require(rel.isNotEmpty()) { "不能创建信任文件夹根目录" }
        val root = rootOf(uri)
        val parentRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        require(name.isNotEmpty() && name != "." && name != "..") { "非法文件夹名: $relPath" }
        val parent = resolve(root, parentRel) ?: throw IllegalArgumentException("目录不存在: $parentRel")
        require(parent.isDirectory) { "父路径不是目录: $parentRel" }
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "路径已存在且不是文件夹: $relPath" }
        }
        val dir = existing ?: parent.createDirectory(name) ?: throw IOException("无法创建文件夹: $relPath")
        dir.toEntry(parentRel, name)
    }

    /** 重命名文件/文件夹 */
    suspend fun rename(uri: String, relPath: String, newName: String): TrustedFolderEntry = withContext(Dispatchers.IO) {
        val rel = validateRelPath(relPath)
        require(rel.isNotEmpty()) { "不能重命名信任文件夹根目录" }
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty() && !trimmed.contains('/') && trimmed != "." && trimmed != "..") {
            "非法新名称: $newName"
        }
        val root = rootOf(uri)
        val parentRel = rel.substringBeforeLast('/', "")
        val doc = resolve(root, rel) ?: throw IllegalArgumentException("不存在: $relPath")
        require(doc.renameTo(trimmed)) { "重命名失败: $relPath" }
        doc.toEntry(parentRel, trimmed)
    }

    /** 移动文件/文件夹到项目内另一个目录（[targetDirRel] 相对项目根） */
    suspend fun move(uri: String, relPath: String, targetDirRel: String): TrustedFolderEntry = withContext(Dispatchers.IO) {
        val rel = validateRelPath(relPath)
        val target = validateRelPath(targetDirRel)
        require(rel.isNotEmpty()) { "不能移动信任文件夹根目录" }
        val root = rootOf(uri)
        val doc = resolve(root, rel) ?: throw IllegalArgumentException("不存在: $relPath")
        val targetDir = resolve(root, target) ?: throw IllegalArgumentException("目标目录不存在: $targetDirRel")
        require(targetDir.isDirectory) { "目标不是目录: $targetDirRel" }
        val name = doc.name ?: throw IOException("无法获取名称: $relPath")
        require(targetDir.findFile(name) == null) { "目标目录已存在同名项: $targetDirRel/$name" }
        val sourceParent = doc.getParentFile()
            ?: throw IOException("无法获取源目录信息: $relPath")
        // DocumentFile 无 moveTo，用系统 DocumentsContract 在同一目录树内移动
        val moved = runCatching {
            DocumentsContract.moveDocument(
                context.contentResolver,
                doc.uri,
                sourceParent.uri,
                targetDir.uri,
            )
        }.getOrNull()
        val result = when {
            moved != null -> targetDir.findFile(name) ?: DocumentFile.fromSingleUri(context, moved)
                ?: throw IOException("移动失败: $relPath")
            else -> throw IOException("移动失败: $relPath（系统不支持移动，请手动操作）")
        }
        result.toEntry(target, name)
    }

    /** 删除文件/文件夹（递归由系统处理）。不存在视为成功 */
    suspend fun delete(uri: String, relPath: String) = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val rel = validateRelPath(relPath)
        require(rel.isNotEmpty()) { "不能删除信任文件夹根目录" }
        val doc = resolve(root, rel) ?: return@withContext
        require(doc.delete()) { "删除失败: $relPath" }
    }

    /**
     * 内容搜索：递归遍历 [relPath] 目录下所有文本文件，逐行匹配。
     * 受 [MAX_SCAN_FILES] 文件数与 [MAX_READ_BYTES] 单文件大小限制；结果达到 [maxResults] 即停止。
     */
    suspend fun search(
        uri: String,
        query: String,
        relPath: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        maxResults: Int = 50,
    ): List<TrustedFolderSearchMatch> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        val root = rootOf(uri)
        val rel = validateRelPath(relPath)
        val start = resolve(root, rel) ?: throw IllegalArgumentException("目录不存在: $relPath")
        require(start.isDirectory) { "不是目录: $relPath" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val results = mutableListOf<TrustedFolderSearchMatch>()
        val scanned = AtomicInteger(0)
        // 从搜索起点开始遍历，但结果路径仍相对项目根（初始 dirRel = rel），AI 可直接用结果去 read
        walkFiles(start, rel, scanned) { doc, fileDirRel ->
            val name = doc.name
            if (name == null || !isSearchableTextFile(name) || doc.length() > MAX_READ_BYTES) {
                return@walkFiles true
            }
            val text = runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@walkFiles true
            val relFile = if (fileDirRel.isEmpty()) name else "$fileDirRel/$name"
            for ((index, line) in text.lineSequence().withIndex()) {
                if (results.size >= maxResults) return@walkFiles false
                if (matcher.containsMatchIn(line)) {
                    results += TrustedFolderSearchMatch(
                        path = relFile,
                        line = index + 1,
                        text = line.trim().take(200),
                    )
                }
            }
            true
        }
        results
    }

    /** 递归扫描全部 Markdown 笔记，返回 (相对路径, 内容) 列表。供断链/体检使用。 */
    suspend fun scanMarkdownFiles(uri: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val files = mutableListOf<Pair<String, String>>()
        val scanned = AtomicInteger(0)
        walkFiles(root, "", scanned) { doc, fileDirRel ->
            val name = doc.name
            if (name == null || !isMarkdownFile(name) || doc.length() > MAX_READ_BYTES) {
                return@walkFiles true
            }
            val text = runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@walkFiles true
            files += (if (fileDirRel.isEmpty()) name else "$fileDirRel/$name") to text
            true
        }
        files
    }

    /**
     * 按笔记名（不含扩展名，忽略大小写/Unicode 规范化差异）在整个项目树里找 .md 文件，返回相对路径。
     * 只比较文件名、不读内容，因此比 [scanMarkdownFiles] 快且不受内容扫描上限约束
     * （[MAX_NAME_SCAN_FILES] 仅限制遍历文件数，避免超大目录卡死）。
     */
    suspend fun findMarkdownByNoteName(uri: String, noteName: String): String? = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val target = noteName.toFileNameKey()
        var found: String? = null
        val scanned = AtomicInteger(0)
        walkFiles(root, "", scanned, limit = MAX_NAME_SCAN_FILES) { doc, fileDirRel ->
            val name = doc.name
            if (name != null && isMarkdownFile(name) && name.toFileNameKey() == target) {
                found = if (fileDirRel.isEmpty()) name else "$fileDirRel/$name"
                return@walkFiles false
            }
            true
        }
        found
    }

    /** 构建整棵树的 Markdown 文件名索引：笔记名匹配键 -> 相对路径。一次遍历，供双链跳转加速。 */
    suspend fun indexMarkdownFiles(uri: String): Map<String, String> = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val index = HashMap<String, String>()
        val scanned = AtomicInteger(0)
        walkFiles(root, "", scanned, limit = MAX_NAME_SCAN_FILES) { doc, fileDirRel ->
            val name = doc.name
            if (name != null && isMarkdownFile(name)) {
                val key = name.toFileNameKey()
                if (key.isNotEmpty() && key !in index) {
                    index[key] = if (fileDirRel.isEmpty()) name else "$fileDirRel/$name"
                }
            }
            true
        }
        index
    }

    /** 构建整棵树的图片文件索引：文件名匹配键（含扩展名 + 去扩展名）-> 相对路径。供 Obsidian 附件图片按名定位。 */
    suspend fun indexImageFiles(uri: String): Map<String, String> = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val index = HashMap<String, String>()
        val scanned = AtomicInteger(0)
        walkFiles(root, "", scanned, limit = MAX_NAME_SCAN_FILES) { doc, fileDirRel ->
            val name = doc.name
            if (name != null && isImageFile(name)) {
                val rel = if (fileDirRel.isEmpty()) name else "$fileDirRel/$name"
                // 完整文件名优先（`![[a.jpg]]`），去扩展名兜底（`![[a]]`）
                index.putIfAbsent(name.toFileNameKey(), rel)
                index.putIfAbsent(name.substringBeforeLast('.', "").toFileNameKey(), rel)
            }
            true
        }
        index
    }

    /** 解析相对路径对应文件的 content:// URI（供 Coil 图片预览等直接加载）。目录或不存在返回 null */
    suspend fun resolveUri(uri: String, relPath: String): Uri? = withContext(Dispatchers.IO) {
        val root = rootOf(uri)
        val doc = resolve(root, validateRelPath(relPath)) ?: return@withContext null
        if (doc.isFile) doc.uri else null
    }

    /**
     * 递归遍历目录树。每个文件调用 [onFile]（参数为文件及其所在目录的相对路径）；
     * [onFile] 返回 false 表示停止整个遍历（结果触顶时提前退出）。
     * 扫描文件数达到 [limit] 时停止。返回 false 表示被提前终止。
     */
    private fun walkFiles(
        dir: DocumentFile,
        dirRel: String,
        scanned: AtomicInteger,
        limit: Int = MAX_SCAN_FILES,
        onFile: (DocumentFile, String) -> Boolean,
    ): Boolean {
        for (doc in dir.listFiles()) {
            if (scanned.get() >= limit) return false
            if (doc.isDirectory) {
                val keepGoing = walkFiles(
                    dir = doc,
                    dirRel = if (dirRel.isEmpty()) doc.name.orEmpty() else "$dirRel/${doc.name}",
                    scanned = scanned,
                    onFile = onFile,
                    limit = limit,
                )
                if (!keepGoing) return false
            } else if (doc.isFile) {
                if (scanned.get() >= limit) return false
                scanned.incrementAndGet()
                if (!onFile(doc, dirRel)) return false
            }
        }
        return true
    }

    private fun DocumentFile.toEntry(parentRel: String, name: String): TrustedFolderEntry =
        TrustedFolderEntry(
            name = name,
            path = if (parentRel.isEmpty()) name else "$parentRel/$name",
            isDirectory = isDirectory,
            sizeBytes = length(),
            updatedAt = lastModified(),
        )
}
