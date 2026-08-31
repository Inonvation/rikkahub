package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val asyncTaskRunner: WorkspaceAsyncTaskRunner,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            // rootfs 就绪后生成环境自描述文件, 让 AI 开局了解环境; 失败不阻塞安装成功(下次会话懒兜底)
            runCatching { ensureAgentsFile(workspace.id) }
                .onFailure { Log.w(TAG, "generate AGENTS.md failed after install", it) }
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    /**
     * 递归列出工作区某区域下所有非隐藏文件，用于 shell 执行前后的变更快照。
     */
    suspend fun listAllFiles(
        id: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listAllFiles(workspace.root, area)
    }

    /** 按 Rootfs 内绝对路径列出目录内容(支持 /workspace、bind mount、rootfs 内部) */
    suspend fun listFilesInRootfs(
        id: String,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFilesInRootfs(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径做 glob 匹配 */
    suspend fun globInRootfs(
        id: String,
        pattern: String,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.globInRootfs(workspace.root, pattern, path)
    }

    /** 按 Rootfs 内绝对路径做内容搜索 */
    suspend fun grepInRootfs(
        id: String,
        query: String,
        path: String,
        regex: Boolean,
        ignoreCase: Boolean,
        includeGlob: String?,
    ): List<WorkspaceSearchMatch> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.grepInRootfs(workspace.root, query, path, regex, ignoreCase, includeGlob)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /** 新建目录(含多级), 已存在则抛错 */
    suspend fun createDirectory(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.createDirectory(workspace.root, path, area)
    }

    /** 把 files 区打包为 zip 备份导出 */
    suspend fun backupFiles(id: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.backupFiles(workspace.root, outputStream)
    }

    /** 从备份 zip 恢复 files 区 (覆盖现有内容, 含路径安全校验) */
    suspend fun restoreFiles(id: String, inputStream: InputStream) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.restoreFiles(workspace.root, inputStream)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun fileDirPath(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        manager.ensureWorkspace(workspace.root)
        when (area) {
            // FILES 区返回文件所在目录的绝对路径（HTML 预览相对资源解析基准）
            WorkspaceStorageArea.FILES -> File(manager.filesDir(workspace.root), path).parentFile?.absolutePath
            // LINUX/rootfs 区路径不映射到真实文件系统目录，返回 null（HTML 相对资源不可解析）
            WorkspaceStorageArea.LINUX -> null
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    /** 分片读取结果：文本 + 文件总字节数（供模型规划后续分片） */
    data class RootfsTextRange(
        val sizeBytes: Long,
        val text: String,
    )

    /**
     * 按 Rootfs 内绝对路径读指定字节区间（RandomAccessFile，不经 proot）。
     * 越界安全：start 超过文件末尾返回空文本；实际读取长度取 min(maxBytes, 剩余字节)。
     */
    suspend fun readRootfsTextRange(
        id: String,
        path: String,
        start: Long,
        maxBytes: Long,
    ): RootfsTextRange = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        RootfsTextRange(
            sizeBytes = manager.rootfsFileSize(workspace.root, path),
            text = ByteArrayOutputStream().use { out ->
                manager.exportRootfsFileRange(workspace.root, path, start, maxBytes, out)
                out.toString(Charsets.UTF_8.name())
            },
        )
    }

    /** 后台执行 shell 命令（workspace_shell_async 后端），立即返回 taskId */
    fun launchAsyncCommand(
        id: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
    ): String = asyncTaskRunner.launch(id, command, cwd, timeoutMillis)

    fun asyncTaskStatus(taskId: String): AsyncTaskStatus? = asyncTaskRunner.status(taskId)

    /**
     * 把环境变量持久化到 rootfs /etc/profile.d（每次登录 shell 自动加载）；value 为 null 时移除该变量。
     * 文件为 rootfs 内 app 托管配置，路径与内容格式受控，不走用户输入路径。
     */
    suspend fun setEnvPersistence(
        id: String,
        name: String,
        value: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.ensureWorkspace(workspace.root)
        val profileDir = File(manager.linuxDir(workspace.root), "etc/profile.d").apply { mkdirs() }
        val envFile = File(profileDir, ENV_PROFILE_FILE)
        val lines = if (envFile.isFile) {
            runCatching { envFile.readLines() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val updated = upsertEnvLine(lines, name, value)
        if (updated == lines) return@withContext true
        val tmp = File(profileDir, "$ENV_PROFILE_FILE.tmp")
        tmp.writeText(updated.joinToString("\n") + "\n")
        if (!tmp.renameTo(envFile)) {
            envFile.writeText(updated.joinToString("\n") + "\n")
            tmp.delete()
        }
        true
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    /** 软删除: 把文件移入回收站(.trash), 可后续恢复 */
    suspend fun trashFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.moveFileToTrash(workspace.root, path, recursive, area)
    }

    /** 从回收站恢复到原路径 */
    suspend fun restoreFile(
        id: String,
        area: WorkspaceStorageArea,
        trashRelativePath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.restoreFileFromTrash(workspace.root, trashRelativePath, area)
    }

    /** 列出回收站内的文件 */
    suspend fun listTrash(
        id: String,
        area: WorkspaceStorageArea,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listTrashFiles(workspace.root, area)
    }

    /** 永久删除回收站内的文件 */
    suspend fun deleteTrashFile(
        id: String,
        area: WorkspaceStorageArea,
        trashRelativePath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.deleteTrashFile(workspace.root, trashRelativePath, area)
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite, area)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    /**
     * 安装常用工具链 (python3/pip/git/curl 等), 供 AI 工作区直接使用。
     * 走 rootfs 内 apt, 耗时可能数分钟; 装完由调用方按需触发 AGENTS 刷新, 让 AI 看到新工具列表。
     */
    suspend fun installCommonTools(id: String): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.executeCommand(
            root = workspace.root,
            command = COMMON_TOOLS_INSTALL_SCRIPT,
            timeoutMillis = COMMON_TOOLS_INSTALL_TIMEOUT_MS,
        )
    }

    /** 读取 .agent 下注入型配置(AGENTS/MEMORY)内容; 不存在或过大返回 null */
    private suspend fun readAgentConfigFile(id: String, relativePath: String): String? =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext null
            val file = File(manager.filesDir(workspace.root), relativePath)
            if (!file.isFile || file.length() > MAX_AGENTS_READ_BYTES) return@withContext null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    /** 读取环境自描述文件 AGENTS.md 内容, 用于注入 system prompt */
    suspend fun readAgentsFileContent(id: String): String? = readAgentConfigFile(id, AGENTS_FILE)

    /** 读取经验索引 MEMORY.md 内容, 用于注入 system prompt */
    suspend fun readMemoryIndex(id: String): String? = readAgentConfigFile(id, MEMORY_FILE)

    /** 确保 MEMORY.md 索引与 notes 详情文件齐备; 已有有效内容保留, 索引缺失/空白则重新生成 */
    suspend fun ensureMemoryIndex(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.ensureWorkspace(workspace.root)
        val filesDir = manager.filesDir(workspace.root)
        val memoryFile = File(filesDir, MEMORY_FILE)
        if (memoryFile.isFile && memoryFile.readPrefixIsNotBlank()) {
            ensureNoteFiles(filesDir)
            return@withContext true
        }
        manager.writeText(workspace.root, MEMORY_FILE, MEMORY_INDEX_TEMPLATE + "\n")
        ensureNoteFiles(filesDir)
        true
    }

    /** 补建缺失的 notes 详情文件(带引导文案), 均为 .agent 下可信常量路径, 不走用户输入 */
    private fun ensureNoteFiles(filesDir: File) {
        AGENT_NOTE_GUIDES.forEach { (notePath, guide) ->
            val noteFile = File(filesDir, notePath)
            if (!noteFile.isFile) {
                val header = notePath.substringAfterLast('/').removeSuffix(".md")
                noteFile.parentFile?.mkdirs()
                noteFile.writeText("# $header\n\n($guide)\n")
            }
        }
    }

    /** 每工作区上次 AGENTS 探测时间, 控制探测脚本执行频率; WorkspaceRepository 是单例, 跨消息可靠 */
    private val agentsRefreshThrottle = ConcurrentHashMap<String, Long>()

    /**
     * 探测并更新 /workspace/.agent/AGENTS.md 的自动生成区(AUTOGEN 区):
     * 重新执行探测脚本, 合并替换 AUTOGEN 区, 保留标记之外的内容(AI 自由编辑区)。
     * 合并结果与现有文件逐字节一致时跳过写文件——注入 system prompt 的文本不变,
     * LLM prompt 缓存前缀稳定不失效; 只有真实变化(装/删工具、版本变化)才落盘。
     */
    suspend fun ensureAgentsFile(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.ensureWorkspace(workspace.root)
        val filesDir = manager.filesDir(workspace.root)
        val agentsFile = File(filesDir, AGENTS_FILE)

        // 一次性迁移: 旧版本在 files 区根生成 AGENTS.md, 移到 .agent/ (空白目标先删, 避免 rename 失败)。
        // 迁移后不返回, 继续探测刷新给老文件补上 AUTOGEN 区。
        val legacyFile = File(filesDir, LEGACY_AGENTS_FILE)
        if (legacyFile.isFile && !agentsFile.isFile) {
            agentsFile.parentFile?.mkdirs()
            if (agentsFile.exists()) agentsFile.delete()
            legacyFile.renameTo(agentsFile)
        }

        val result = manager.executeCommand(
            root = workspace.root,
            command = AGENTS_FILE_SCRIPT,
            timeoutMillis = AGENTS_SCRIPT_TIMEOUT_MS,
        )
        if (result.timedOut || result.exitCode != 0 || result.stdout.isBlank()) {
            Log.w(
                TAG,
                "AGENTS.md generation failed: exit=${result.exitCode}, timedOut=${result.timedOut}, " +
                    "stderr=${result.stderr.take(200)}",
            )
            return@withContext false
        }
        val generated = result.stdout.trimEnd() + "\n"
        val existing = if (agentsFile.isFile) {
            runCatching { agentsFile.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val merged = mergeAgentsContent(existing, generated)
        if (existing != null && existing == merged) return@withContext true
        agentsFile.parentFile?.mkdirs()
        agentsFile.writeText(merged)
        true
    }

    /**
     * 按节流刷新 AGENTS.md 自动生成区: 距上次探测不足 [AGENTS_REFRESH_INTERVAL_MS] 则跳过。
     * 节流只控制探测脚本的执行频率(防每条消息都跑 proot), 不决定是否写文件——
     * 写不写由 [ensureAgentsFile] 内部的内容比较决定。失败移除时间戳, 下次调用重试。
     */
    suspend fun refreshAgentsFileIfStale(id: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = agentsRefreshThrottle[id]
        if (last != null && now - last < AGENTS_REFRESH_INTERVAL_MS) return@withContext false
        agentsRefreshThrottle[id] = now
        val ok = runCatching { ensureAgentsFile(id) }.getOrDefault(false)
        if (!ok) agentsRefreshThrottle.remove(id)
        ok
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    suspend fun updateTimestamp(id: String, timestamp: Long) {
        dao.updateTimestamp(id, timestamp)
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /** 注入 system prompt 时读取 AGENTS.md 的大小上限, 防 AI 把文件写大撑爆内存 */
        private const val MAX_AGENTS_READ_BYTES = 64L * 1024

        /** 环境自描述文件名, 位于 workspace files 区 .agent 目录(对应 Rootfs /workspace/.agent/AGENTS.md) */
        private const val AGENTS_FILE = ".agent/AGENTS.md"
        /** 旧版本在 files 区根生成 AGENTS.md, 用于一次性迁移 */
        private const val LEGACY_AGENTS_FILE = "AGENTS.md"
        private const val AGENTS_SCRIPT_TIMEOUT_MS = 60_000L
        /** AGENTS 自动生成区刷新节流: 只控制探测脚本执行频率, 是否写文件由内容比较决定 */
        private const val AGENTS_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        /** 常用工具链安装命令 (rootfs 内 apt); 装完触发 AGENTS 刷新可见工具列表 */
        private const val COMMON_TOOLS_INSTALL_SCRIPT =
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                "python3 python3-pip git curl wget unzip zip ca-certificates sqlite3 jq"
        /** 常用工具安装最长耗时: apt update + install 可能数分钟 */
        private const val COMMON_TOOLS_INSTALL_TIMEOUT_MS = 600_000L

        /** 经验索引文件名(注入 system prompt), 位于 .agent 目录; 细节放在 notes/ 按需读 */
        private const val MEMORY_FILE = ".agent/MEMORY.md"

        /** workspace_set_env 写盘的 profile.d 文件名（rootfs 内，bash -l 登录时随 /etc/profile 加载） */
        private const val ENV_PROFILE_FILE = "00-rikkahub-env.sh"
        private val MEMORY_INDEX_TEMPLATE = """
            # Workspace Memory (index)

            Index of this workspace's accumulated experience. Details live in the files below.
            Update the relevant file after installing a tool, solving a tricky problem, or settling a project workflow. Keep entries concise.

            - Toolchain  -> notes/toolchain.md   (installed tools and versions; update after installing)
            - Pitfalls   -> notes/pitfalls.md    (gotchas and their fixes)
            - Workflows  -> notes/workflows.md   (build/run commands for workspace projects)
            - Project    -> notes/project.md     (current work in progress and its state)
        """.trimIndent()
        private val AGENT_NOTE_GUIDES = mapOf(
            ".agent/notes/toolchain.md" to "Installed tools and versions; update after installing something new.",
            ".agent/notes/pitfalls.md" to "Gotchas and their fixes encountered in this workspace.",
            ".agent/notes/workflows.md" to "Build/run commands for the workspace's projects.",
            ".agent/notes/project.md" to "Current work in progress and its state.",
        )

        /**
         * 探测脚本: 在 PRoot 内执行, 直接输出 Markdown 内容, app 端不做解析。
         * 输出会作为 AUTOGEN 区由 mergeAgentsContent 包裹, 注入 system prompt。
         * 格式对照 Codex AGENTS.md 规范: ## 分节 + 每行一条 "- " 指令/事实 + must 句式, token 最简。
         * 输出必须确定性(不写时间戳等每次变化的字段), 保证内容稳定时文本逐字节一致, 不破坏 prompt 缓存。
         */
        private val AGENTS_FILE_SCRIPT = """
            {
            echo "# Workspace Environment (auto-generated)"
            echo ""
            echo "## Environment"
            echo "- Linux sandbox (PRoot on Android). NOT Windows — use Unix commands; never use dir/type/copy."
            . /etc/os-release 2>/dev/null
            echo "- Distro: ${'$'}{PRETTY_NAME:-unknown}"
            echo "- Arch: ${'$'}(uname -m 2>/dev/null)"
            echo "- Disk free: ${'$'}(df -h / 2>/dev/null | awk 'NR==2{print $4}')"
            if command -v apt >/dev/null 2>&1; then echo "- Package manager: apt"
            elif command -v apk >/dev/null 2>&1; then echo "- Package manager: apk"
            elif command -v pacman >/dev/null 2>&1; then echo "- Package manager: pacman"
            elif command -v dnf >/dev/null 2>&1; then echo "- Package manager: dnf"
            elif command -v yum >/dev/null 2>&1; then echo "- Package manager: yum"
            fi
            # 网络探测多地址尝试：阿里 DNS(国内) / Cloudflare / Google，任一可达即视为有网，避免 1.1.1.1 在国内误报 no
            if timeout 1 bash -c 'exec 3<>/dev/tcp/223.5.5.5/53' 2>/dev/null || timeout 1 bash -c 'exec 3<>/dev/tcp/1.1.1.1/53' 2>/dev/null || timeout 1 bash -c 'exec 3<>/dev/tcp/8.8.8.8/53' 2>/dev/null; then echo "- Network: yes"; else echo "- Network: no"; fi
            echo "- Mounts: /workspace (persistent), /skills, /upload, /tool_outputs"
            echo ""
            echo "## Installed"
            if command -v python3 >/dev/null 2>&1; then echo "- Python: ${'$'}(python3 --version 2>&1)"; fi
            if command -v pip3 >/dev/null 2>&1; then echo "- pip: ${'$'}(pip3 --version 2>&1) (break-system-packages enabled)"; fi
            tools=""
            for t in node npm yarn git gcc g++ make cmake curl wget jq tar zip unzip openssl sqlite3 perl ruby ffmpeg; do
                command -v "${'$'}{t}" >/dev/null 2>&1 && tools="${'$'}{tools} ${'$'}{t}"
            done
            if [ -n "${'$'}{tools}" ]; then echo "- Installed:${'$'}{tools}"; fi
            echo "- To discover more tools, run: ls /usr/bin (or: which <name>)"
            echo ""
            echo "## Rules"
            echo "- Must use absolute paths — each workspace_shell call is a fresh process; cd/export don't persist."
            echo "- Must treat /skills and /upload as read-only — do not write files there (shell-level writes are not blocked, only forbidden)."
            echo "- Must not edit this auto-generated section; put your own notes below."
            }
        """.trimIndent()
    }
}

/** 读文件前 512 字节判断是否非空白, 用于识别被清空的损坏文件 */
private fun File.readPrefixIsNotBlank(): Boolean = runCatching {
    if (length() == 0L) return@runCatching false
    inputStream().use { input ->
        val buf = ByteArray(512)
        val read = input.read(buf)
        read > 0 && String(buf, 0, read, Charsets.UTF_8).trim().isNotEmpty()
    }
}.getOrDefault(false)

/** AGENTS.md 自动生成区标记: BEGIN..END 之间由 app 每次会话探测刷新, 之外为 AI 自由编辑区 */
private const val AUTOGEN_BEGIN = "<!-- AUTOGEN-BEGIN -->"
private const val AUTOGEN_END = "<!-- AUTOGEN-END -->"

/**
 * 合并 AGENTS.md 自动生成区: 替换 [AUTOGEN_BEGIN] 到 [AUTOGEN_END] 之间的内容,
 * 保留标记之外的内容(AI 自由编辑区)。老文件无标记/标记异常时把 AUTOGEN 块插到最前并保留原内容(下次刷新自愈)。
 * internal: 供本模块单元测试验证。
 */
internal fun mergeAgentsContent(existing: String?, generated: String): String {
    val block = "$AUTOGEN_BEGIN\n$generated$AUTOGEN_END"
    if (existing.isNullOrBlank()) return block
    val begin = existing.indexOf(AUTOGEN_BEGIN)
    val end = existing.indexOf(AUTOGEN_END)
    return if (begin >= 0 && end > begin) {
        existing.substring(0, begin) + block + existing.substring(end + AUTOGEN_END.length)
    } else {
        "$block\n\n$existing"
    }
}

/**
 * 更新持久化 env 行列表: 替换/新增 `export NAME='value'` 行（单引号转义），
 * value 为 null 时移除该变量的所有行。保留其它行（如用户自行编辑的内容）。
 * internal: 供本模块单元测试验证。
 */
internal fun upsertEnvLine(lines: List<String>, name: String, value: String?): List<String> {
    val prefix = "export $name="
    val without = lines.filterNot { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith(prefix) || trimmed == "export $name"
    }
    val newLine = value?.let {
        "export $name='" + it.replace("'", "'\\''") + "'"
    } ?: return without
    return without + newLine
}
