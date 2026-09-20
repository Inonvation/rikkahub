package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 自启动脚本在 files 区的约定目录（Rootfs 视角 /workspace/.rikka/startup，隐藏目录不进 shell 文件变更快照） */
const val WORKSPACE_AUTOSTART_DIR = ".rikka/startup"

private const val ENABLED_SUFFIX = ".sh"
private const val DISABLED_SUFFIX = ".sh.disabled"
/** 服务型脚本：app 持有进程、无超时，随 app 退出终止 */
internal const val SERVICE_SUFFIX = ".service.sh"
private const val SERVICE_DISABLED_SUFFIX = ".service.sh.disabled"

/** 单个自启动脚本的执行超时：初始化/挂服务都可能偏慢，但不容忍死循环脚本卡死引导 */
private const val SCRIPT_TIMEOUT_MS = 120_000L

/** 保存在内存里的单脚本输出尾部（供 UI「运行日志」查看；进程重启即失，不落盘） */
private const val OUTPUT_TAIL_CHARS = 4 * 1024

/** 服务进程输出尾部上限（有界缓冲，防疯狂刷日志撑爆内存） */
private const val SERVICE_TAIL_CHARS = 8 * 1024

data class AutostartScript(
    /** 带后缀的文件名，作为增删改的标识（如 mirror.sh / wb2api.service.sh.disabled） */
    val fileName: String,
    /** 展示名（去掉 .sh/.service.sh/.disabled 后缀） */
    val displayName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
    /** 服务型脚本（*.service.sh）：app 持有进程不限时，随 app 退出终止；普通脚本跑完即退、单脚本 120s 超时 */
    val service: Boolean = false,
)

data class AutostartScriptResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val finishedAt: Long,
    val outputTail: String,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

data class AutostartServiceStatus(
    val running: Boolean,
    val startedAt: Long? = null,
    val exitCode: Int? = null,
    val outputTail: String = "",
)

data class AutostartState(
    val scripts: List<AutostartScript> = emptyList(),
    val running: Boolean = false,
    /** 本进程内上次执行结果，key = fileName（仅普通脚本） */
    val results: Map<String, AutostartScriptResult> = emptyMap(),
    /** 服务型脚本运行状态，key = fileName（进程内状态，app 重启即失） */
    val services: Map<String, AutostartServiceStatus> = emptyMap(),
    /** 本进程内已执行过开机引导 */
    val booted: Boolean = false,
)

/**
 * 工作区自启动脚本引导器。
 *
 * 语义对齐「电脑开机」：每次 app 进程启动算一次开机，工作区首次被 shell（AI 工具/异步任务）
 * 或终端触达时，按文件名顺序执行一遍 `.rikka/startup/` 下所有启用脚本（bash -l，cwd=/workspace）。
 * 引导异步进行，不阻塞首个命令；不承诺跨进程存续，进程重启后随下次触达重跑。
 *
 * 只依赖 [WorkspaceDAO] + [WorkspaceManager]（不依赖 WorkspaceRepository）：
 * 执行走 manager 直连，避免与 Repository 内的 ensureBooted 钩子形成递归。
 */
