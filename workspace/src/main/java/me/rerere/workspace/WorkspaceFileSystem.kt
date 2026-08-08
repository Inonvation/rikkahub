package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    private val trashJson = Json { ignoreUnknownKeys = true }

    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !isHiddenWorkspaceName(it.name) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root).withChildCount(root) }
    }

    /**
     * 目录的直接子项数（文件+子文件夹）。轻量实现：仅一次 listFiles 计数，不做递归遍历，
     * 避免大目录列表（尤其嵌套文件夹）因统计占用而卡顿。占用仅对文件显示。
     */
    fun directoryChildCount(root: File, path: String): Int {
        val dir = resolvePath(root, path)
        if (!dir.exists() || !dir.isDirectory) return 0
        return dir.listFiles().orEmpty().count { !isHiddenWorkspaceName(it.name) }
    }

    /** 目录条目补充直接子项数（文件原样返回，不统计占用） */
    private fun WorkspaceFileEntry.withChildCount(root: File): WorkspaceFileEntry {
        if (!isDirectory) return this
        return copy(childCount = directoryChildCount(root, path))
    }

    /**
     * 递归列出 [root] 下所有非隐藏文件（不含目录），用于工作区变更检测快照。
     * 会跳过隐藏目录及其子文件。
     */
    fun listAllFiles(root: File): List<WorkspaceFileEntry> {
        require(root.exists() && root.isDirectory) { "Root must be an existing directory: ${root.path}" }
        return root.walk()
            .filter { it.isFile && !isHiddenWorkspaceName(it.name) }
            .filter { file ->
                val relative = file.relativeTo(root).path
                relative.split(File.separatorChar).none { isHiddenWorkspaceName(it) }
            }
            .map { it.toEntry(root) }
            .toList()
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    /** 新建目录(含多级), 路径已安全校验; 已存在则抛错 */
    fun mkdir(root: File, path: String): WorkspaceFileEntry {
        val dir = resolvePath(root, path)
        require(!dir.exists()) { "Path already exists: $path" }
        require(dir.mkdirs()) { "Failed to create directory: $path" }
        return dir.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    /**
     * 把文件/目录移入 workspace 根下的 `.trash`, 并写入 manifest 记录原路径与垃圾箱内名.
     *
     * 目录需 [recursive] = true. 垃圾箱内重名时加时间戳前缀, manifest 原子写入, 避免恢复错乱.
     */
    fun moveToTrash(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to trash workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        require(!isInsideTrash(root, file)) { "Cannot trash a file already inside .trash" }
        if (file.isDirectory) {
            require(recursive) { "Directory trash requires recursive = true" }
        }
        val trashDir = File(root, TRASH_DIR).apply { mkdirs() }
        val manifest = readTrashManifest(trashDir)
        val trashName = uniqueTrashName(trashDir, file.name)
        val target = File(trashDir, trashName)
        require(file.renameTo(target)) { "Failed to move $path into trash" }
        writeTrashManifest(trashDir, manifest + (path to trashName))
        return true
    }

    /**
     * 从垃圾箱恢复文件到原路径. [trashRelativePath] 为 manifest 中的原相对路径(manifest key).
     *
     * 若原路径已被占用, 恢复时自动改名避免覆盖; 成功后移除 manifest 记录.
     */
    fun restoreFromTrash(root: File, trashRelativePath: String): Boolean {
        require(trashRelativePath.isNotBlank() && trashRelativePath != ".") { "Invalid trash path: $trashRelativePath" }
        val trashDir = File(root, TRASH_DIR)
        if (!trashDir.exists()) return false
        val manifest = readTrashManifest(trashDir)
        val trashName = manifest[trashRelativePath] ?: return false
        val trashFile = File(trashDir, trashName)
        if (!trashFile.exists()) return false
        val original = resolvePath(root, trashRelativePath)
        original.parentFile?.mkdirs()
        val restored = if (!original.exists()) original else resolveConflict(original)
        require(trashFile.renameTo(restored)) { "Failed to restore $trashRelativePath" }
        writeTrashManifest(trashDir, manifest - trashRelativePath)
        return true
    }

    /** 列出垃圾箱内文件, 展示原路径与名称(结合 manifest 与磁盘实际校验). */
    fun listTrash(root: File): List<WorkspaceFileEntry> {
        val trashDir = File(root, TRASH_DIR)
        if (!trashDir.exists()) return emptyList()
        val manifest = readTrashManifest(trashDir)
        return manifest.entries
            .mapNotNull { (originalPath, trashName) ->
                val file = File(trashDir, trashName)
                if (!file.exists()) {
                    null
                } else {
                    file.toEntry(root).copy(
                        path = originalPath,
                        name = originalPath.substringAfterLast('/'),
                    )
                }
            }
            .sortedBy { it.path }
            .take(config.maxListEntries)
    }

    /** 永久删除垃圾箱内文件(从磁盘与 manifest 移除). 返回是否成功. */
    fun deleteFromTrash(root: File, trashRelativePath: String): Boolean {
        require(trashRelativePath.isNotBlank() && trashRelativePath != ".") { "Invalid trash path: $trashRelativePath" }
        val trashDir = File(root, TRASH_DIR)
        if (!trashDir.exists()) return false
        val manifest = readTrashManifest(trashDir)
        val trashName = manifest[trashRelativePath] ?: return false
        val trashFile = File(trashDir, trashName)
        val deleted = if (trashFile.isDirectory) {
            trashFile.deleteRecursively()
        } else {
            trashFile.delete()
        }
        if (!deleted) return false
        writeTrashManifest(trashDir, manifest - trashRelativePath)
        return true
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !isHiddenWorkspaceName(it.toFile().name) }
                // 匹配相对 [start]（起始目录）的路径, 而非相对 [root]: 这样调用方传入
                // path=起始目录 后, 用 "*.kt" 这类相对该目录的直觉 pattern 即可命中,
                // 不必在 pattern 里带目录前缀。path 为空时 start == root, 行为与旧版一致。
                .filter { matcher.matches(start.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !isHiddenWorkspaceName(it.toFile().name) }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            val startPath = start.toPath()
            // Files.walk 会递归下潜整个子树，包括回收站目录。glob/grep 只滤叶子文件名，
            // 若不在此处按路径前缀剪枝，`.trash/` 内部的文件会被搜索/匹配命中。
            // 剪枝路径本身（rel == TRASH_DIR）及其全部后代（rel 以 "TRASH_DIR/" 开头）。
            block(
                stream.iterator().asSequence()
                    .filter { path ->
                        val rel = startPath.relativize(path).toString()
                        rel != TRASH_DIR && !rel.startsWith(TRASH_DIR + File.separator)
                    }
            )
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        // 回收站 `.trash` 是内部软删区，不允许通过通用文件 API（list/read/write/glob/grep/delete）直接寻址，
        // 防止 AI 工具读取已删除文件或写坏 manifest。回收站自身的方法直接用 File(root, TRASH_DIR) 构造路径，不受影响。
        require(!isInsideTrash(rootFile, target)) { "Access to trash is not allowed via file API: $path" }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }

    /** 工作区内的隐藏条目: 临时文件前缀 `.l2s.` 与回收站目录 `.trash` */
    private fun isHiddenWorkspaceName(name: String): Boolean =
        name.startsWith(".l2s.") || name == TRASH_DIR

    private fun isInsideTrash(root: File, file: File): Boolean {
        val trashPath = File(root, TRASH_DIR).canonicalFile.path
        val filePath = file.canonicalFile.path
        return filePath == trashPath || filePath.startsWith(trashPath + File.separator)
    }

    /** 垃圾箱内重名时加时间戳前缀, 保证同一时刻删除的同名文件不互相覆盖 */
    private fun uniqueTrashName(trashDir: File, name: String): String {
        if (!File(trashDir, name).exists()) return name
        val base = "${System.currentTimeMillis()}_$name"
        var candidate = base
        var n = 1
        while (File(trashDir, candidate).exists()) {
            candidate = "${base}_$n"
            n++
        }
        return candidate
    }

    /**
     * 读取垃圾箱 manifest. 文件不存在视为空; 存在但损坏时抛错, 避免静默覆盖造成数据丢失.
     */
    private fun readTrashManifest(trashDir: File): Map<String, String> {
        val manifestFile = File(trashDir, TRASH_MANIFEST)
        if (!manifestFile.exists()) return emptyMap()
        return try {
            trashJson.decodeFromString(manifestFile.readText())
        } catch (e: Exception) {
            throw IllegalStateException("Trash manifest corrupted: ${manifestFile.path}", e)
        }
    }

    /** 原子写 manifest: 先写临时文件再 rename, 避免中途崩溃留下半截 JSON */
    private fun writeTrashManifest(trashDir: File, manifest: Map<String, String>) {
        val manifestFile = File(trashDir, TRASH_MANIFEST)
        val tmp = File(trashDir, "$TRASH_MANIFEST.tmp")
        val content = trashJson.encodeToString(manifest)
        tmp.writeText(content)
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.writeText(content)
            tmp.delete()
        }
    }

    companion object {
        private const val TRASH_DIR = ".trash"
        private const val TRASH_MANIFEST = "manifest.json"
    }
}
