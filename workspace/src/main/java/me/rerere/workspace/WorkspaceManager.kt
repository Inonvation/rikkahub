package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    /**
     * 递归列出指定区域下所有非隐藏文件，用于变更检测快照。
     */
    fun listAllFiles(
        root: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.listAllFiles(areaDir(root, area))

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun createDirectory(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = fileSystem.mkdir(areaDir(root, area), path)

    /**
     * 把 files 区打包为 zip, 供用户备份导出 (排除 .trash 回收站)。
     */
    fun backupFiles(root: String, outputStream: OutputStream) {
        val filesRoot = filesDir(root)
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            filesRoot.walkTopDown()
                .filter { it.isFile }
                .filterNot { it.relativeTo(filesRoot).invariantSeparatorsPath.startsWith(".trash/") }
                .forEach { file ->
                    val rel = file.relativeTo(filesRoot).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * 从备份 zip 恢复到 files 区 (覆盖现有内容)。含路径穿越防护:
     * 拒绝绝对路径、..、以及解析后逃出 files 根目录的条目。
     */
    fun restoreFiles(root: String, inputStream: InputStream) {
        val filesRoot = filesDir(root)
        val rootCanonical = filesRoot.canonicalFile
        filesRoot.listFiles()?.forEach { it.deleteRecursively() }
        ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                require(name != ".." && !name.startsWith("../") && !name.startsWith("/")) {
                    "Unsafe path in backup: $name"
                }
                val target = File(filesRoot, name).canonicalFile
                require(target.path == rootCanonical.path || target.path.startsWith(rootCanonical.path + File.separator)) {
                    "Backup entry escapes workspace root: $name"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 按字节区间导出 Rootfs 内文件（RandomAccessFile，不经 proot），支持 /workspace、bind mount
     * 与 Rootfs 内部路径。越界安全：start 超过文件末尾时输出为空；实际读取长度取 min(length, 剩余字节)。
     * 供 read_file 分片读取大文件（readRootfsBuffer 的全文件读有 8MB 上限）。
     */
    fun exportRootfsFileRange(
        root: String,
        path: String,
        start: Long,
        length: Long,
        outputStream: OutputStream,
    ) {
        require(start >= 0) { "Range start must be non-negative: $start" }
        require(length > 0) { "Range length must be positive: $length" }
        require(length <= MAX_RANGE_READ_BYTES) {
            "Range length exceeds max ${MAX_RANGE_READ_BYTES} bytes: $length"
        }
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out ->
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (start >= size) return@use
                raf.seek(start)
                val buffer = ByteArray(RANGE_READ_BUFFER_BYTES)
                var left = minOf(length, size - start)
                while (left > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    left -= read
                }
            }
        }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    /** 软删除: 把文件移入本区 `.trash`, 可后续恢复 */
    fun moveFileToTrash(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.moveToTrash(areaDir(root, area), path, recursive)

    /** 从 `.trash` 恢复到原路径 */
    fun restoreFileFromTrash(
        root: String,
        trashRelativePath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.restoreFromTrash(areaDir(root, area), trashRelativePath)

    /** 列出本区 `.trash` 内的文件 */
    fun listTrashFiles(
        root: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.listTrash(areaDir(root, area))

    /** 永久删除 `.trash` 内的文件 */
    fun deleteTrashFile(
        root: String,
        trashRelativePath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.deleteFromTrash(areaDir(root, area), trashRelativePath)

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry =
        fileSystem.move(areaDir(root, area), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    /**
     * 按 Rootfs 内绝对路径列出目录内容。路径解析与 read/write/edit 一致,
     * 支持 /workspace、bind mount 与 Rootfs 内部路径。返回的 path 为 Rootfs 绝对路径,
     * 可直接作为 read/edit 等工具入参。
     */
    fun listFilesInRootfs(root: String, path: String): List<WorkspaceFileEntry> {
        val location = resolveRootfsPath(root, path)
        return fileSystem.list(location.rootDir, location.relativePath)
            .map { it.toAbsoluteRootfs(path, location.relativePath) }
    }

    /**
     * 按 Rootfs 内绝对路径做 glob 匹配。pattern 相对 workspace root 解析(与 files 区 glob 语义一致)。
     * 返回的 path 为 Rootfs 绝对路径。
     */
    fun globInRootfs(root: String, pattern: String, path: String): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val location = resolveRootfsPath(root, path)
        return fileSystem.glob(location.rootDir, pattern, location.relativePath)
            .map { it.toAbsoluteRootfs(path, location.relativePath) }
    }

    /**
     * 按 Rootfs 内绝对路径做内容搜索。支持 Rootfs 内部, 但大目录(如 /usr)可能较慢。
     * 返回的 path 为 Rootfs 绝对路径。
     */
    fun grepInRootfs(
        root: String,
        query: String,
        path: String,
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val location = resolveRootfsPath(root, path)
        return fileSystem.grep(location.rootDir, query, location.relativePath, regex, ignoreCase, includeGlob)
            .map { it.toAbsoluteRootfs(path, location.relativePath) }
    }

    /**
     * 把相对 [location.rootDir] 的 path 转成 Rootfs 绝对路径: 去掉 [relativePrefix] 前缀再拼
     * [rootfsPath], 避免 /workspace/sub/sub/xxx 这类前缀重复。
     */
    private fun absoluteRootfsPath(path: String, relativePrefix: String, rootfsPath: String): String {
        val stripped = if (relativePrefix.isBlank()) path else path.removePrefix("$relativePrefix/")
        val prefix = rootfsPath.trimEnd('/').ifBlank { "/" }
        return if (prefix == "/") "/$stripped" else "$prefix/$stripped"
    }

    private fun WorkspaceFileEntry.toAbsoluteRootfs(rootfsPath: String, relativePrefix: String): WorkspaceFileEntry =
        copy(path = absoluteRootfsPath(path, relativePrefix, rootfsPath))

    private fun WorkspaceSearchMatch.toAbsoluteRootfs(rootfsPath: String, relativePrefix: String): WorkspaceSearchMatch =
        copy(path = absoluteRootfsPath(path, relativePrefix, rootfsPath))

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** read_file 分段读取的单次长度上限（RandomAccessFile 读入内存的预算） */
        const val MAX_RANGE_READ_BYTES = 4L * 1024 * 1024
        private const val RANGE_READ_BUFFER_BYTES = 64 * 1024

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
