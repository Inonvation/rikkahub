package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 远端数据库元信息（`db.meta.json`）。 */
@Serializable
data class DbMeta(
    val sha256: String = "",
    val timestamp: Long = 0L,
    val deviceId: String = "",
    val size: Long = 0L,
)

/** 同步结果统计。 */
data class SyncResult(
    val success: Boolean,
    val pushed: List<String> = emptyList(),
    val pulled: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val error: String? = null,
)

/** settings 读写抽象（App 侧包装 SettingsStore，测试侧用内存 fake）。 */
interface SyncSettingsAccess {
    suspend fun currentSettings(): Settings
    suspend fun saveSettings(settings: Settings)
}

/** 数据库操作抽象（App 侧用 AppDatabase + context，测试侧用临时文件）。 */
interface DbSyncAccess {
    /** WAL checkpoint，保证 -wal 合并进主文件。 */
    fun checkpoint()
    /** 数据库主文件。 */
    fun dbFile(): File
}

/**
 * 远端增量同步引擎（决策表 + 增量编排 + 冲突处理）。
 *
 * 同步单元 relPath 命名空间（与 SyncProvider 注释一致）：
 * - `settings.json`          设置白名单（虚拟单元，内容由 codec 生成）
 * - `db/rikka_hub.db`        checkpoint 后的单文件数据库
 * - `upload/<name>` / `skills/<relpath>` / `fonts/<name>`   托管附件
 *
 * 流程：先 pull 再 push（先吸收其他设备变更，再传播本机变更，减少冲突）。
 * 全程 [Dispatchers.IO]。互斥锁由调用方（T8/T9/T10）通过 [SyncStateStore] 获取。
 */