class WorkspaceAutostartRunner(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val appScope: CoroutineScope,
) {
    private val states = MutableStateFlow<Map<String, AutostartState>>(emptyMap())
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /** 活着的服务进程，key = "$workspaceId/$fileName"；进程退出由监视线程更新状态并移除 */
    private val serviceProcesses = ConcurrentHashMap<String, ServiceHandle>()

    /** 服务启动互斥：boot 引导/开关/工具 start 并发拉起同一服务时防双 spawn */
    private val serviceStartMutexes = ConcurrentHashMap<String, Mutex>()

    private data class ServiceHandle(
        val process: Process,
        val fileName: String,
        val startedAt: Long,
    )

    fun observe(workspaceId: String): Flow<AutostartState> =
        states.map { it[workspaceId] ?: AutostartState() }.distinctUntilChanged()

    /** 当前状态快照（与 [observe] 同源；配合先 refreshScripts 再读取使用） */
    fun state(workspaceId: String): AutostartState =
        states.value[workspaceId] ?: AutostartState()

    /**
     * 每进程一次的开机引导。booted 置位与检查在互斥锁内完成，避免并发首次触达
     * （AI shell + 终端同时进入）时双执行引导；脚本执行本身再由 [runScriptPass] 的锁串行化。
     */
    fun ensureBooted(workspaceId: String) {
        appScope.launch {
            val mutex = mutexes.getOrPut(workspaceId) { Mutex() }
            mutex.withLock {
                if (states.value[workspaceId]?.booted == true) return@launch
                update(workspaceId) { it.copy(booted = true) }
            }
            runCatching { runScriptPass(workspaceId) }
                .onFailure { Log.w(TAG, "autostart boot failed: $workspaceId", it) }
        }
    }

    /** 重新扫描脚本目录（增删改/编辑器返回后调用） */
    suspend fun refreshScripts(workspaceId: String) {
        withContext(Dispatchers.IO) {
            val scripts = runCatching { listScripts(workspaceId) }.getOrDefault(emptyList())
            update(workspaceId) { it.copy(scripts = scripts) }
        }
    }

    /** UI「立即执行」/AI run：无视 booted 状态强制执行一轮并等待完成 */
    suspend fun runAll(workspaceId: String): AutostartState = runScriptPass(workspaceId, force = true)

    suspend fun createScript(workspaceId: String, rawName: String, content: String): AutostartScript {
        val fileName = autostartScriptFileName(rawName)
            ?: error("脚本名只能包含字母/数字/点/下划线/连字符，且以 .sh 结尾")
        return withContext(Dispatchers.IO) {
            val root = requireRoot(workspaceId)
            manager.writeText(root, "$WORKSPACE_AUTOSTART_DIR/$fileName", content, overwrite = false)
            refreshScripts(workspaceId)
            AutostartScript(
                fileName = fileName,
                displayName = fileName.removeSuffix(SERVICE_SUFFIX).removeSuffix(ENABLED_SUFFIX),
                enabled = true,
                sizeBytes = content.toByteArray().size.toLong(),
                updatedAt = System.currentTimeMillis(),
                service = fileName.endsWith(SERVICE_SUFFIX),
            )
        }
    }

    /**
     * 启用/停用 = 改名（追加/去掉 .disabled 后缀），零额外元数据。
     * 服务型脚本联动进程：启用即启动、停用即停止（开关语义 = 「我要它跑/不跑」）。
     */
    suspend fun setEnabled(workspaceId: String, fileName: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val root = requireRoot(workspaceId)
            val isService = fileName.endsWith(SERVICE_DISABLED_SUFFIX) || fileName.endsWith(SERVICE_SUFFIX)
            val file = requireScriptFile(root, fileName)
            val targetName = when {
                enabled && fileName.endsWith(".disabled") -> fileName.removeSuffix(".disabled")
                !enabled && fileName.endsWith(ENABLED_SUFFIX) || !enabled && fileName.endsWith(SERVICE_SUFFIX) ->
                    fileName + ".disabled"

                else -> return@withContext false
            }
            if (!file.renameTo(File(file.parentFile, targetName))) {
                error("重命名失败: $fileName -> $targetName")
            }
            if (isService) {
                if (enabled) startService(workspaceId, targetName) else stopService(workspaceId, fileName)
            }
            refreshScripts(workspaceId)
            true
        }

    suspend fun deleteScript(workspaceId: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val root = requireRoot(workspaceId)
            if (fileName.endsWith(SERVICE_SUFFIX)) stopService(workspaceId, fileName)
            val file = requireScriptFile(root, fileName)
            val deleted = file.delete()
            update(workspaceId) {
                it.copy(results = it.results - fileName, services = it.services - fileName)
            }
            refreshScripts(workspaceId)
            deleted
        }

    // ===== 服务型脚本（app 持有进程，无超时，随 app 退出终止） =====

    /** 启动服务脚本（已存活则幂等返回）；脚本内容前台运行即可，进程由本 runner 持有 */
    suspend fun startService(workspaceId: String, fileName: String) {
        val root = requireRoot(workspaceId)
        val key = "$workspaceId/$fileName"
        val startMutex = serviceStartMutexes.getOrPut(key) { Mutex() }
        startMutex.withLock {
            if (serviceProcesses[key]?.process?.isAlive == true) return
            withContext(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                update(workspaceId) {
                    it.copy(services = it.services + (fileName to AutostartServiceStatus(running = true, startedAt = startedAt)))
                }
                try {
                    // 文件名经 autostartScriptFileName 白名单校验，单引号内无注入面
                    val process = manager.spawnCommand(root, "bash -l '/workspace/$WORKSPACE_AUTOSTART_DIR/$fileName'")
                    serviceProcesses[key] = ServiceHandle(process, fileName, startedAt)
                    watchService(workspaceId, key, fileName, process, startedAt)
                } catch (e: Throwable) {
                    Log.w(TAG, "start service failed: $fileName", e)
                    serviceProcesses.remove(key)
                    update(workspaceId) {
                        it.copy(
                            services = it.services + (
                                fileName to AutostartServiceStatus(
                                    running = false,
                                    startedAt = startedAt,
                                    exitCode = -1,
                                    outputTail = "启动失败: ${e.message}",
                                )
                                ),
                        )
                    }
                }
            }
        }
    }

    /** 停止服务脚本：杀掉 proot 主进程，--kill-on-exit 连带清理 rootfs 内全部子进程 */
    suspend fun stopService(workspaceId: String, fileName: String) {
        val handle = serviceProcesses.remove("$workspaceId/$fileName") ?: return
        withContext(Dispatchers.IO) {
            runCatching { handle.process.destroyForcibly() }
            runCatching { handle.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        }
        // 退出终态由 [watchService] 监视线程统一写回状态
    }

    /** 工作区删除/rootfs 重装时清理：停掉本工作区全部服务并丢弃状态 */
    fun clearWorkspace(workspaceId: String) {
        serviceProcesses.keys.filter { it.startsWith("$workspaceId/") }.forEach { key ->
            serviceProcesses.remove(key)?.let { runCatching { it.process.destroyForcibly() } }
        }
        states.update { it - workspaceId }
    }

    /**
     * 输出收集 + 退出监视：两个 daemon 线程读 stdout/stderr 进有界缓冲，
     * 一个线程 waitFor 进程退出后把终态（running=false + exitCode + 输出尾部）写回状态。
     */
    private fun watchService(
        workspaceId: String,
        key: String,
        fileName: String,
        process: Process,
        startedAt: Long,
    ) {
        val tail = StringBuilder()
        fun collect(stream: java.io.InputStream) {
            Thread {
                try {
                    stream.bufferedReader().use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            synchronized(tail) {
                                tail.append(buffer, 0, read)
                                if (tail.length > SERVICE_TAIL_CHARS * 2) {
                                    tail.delete(0, tail.length - SERVICE_TAIL_CHARS)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 进程被杀时流关闭, 保留已读内容
                }
            }.apply { isDaemon = true }.start()
        }
        collect(process.inputStream)
        collect(process.errorStream)

        Thread {
            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            serviceProcesses.remove(key)
            // 归属校验：期间若同 key 已被更新的进程接管（快速停止→再启动），旧监视线程不得覆盖新进程的运行态
            val current = serviceProcesses[key]
            if (current != null && current.process !== process) return@Thread
            update(workspaceId) {
                it.copy(
                    services = it.services + (
                        fileName to AutostartServiceStatus(
                            running = false,
                            startedAt = startedAt,
                            exitCode = exitCode,
                            outputTail = synchronized(tail) { tail.toString().takeLast(SERVICE_TAIL_CHARS) },
                        )
                        ),
                )
            }
        }.apply { isDaemon = true }.start()
    }

    private suspend fun runScriptPass(workspaceId: String, force: Boolean = false): AutostartState {
        val mutex = mutexes.getOrPut(workspaceId) { Mutex() }
        mutex.withLock {
            val workspace = dao.getById(workspaceId)
            if (workspace == null || !manager.hasRootfs(workspace.root)) {
                // rootfs 未就绪：撤销 booted 置位，等下次触达重试
                update(workspaceId) { it.copy(booted = false, running = false) }
                return current(workspaceId)
            }
            val scripts = runCatching { listScripts(workspaceId) }.getOrDefault(emptyList())
            update(workspaceId) { it.copy(scripts = scripts, booted = true, running = true) }
            for (script in scripts.filter { it.enabled }) {
                if (script.service) {
                    // 服务型：确保进程在跑即返回（已存活幂等跳过），不阻塞后续脚本
                    runCatching { startService(workspaceId, script.fileName) }
                        .onFailure { Log.w(TAG, "boot service failed: ${script.fileName}", it) }
                } else {
                    val result = runScript(workspace.root, script.fileName)
                    update(workspaceId) { it.copy(results = it.results + (script.fileName to result)) }
                }
            }
            update(workspaceId) { it.copy(running = false) }
            return current(workspaceId)
        }
    }

    private suspend fun runScript(root: String, fileName: String): AutostartScriptResult {
        val start = System.currentTimeMillis()
        // 文件名经 autostartScriptFileName 白名单校验，单引号内无注入面
        val command = "bash -l '/workspace/$WORKSPACE_AUTOSTART_DIR/$fileName'"
        return withContext(Dispatchers.IO) {
            val result = runCatching {
                manager.executeCommand(root, command, cwd = "", timeoutMillis = SCRIPT_TIMEOUT_MS)
            }
            val duration = System.currentTimeMillis() - start
            result.fold(
                onSuccess = { r ->
                    AutostartScriptResult(
                        exitCode = r.exitCode,
                        timedOut = r.timedOut,
                        durationMs = duration,
                        finishedAt = System.currentTimeMillis(),
                        outputTail = (r.stdout + r.stderr).takeLast(OUTPUT_TAIL_CHARS),
                    )
                },
                onFailure = { e ->
                    Log.w(TAG, "autostart script failed: $fileName", e)
                    AutostartScriptResult(
                        exitCode = -1,
                        timedOut = false,
                        durationMs = duration,
                        finishedAt = System.currentTimeMillis(),
                        outputTail = "launcher error: ${e.message}",
                    )
                },
            )
        }
    }

    private suspend fun listScripts(workspaceId: String): List<AutostartScript> {
        val root = requireRoot(workspaceId)
        manager.ensureWorkspace(root)
        val dir = File(manager.filesDir(root), WORKSPACE_AUTOSTART_DIR)
        return parseAutostartScripts(dir.listFiles()?.toList().orEmpty())
    }

    private suspend fun requireRoot(workspaceId: String): String {
        val root = dao.getById(workspaceId)?.root ?: error("Workspace not found: $workspaceId")
        manager.ensureWorkspace(root)
        return root
    }

    private fun requireScriptFile(root: String, fileName: String): File {
        require(File(fileName).name == fileName) { "非法脚本文件名: $fileName" }
        val file = File(manager.filesDir(root), "$WORKSPACE_AUTOSTART_DIR/$fileName")
        require(file.isFile) { "脚本不存在: $fileName" }
        return file
    }

    private fun current(workspaceId: String): AutostartState =
        states.value[workspaceId] ?: AutostartState()

    private fun update(workspaceId: String, transform: (AutostartState) -> AutostartState) {
        states.update { it + (workspaceId to transform(it[workspaceId] ?: AutostartState())) }
    }

    private companion object {
        private const val TAG = "WorkspaceAutostart"
    }
}

/**
 * 脚本发现纯逻辑：只认 *.sh（启用）与 *.sh.disabled（停用），其中 *.service.sh 为服务型，
 * 其余忽略；按文件名排序。internal: 供 JVM 单测验证。
 */
internal fun parseAutostartScripts(files: List<File>): List<AutostartScript> =
    files.asSequence()
        .filter { it.isFile }
        .mapNotNull { file ->
            val name = file.name
            when {
                name.endsWith(SERVICE_DISABLED_SUFFIX) -> AutostartScript(
                    fileName = name,
                    displayName = name.removeSuffix(".disabled").removeSuffix(SERVICE_SUFFIX),
                    enabled = false,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                    service = true,
                )

                name.endsWith(SERVICE_SUFFIX) -> AutostartScript(
                    fileName = name,
                    displayName = name.removeSuffix(SERVICE_SUFFIX),
                    enabled = true,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                    service = true,
                )

                name.endsWith(DISABLED_SUFFIX) -> AutostartScript(
                    fileName = name,
                    displayName = name.removeSuffix(".disabled").removeSuffix(ENABLED_SUFFIX),
                    enabled = false,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                )

                name.endsWith(ENABLED_SUFFIX) -> AutostartScript(
                    fileName = name,
                    displayName = name.removeSuffix(ENABLED_SUFFIX),
                    enabled = true,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                )

                else -> null
            }
        }
        .sortedBy { it.fileName }
        .toList()

/**
 * 用户/AI 输入的脚本名 → 合法文件名（追加 .sh 后缀）；非法返回 null。
 * 白名单字符集杜绝路径穿越与 shell 注入。internal: 供 JVM 单测验证。
 */
internal fun autostartScriptFileName(raw: String): String? {
    val name = raw.trim().trimStart('/')
    val withSuffix = if (name.endsWith(ENABLED_SUFFIX)) name else "$name$ENABLED_SUFFIX"
    if (withSuffix == ENABLED_SUFFIX) return null
    val base = withSuffix.removeSuffix(ENABLED_SUFFIX)
    return if (base.matches(Regex("[A-Za-z0-9._-]+")) && base != "." && base != "..") withSuffix else null
}