class SyncManager(
    private val settings: SyncSettingsAccess,
    private val stateStore: SyncStateStore,
    private val dbAccess: DbSyncAccess,
    private val filesRoot: File,
    private val syncPendingDir: File,
    private val json: Json = JsonInstant,
    private val logger: (String) -> Unit = {},
) {
    private companion object {
        const val DB_PATH = "db/rikka_hub.db"
        const val DB_META_PATH = "db.meta.json"
    }

    private enum class UnitKind { SETTINGS, DATABASE, FILE }

    private class SyncUnit(
        val relPath: String,
        val kind: UnitKind,
        val file: File? = null,
    )

    /**
     * 执行一次完整同步。config 决定同步哪些单元。
     *
     * @return 统计结果；网络/认证失败时 success=false 且 error 有值。
     */
    suspend fun sync(provider: SyncProvider, config: SyncConfig): SyncResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = stateStore.getOrCreateDeviceId()
            provider.ensureBaseCollection().getOrThrow()

            val localSettings = settings.currentSettings()
            val settingsBytes = settingsContent(localSettings)
            val settingsSha = sha256(settingsBytes)
            val localUnits = collectLocalUnits(config)
            val remoteByPath = provider.listRemote().getOrThrow().associateBy { it.relPath }

            // 远端 db.meta.json（存在时），供 DB 单元校验/冲突
            val remoteDbMeta = if (config.includeDatabase) fetchRemoteDbMeta(provider) else null

            val pushed = mutableListOf<String>()
            val pulled = mutableListOf<String>()
            val conflicts = mutableListOf<String>()
            val deleted = mutableListOf<String>()

            // 先 pull 后 push
            val allPaths = (localUnits.keys + remoteByPath.keys).toSortedSet()
            for (relPath in allPaths) {
                if (relPath == DB_META_PATH) continue // db.meta.json 由 DB 单元统一管理
                val unit = localUnits[relPath]
                val remote = remoteByPath[relPath]
                val record = stateStore.current().syncedFiles[relPath]

                val remoteTag = remote?.let { remoteTagOf(it) }

                val action = when (unit?.kind) {
                    UnitKind.SETTINGS -> decideSettings(
                        localSettingsSha = settingsSha,
                        remote = remote,
                        remoteTag = remoteTag,
                        record = record,
                    )

                    UnitKind.DATABASE -> decideDatabase(
                        localSha = sha256OfUnit(unit!!),
                        remoteMeta = remoteDbMeta,
                        record = record,
                    )

                    else -> SyncDecision.decide(
                        SyncDecision.Input(
                            localExists = unit != null,
                            localSha = unit?.let { sha256OfUnit(it) },
                            localUpdatedAtMs = unit?.let { updatedAtMsOfUnit(it) } ?: 0L,
                            record = record,
                            remoteExists = remote != null,
                            remoteTag = remoteTag,
                            remoteLastModifiedMs = remote?.lastModifiedMs,
                        )
                    )
                }

                when (action) {
                    SyncDecision.Action.Push -> {
                        performPush(provider, config, unit!!, settingsBytes, deviceId, pushed)
                    }

                    SyncDecision.Action.Pull -> {
                        performPull(provider, unit, remote!!, config, pulled)
                    }

                    SyncDecision.Action.Conflict -> {
                        performConflict(provider, unit, remote!!, remoteDbMeta, deviceId, config, conflicts)
                    }

                    SyncDecision.Action.DeleteRemote -> {
                        provider.delete(relPath).getOrThrow()
                        updateRecord(relPath, null)
                        deleted += relPath
                    }

                    SyncDecision.Action.Skip -> Unit
                }
            }

            // 清理：syncedFiles 中本地与远端都不存在的遗留记录
            pruneRecords(localUnits.keys, remoteByPath.keys)

            stateStore.update { it.copy(lastSyncTime = System.currentTimeMillis(), pendingSync = false) }
            logger("sync done: pushed=$pushed pulled=$pulled conflicts=$conflicts deleted=$deleted")
            SyncResult(
                success = true,
                pushed = pushed,
                pulled = pulled,
                conflicts = conflicts,
                deleted = deleted,
            )
        } catch (e: Exception) {
            logger("sync failed: ${e.message}")
            SyncResult(success = false, error = e.message)
        }
    }

    // ------------------------------------------------------------------
    // 单元收集
    // ------------------------------------------------------------------

    /** 读取远端 db.meta.json（不存在/解析失败返回 null，不视为错误）。 */
    private suspend fun fetchRemoteDbMeta(provider: SyncProvider): DbMeta? {
        val tmp = File(syncPendingDir, "db.meta.remote.json")
        tmp.parentFile?.mkdirs()
        return try {
            provider.downloadToFile("db.meta.json", tmp).getOrThrow()
            val meta = json.decodeFromString<DbMeta>(tmp.readText(Charsets.UTF_8))
            logger("remote db meta: ${meta.sha256.take(8)} ts=${meta.timestamp}")
            meta
        } catch (e: Exception) {
            logger("remote db meta absent: ${e.message}")
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * 应用待恢复的 DB（应用启动时、任何 get<AppDatabase>() 之前调用）。
     * 校验 pending 文件存在 → 覆盖 databases/rikka_hub.db 并删 -wal/-shm → 清 dbRestorePending。
     * 失败只记日志不抛出，DB 以本地现状启动。
     *
     * @return 是否成功应用
     */
    suspend fun applyPendingDbRestore(): Boolean = withContext(Dispatchers.IO) {
        val pendingPath = stateStore.current().dbRestorePending ?: return@withContext false
        val pending = File(pendingPath)
        if (!pending.exists()) {
            logger("applyPendingDbRestore: pending file missing, clearing state")
            stateStore.update { it.copy(dbRestorePending = null) }
            return@withContext false
        }
        try {
            val target = dbAccess.dbFile()
            val parent = target.parentFile
            // 删除旧的 -wal/-shm，防止与覆盖后的主文件不一致
            parent?.let { p ->
                File(p, "rikka_hub-wal").delete()
                File(p, "rikka_hub-shm").delete()
            }
            parent?.mkdirs()
            pending.copyTo(target, overwrite = true)
            pending.delete()
            stateStore.update { it.copy(dbRestorePending = null) }
            logger("applyPendingDbRestore: db restored from pending")
            true
        } catch (e: Exception) {
            logger("applyPendingDbRestore failed: ${e.message}")
            pending.delete()
            stateStore.update { it.copy(dbRestorePending = null) }
            false
        }
    }

    private fun collectLocalUnits(config: SyncConfig): Map<String, SyncUnit> {
        val units = mutableMapOf<String, SyncUnit>()
        // settings.json 总是参与同步（白名单内容）
        units["settings.json"] = SyncUnit("settings.json", UnitKind.SETTINGS)
        if (config.includeDatabase) {
            units[DB_PATH] = SyncUnit(DB_PATH, UnitKind.DATABASE)
        }
        if (config.includeFiles) {
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS).forEach { folder ->
                val dir = File(filesRoot, folder)
                walkFiles(dir, prefix = "$folder/").forEach { (rel, file) ->
                    units[rel] = SyncUnit(rel, UnitKind.FILE, file)
                }
            }
        }
        return units
    }

    private fun walkFiles(dir: File, prefix: String): List<Pair<String, File>> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val result = mutableListOf<Pair<String, File>>()
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                result += walkFiles(f, "$prefix${f.name}/")
            } else if (f.isFile && !isConflictCopy(f.name)) {
                result += (prefix + f.name to f)
            }
        }
        return result
    }

    /** 冲突副本（.conflict-<deviceId> / .old-<ts> 后缀）不参与后续增量。 */
    private fun isConflictCopy(name: String): Boolean {
        return name.contains(".conflict-") || name.contains(".old-")
    }

    // ------------------------------------------------------------------
    // 单元内容 / 元信息
    // ------------------------------------------------------------------

    private fun sha256OfUnit(unit: SyncUnit, settingsBytes: ByteArray? = null): String {
        return when (unit.kind) {
            UnitKind.SETTINGS -> sha256(settingsBytes ?: ByteArray(0))
            UnitKind.DATABASE -> {
                dbAccess.checkpoint()
                sha256(dbAccess.dbFile())
            }

            UnitKind.FILE -> sha256(unit.file!!)
        }
    }

    private fun updatedAtMsOfUnit(unit: SyncUnit): Long {
        return when (unit.kind) {
            UnitKind.SETTINGS -> System.currentTimeMillis()
            UnitKind.DATABASE -> {
                dbAccess.checkpoint()
                dbAccess.dbFile().lastModified()
            }

            UnitKind.FILE -> unit.file!!.lastModified()
        }
    }

    // ------------------------------------------------------------------
    // DB 专用决策（用 db.meta.json 的 sha256 + timestamp 做中间桥梁）
    // ------------------------------------------------------------------

    /**
     * DB 决策：与附件不同，DB 用 db.meta.json 记录的 sha256 与 timestamp 判断。
     * - 远端 meta 不存在 → push（首次/未初始化）
     * - 本地 == 记录 sha && 远端 == 记录 sha → skip
     * - 本地 == 记录 sha && 远端 != 记录 sha → pull（远端被改）
     * - 本地 != 记录 sha && 远端 == 记录 sha → push（本地被改）
     * - 双端都变 → 冲突，按 timestamp 大者胜
     */
    private fun decideDatabase(
        localSha: String,
        remoteMeta: DbMeta?,
        record: FileSyncRecord?,
    ): SyncDecision.Action {
        if (remoteMeta == null) return SyncDecision.Action.Push
        if (record == null) return SyncDecision.Action.Pull // 首次见远端 db
        return when {
            localSha == record.sha256 && remoteMeta.sha256 == record.remoteTag -> SyncDecision.Action.Skip
            localSha == record.sha256 -> SyncDecision.Action.Pull
            remoteMeta.sha256 == record.remoteTag -> SyncDecision.Action.Push
            else -> SyncDecision.Action.Conflict
        }
    }

    private fun settingsContent(settings: Settings): ByteArray =
        SettingsSyncCodec.toSyncableJson(settings).toByteArray(Charsets.UTF_8)

    private fun remoteTagOf(remote: RemoteFile): String {
        remote.etag?.takeIf { it.isNotBlank() }?.let { return it }
        remote.lastModifiedMs?.let { return "lm:$it" }
        return ""
    }

    // ------------------------------------------------------------------
    // settings 专用决策（可合并，冲突 = pull + merge）
    // ------------------------------------------------------------------

    private fun decideSettings(
        localSettingsSha: String?,
        remote: RemoteFile?,
        remoteTag: String?,
        record: FileSyncRecord?,
    ): SyncDecision.Action {
        val remoteExists = remote != null
        return when {
            !remoteExists -> SyncDecision.Action.Push
            record == null -> SyncDecision.Action.Pull
            localSettingsSha == record.sha256 && remoteTag == record.remoteTag -> SyncDecision.Action.Skip
            localSettingsSha == record.sha256 && remoteTag != record.remoteTag -> SyncDecision.Action.Pull
            localSettingsSha != record.sha256 && remoteTag == record.remoteTag -> SyncDecision.Action.Push
            else -> SyncDecision.Action.Pull // 冲突也走 pull + merge（settings 可合并，白名单外回落本地）
        }
    }

    // ------------------------------------------------------------------
    // 动作执行
    // ------------------------------------------------------------------

    private suspend fun performPush(
        provider: SyncProvider,
        config: SyncConfig,
        unit: SyncUnit,
        settingsBytes: ByteArray,
        deviceId: String,
        pushed: MutableList<String>,
    ) {
        if (unit.kind == UnitKind.DATABASE) {
            pushDatabase(provider, deviceId, pushed)
            return
        }
        val content = when (unit.kind) {
            UnitKind.SETTINGS -> settingsBytes
            UnitKind.FILE -> unit.file!!.readBytes()
            UnitKind.DATABASE -> error("handled above")
        }
        val result = provider.upload(unit.relPath, content).getOrThrow()
        val remoteTag = result.etag ?: result.lastModifiedMs?.let { "lm:$it" } ?: ""
        updateRecord(
            unit.relPath,
            FileSyncRecord(
                sha256 = sha256(content),
                remoteTag = remoteTag,
                updatedAtMs = unit.let { updatedAtMsOfUnit(it) },
                deviceId = deviceId,
            )
        )
        pushed += unit.relPath
    }

    /** checkpoint 后上传单文件 DB，并写 db.meta.json（sha256/timestamp/deviceId/size）。 */
    private suspend fun pushDatabase(
        provider: SyncProvider,
        deviceId: String,
        pushed: MutableList<String>,
    ) {
        dbAccess.checkpoint()
        val dbFile = dbAccess.dbFile()
        val content = dbFile.readBytes()
        val dbSha = sha256(content)
        val result = provider.upload(DB_PATH, content).getOrThrow()
        provider.upload(
            DB_META_PATH,
            JsonInstant.encodeToString(
                DbMeta(
                    sha256 = dbSha,
                    timestamp = System.currentTimeMillis(),
                    deviceId = deviceId,
                    size = content.size.toLong(),
                )
            ).toByteArray(Charsets.UTF_8),
        ).getOrThrow()
        updateRecord(
            DB_PATH,
            FileSyncRecord(
                sha256 = dbSha,
                remoteTag = dbSha,
                updatedAtMs = System.currentTimeMillis(),
                deviceId = deviceId,
            )
        )
        pushed += DB_PATH
    }

    private suspend fun performPull(
        provider: SyncProvider,
        unit: SyncUnit?,
        remote: RemoteFile,
        config: SyncConfig,
        pulled: MutableList<String>,
    ) {
        if (unit == null) {
            // 首见远端文件
            if (remote.relPath == "settings.json") {
                pullSettings(provider, remote)
            } else if (remote.relPath == DB_PATH) {
                pullDatabase(provider, remote)
            } else {
                pullFile(provider, remote)
            }
        } else {
            when (unit.kind) {
                UnitKind.SETTINGS -> pullSettings(provider, remote)
                UnitKind.DATABASE -> pullDatabase(provider, remote)
                UnitKind.FILE -> pullFile(provider, remote)
            }
        }
        pulled += remote.relPath
    }

    private suspend fun pullSettings(provider: SyncProvider, remote: RemoteFile) {
        val tmp = File(syncPendingDir, "settings.json")
        tmp.parentFile?.mkdirs()
        provider.downloadToFile("settings.json", tmp).getOrThrow()
        val remoteJson = tmp.readText(Charsets.UTF_8)
        val local = settings.currentSettings()
        val merged = SettingsSyncCodec.fromSyncableJson(remoteJson, local)
        settings.saveSettings(merged)
        tmp.delete()
        updateRecord(
            "settings.json",
            FileSyncRecord(
                sha256 = sha256(SettingsSyncCodec.toSyncableJson(merged).toByteArray()),
                // 记录远端 tag：下次本地未变时据此判定 Skip，避免每轮都重复 pull
                remoteTag = remoteTagOf(remote),
                updatedAtMs = remote.lastModifiedMs ?: System.currentTimeMillis(),
                deviceId = "",
            )
        )
    }

    /**
     * 下载远端 DB 到 pending 目录，校验 sha256 与远端 db.meta.json 一致后才记 dbRestorePending，
     * 不直接替换活动 DB（替换时机在应用重启的 applyPendingDbRestore）。
     */
    private suspend fun pullDatabase(provider: SyncProvider, remote: RemoteFile) {
        val tmp = File(syncPendingDir, "rikka_hub.pending.db")
        tmp.parentFile?.mkdirs()
        provider.downloadToFile(DB_PATH, tmp).getOrThrow()
        val downloadedSha = sha256(tmp)
        // 校验远端 db.meta.json 的 sha256（若可读），防止下载损坏
        val expectedSha = fetchRemoteDbMeta(provider)?.sha256
        if (expectedSha != null && expectedSha != downloadedSha) {
            logger("db sha256 mismatch: expected $expectedSha got $downloadedSha, refusing")
            tmp.delete()
            throw IllegalStateException("db sha256 mismatch")
        }
        stateStore.update { it.copy(dbRestorePending = tmp.absolutePath) }
        updateRecord(
            DB_PATH,
            FileSyncRecord(
                sha256 = downloadedSha,
                remoteTag = downloadedSha,
                updatedAtMs = remote.lastModifiedMs ?: System.currentTimeMillis(),
                deviceId = "",
            )
        )
    }

    private suspend fun pullFile(provider: SyncProvider, remote: RemoteFile) {
        val localTarget = resolveLocalTarget(remote.relPath)
        localTarget.parentFile?.mkdirs()
        provider.downloadToFile(remote.relPath, localTarget).getOrThrow()
        updateRecord(
            remote.relPath,
            FileSyncRecord(
                sha256 = sha256(localTarget),
                // 记录远端 tag：下次本地未变时据此判定 Skip，避免每轮都重复 pull
                remoteTag = remoteTagOf(remote),
                updatedAtMs = remote.lastModifiedMs ?: localTarget.lastModified(),
                deviceId = "",
            )
        )
    }

    private suspend fun performConflict(
        provider: SyncProvider,
        unit: SyncUnit?,
        remote: RemoteFile,
        remoteDbMeta: DbMeta?,
        deviceId: String,
        config: SyncConfig,
        conflicts: MutableList<String>,
    ) {
        val localUpdatedAt = unit?.let { updatedAtMsOfUnit(it) } ?: 0L
        // remoteDbMeta 仅用于 DATABASE 冲突；附件/其他用远端 lastModified
        val remoteUpdatedAt = remote.lastModifiedMs ?: 0L

        when (unit?.kind) {
            UnitKind.SETTINGS -> {
                // settings 冲突走 pull + merge（白名单外回落本地），无副本
                performPull(provider, unit, remote, config, conflicts)
            }

            UnitKind.DATABASE -> {
                val dbRemoteUpdatedAt = remoteDbMeta?.timestamp ?: remote.lastModifiedMs ?: 0L
                val dbLocalWins = localUpdatedAt >= dbRemoteUpdatedAt
                dbAccess.checkpoint()
                if (dbLocalWins) {
                    // 本地胜：远端旧库留 db.old/<ts>/rikka_hub.db，再上传本地库 + meta
                    remoteDbMeta?.let { meta ->
                        val tmp = File(syncPendingDir, "db.old-${meta.timestamp}.db")
                        tmp.parentFile?.mkdirs()
                        try {
                            provider.downloadToFile(DB_PATH, tmp).getOrThrow()
                            provider.upload("db.old/${meta.timestamp}/rikka_hub.db", tmp.readBytes()).getOrThrow()
                        } finally {
                            tmp.delete()
                        }
                    }
                    pushDatabase(provider, deviceId, conflicts)
                } else {
                    // 远端胜：本地留 rikka_hub.db.old-<ts> 副本，再拉远端
                    val oldCopy = File(
                        dbAccess.dbFile().parentFile,
                        "rikka_hub.db.old-$dbRemoteUpdatedAt"
                    )
                    dbAccess.dbFile().copyTo(oldCopy, overwrite = true)
                    pullDatabase(provider, remote)
                }
                recordConflict(DB_PATH, dbRemoteUpdatedAt, deviceId, dbLocalWins)
                conflicts += DB_PATH
            }

            UnitKind.FILE -> {
                val fileLocalWins = localUpdatedAt >= remoteUpdatedAt
                if (fileLocalWins) {
                    // 本地胜：上传本地，把旧远端留 conflict 副本
                    val remoteConflictPath = "${remote.relPath}.conflict-$deviceId"
                    remoteConflictCopy(provider, remote.relPath, remoteConflictPath)
                    provider.upload(remote.relPath, unit.file!!.readBytes()).getOrThrow()
                } else {
                    // 远端胜：下载远端，本地留 conflict 副本
                    val localConflict = resolveLocalTarget(remote.relPath + ".conflict-$deviceId")
                    val target = resolveLocalTarget(remote.relPath)
                    if (target.exists()) {
                        localConflict.parentFile?.mkdirs()
                        target.copyTo(localConflict, overwrite = true)
                    }
                    provider.downloadToFile(remote.relPath, target).getOrThrow()
                }
                recordConflict(remote.relPath, remoteUpdatedAt, deviceId, fileLocalWins)
                conflicts += remote.relPath
            }

            null -> {
                // 远端存在、本地无记录冲突兜底：拉取
                performPull(provider, unit, remote, config, conflicts)
            }
        }
    }

    /** 记录冲突到 SyncStateStore.conflicts。 */
    private suspend fun recordConflict(relPath: String, atMs: Long, deviceId: String, localWins: Boolean) {
        stateStore.update { state ->
            state.copy(
                conflicts = state.conflicts + ConflictRecord(
                    relPath = relPath,
                    atMs = atMs,
                    winnerDevice = if (localWins) deviceId else "remote",
                )
            )
        }
    }

    /** 远端文件改名保存冲突副本（下载下来再传一份）。 */
    private suspend fun remoteConflictCopy(provider: SyncProvider, src: String, dst: String) {
        val tmp = File(syncPendingDir, "conflict-${System.currentTimeMillis()}.bin")
        tmp.parentFile?.mkdirs()
        try {
            provider.downloadToFile(src, tmp).getOrThrow()
            provider.upload(dst, tmp.readBytes()).getOrThrow()
        } finally {
            tmp.delete()
        }
    }

    private fun resolveLocalTarget(relPath: String): File {
        return when {
            relPath.startsWith("upload/") -> File(filesRoot, relPath)
            relPath.startsWith("skills/") -> File(filesRoot, relPath)
            relPath.startsWith("fonts/") -> File(filesRoot, relPath)
            else -> File(filesRoot, relPath.substringAfterLast('/'))
        }
    }

    // ------------------------------------------------------------------
    // 记录维护
    // ------------------------------------------------------------------

    private suspend fun updateRecord(relPath: String, record: FileSyncRecord?) {
        stateStore.update { state ->
            val newMap = state.syncedFiles.toMutableMap()
            if (record == null) newMap.remove(relPath) else newMap[relPath] = record
            state.copy(syncedFiles = newMap)
        }
    }

    private suspend fun pruneRecords(localPaths: Set<String>, remotePaths: Set<String>) {
        stateStore.update { state ->
            val stale = state.syncedFiles.keys.filter { it !in localPaths && it !in remotePaths }
            if (stale.isEmpty()) {
                state
            } else {
                state.copy(syncedFiles = state.syncedFiles - stale.toSet())
            }
        }
    }

    // ------------------------------------------------------------------
    // hash
    // ------------------------------------------------------------------

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
