package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** 差异预览中的单个条目（供确认弹窗展示）。 */
data class SyncPreviewItem(
    val relPath: String,
    val label: String,
    val size: Long = 0,
)

/**
 * 本地与云端的差异预览（只读检测结果，供用户确认后再执行）。
 * 按动作分组：
 * - [uploads]   Push：本地新增/改动 → 上传到云端
 * - [updates]   Pull：云端新增/改动 → 下载到本地
 * - [deletions] DeleteRemote：本地已删除 → 传播删除到云端
 * - [conflicts] Conflict：双端都改过 → 自动保留冲突副本
 */
data class SyncPreview(
    val uploads: List<SyncPreviewItem> = emptyList(),
    val updates: List<SyncPreviewItem> = emptyList(),
    val deletions: List<SyncPreviewItem> = emptyList(),
    val conflicts: List<SyncPreviewItem> = emptyList(),
) {
    /** 没有任何待处理差异。 */
    val isEmpty: Boolean
        get() = uploads.isEmpty() && updates.isEmpty() && deletions.isEmpty() && conflicts.isEmpty()

    val totalCount: Int
        get() = uploads.size + updates.size + deletions.size + conflicts.size

    /** 待传输内容的总字节数（用于预览展示总占用）。 */
    val totalSize: Long
        get() = uploads.sumOf { it.size } + updates.sumOf { it.size } +
            deletions.sumOf { it.size } + conflicts.sumOf { it.size }
}

/** 单个同步单元的执行状态（供进度视图展示）。 */
enum class SyncItemStatus { WAITING, IN_PROGRESS, DONE, FAILED }

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
    /** 会话增量同步的本地数据访问（App 侧由 ConversationRepository 实现；测试可空，不启用会话通道）。 */
    private val conversationAccess: ConversationSyncAccess? = null,
) {
    companion object {
        const val DB_PATH = "db/rikka_hub.db"
        const val DB_META_PATH = "db.meta.json"

        /** 并发上传文件的最大并发数（平衡吞吐与 WebDAV 限流/内存压力）。 */
        private const val FILE_UPLOAD_CONCURRENCY = 3

        /** 会话上传批大小：每批后提交 index（断点续传）并短暂停顿（规避限流）。 */
        internal const val CONVERSATION_UPLOAD_BATCH = 20

        /** 每两个会话上传之间的最小间隔（毫秒）：从源头降低 WebDAV 请求频率，规避 503 限流。 */
        internal const val CONVERSATION_UPLOAD_INTERVAL_MS = 150L

        /** 每批会话上传后的额外暂停（毫秒）。 */
        internal const val CONVERSATION_UPLOAD_PAUSE_MS = 1_000L

        /** 服务端限流（503/429）后重试前的冷却（毫秒）。 */
        internal const val CONVERSATION_THROTTLE_RETRY_MS = 60_000L

        /** 将同步 relPath 转为用户可读的展示名（纯函数，供差异预览弹窗使用）。 */
        fun humanLabel(relPath: String): String = when {
            relPath == "settings.json" -> "设置"
            relPath == DB_PATH -> "聊天数据库"
            relPath.startsWith("upload/") -> relPath.removePrefix("upload/")
            relPath.startsWith("fonts/") -> relPath.removePrefix("fonts/")
            relPath.startsWith("skills/") -> relPath.removePrefix("skills/")
            else -> relPath
        }
    }

    private enum class UnitKind { SETTINGS, DATABASE, FILE }

    private class SyncUnit(
        val relPath: String,
        val kind: UnitKind,
        val file: File? = null,
    )

    /** 决策计划项：携带执行所需的上下文（unit / remote / remoteDbMeta）。 */
    private class PlanItem(
        val relPath: String,
        val action: SyncDecision.Action,
        val unit: SyncUnit?,
        val remote: RemoteFile?,
        val remoteDbMeta: DbMeta?,
        val size: Long,
    )

    /** [computePlan] 的产物：计划项 + 收尾清理所需的路径集合。 */
    private class ComputeResult(
        val items: List<PlanItem>,
        val localPaths: Set<String>,
        val remotePaths: Set<String>,
    )

    /**
     * 执行一次完整同步。config 决定同步哪些单元。
     *
     * 内部流程：先 [computePlan] 基于当前本地/远端状态做只读决策，再逐个执行计划项。
     *
     * @param onProgress 每处理一个同步单元时回调（relPath + 最新状态），供进度视图实时刷新
     * @return 统计结果；网络/认证失败时 success=false 且 error 有值。
     */
    suspend fun sync(
        provider: SyncProvider,
        config: SyncConfig,
        onProgress: (relPath: String, status: SyncItemStatus) -> Unit = { _, _ -> },
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = stateStore.getOrCreateDeviceId()

            val plan = computePlan(provider, config)

            val pushed = mutableListOf<String>()
            val pulled = mutableListOf<String>()
            val conflicts = mutableListOf<String>()
            val deleted = mutableListOf<String>()

            // settings push 用当前最新设置生成内容（预览确认期间用户可能又改了设置）
            val settingsBytes = settingsContent(settings.currentSettings())

            // 文件类 Push 单独并发上传（大文件/多文件时显著提速）；其余动作保持顺序执行
            val filePushes = plan.items.filter {
                it.action == SyncDecision.Action.Push && it.unit?.kind == UnitKind.FILE
            }

            for (item in plan.items) {
                when (item.action) {
                    SyncDecision.Action.Push -> {
                        if (item.unit?.kind != UnitKind.FILE) {
                            runTracked(item.relPath, onProgress) {
                                performPush(provider, config, item.unit!!, settingsBytes, deviceId, pushed)
                            }
                        }
                    }

                    SyncDecision.Action.Pull -> {
                        runTracked(item.relPath, onProgress) {
                            performPull(provider, item.unit, item.remote!!, config, pulled)
                        }
                    }

                    SyncDecision.Action.Conflict -> {
                        runTracked(item.relPath, onProgress) {
                            performConflict(
                                provider,
                                item.unit,
                                item.remote!!,
                                item.remoteDbMeta,
                                deviceId,
                                config,
                                conflicts,
                            )
                        }
                    }

                    SyncDecision.Action.DeleteRemote -> {
                        runTracked(item.relPath, onProgress) {
                            provider.delete(item.relPath).getOrThrow()
                            updateRecord(item.relPath, null)
                            deleted += item.relPath
                        }
                    }

                    SyncDecision.Action.Skip -> Unit
                }
            }

            if (filePushes.isNotEmpty()) {
                val permits = Semaphore(FILE_UPLOAD_CONCURRENCY)
                withContext(Dispatchers.IO) {
                    filePushes.map { item ->
                        async {
                            permits.withPermit {
                                runTracked(item.relPath, onProgress) {
                                    performPush(provider, config, item.unit!!, settingsBytes, deviceId, pushed)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            // 清理：syncedFiles 中本地与远端都不存在的遗留记录
            pruneRecords(plan.localPaths, plan.remotePaths)

            // 会话增量同步：聊天记录按会话拆分上传/下载，发一条消息只传该会话的小文件
            if (config.includeConversations) {
                conversationAccess?.let { access ->
                    conversationSyncUpload(provider, config, access, onProgress)
                    conversationSyncDownload(provider, access, onProgress)
                }
            }

            stateStore.update { it.copy(lastSyncTime = System.currentTimeMillis(), pendingSync = false) }
            logger("sync done: pushed=$pushed pulled=$pulled conflicts=$conflicts deleted=$deleted")
            SyncResult(
                success = true,
                pushed = pushed,
                pulled = pulled,
                conflicts = conflicts,
                deleted = deleted,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消应向上传播，不能当作同步失败吞掉
            throw e
        } catch (e: Exception) {
            logger("sync failed: ${e.message}")
            SyncResult(success = false, error = e.message)
        }
    }

    /**
     * 会话增量上传（C1）：本地新增/修改的会话导出为 item.json 上传，并更新 index.json。
     * 判断依据：本地会话 syncUpdatedAt 与云端 index 中该会话的版本不同。
     * 全程幂等：index 未变则不上传 item；上传后以导出条目为准更新 index 再上传。
     */
    private suspend fun conversationSyncUpload(
        provider: SyncProvider,
        config: SyncConfig,
        access: ConversationSyncAccess,
        onProgress: (relPath: String, status: SyncItemStatus) -> Unit,
    ) {
        val localDeleted = access.localDeletedTombstones()
        val remoteResult = fetchRemoteConversationIndex(provider)
        // 云端 index 损坏/未知格式：跳过整个会话上传，绝不覆盖云端（避免墓碑丢失/多端收敛破坏）
        if (remoteResult is RemoteIndexResult.Corrupt) {
            logger("conversation sync: remote index corrupt, skip conversation upload")
            return
        }
        var index = (remoteResult as? RemoteIndexResult.Valid)?.index ?: ConversationIndex()
        // 应用本地删除墓碑到 index.deleted，并把被删会话从 active 列表中移除（index 是提交点）
        localDeleted.forEach { tombstone ->
            if (index.deleted.none { it.id == tombstone.id }) {
                index = index.copy(
                    deleted = index.deleted + tombstone,
                    conversations = index.conversations.filterNot { it.id == tombstone.id },
                )
            }
        }

        val now = System.currentTimeMillis()
        val changed = conversationUploadDiff(index, access)

        var uploadedInBatch = 0
        for (entry in changed) {
            // 防覆盖：若云端 index 比本地新（其他设备已更新同一会话），先下载合并吸收云端改动，
            // 再导出合并结果上传；下载合并失败则本次跳过该会话（保云端数据），留待下次同步
            val remoteSyncAt = index.entryById(entry.id)?.syncUpdatedAt ?: 0L
            if (remoteSyncAt > entry.syncUpdatedAt) {
                val merged = runCatching { downloadAndMergeConversation(provider, access, entry.id) }.isSuccess
                if (!merged) {
                    logger("conversation sync: merge-before-upload failed, skip ${entry.id}")
                    continue
                }
            }
            val item = access.exportConversation(entry.id) ?: continue
            // 上报进度（进度弹窗逐会话展示），失败由 runTracked 标记并向上传播
            runTracked(conversationItemPath(entry.id), onProgress) {
                uploadConversationItem(provider, entry.id, item)
            }
            // 导出可能初始化了版本（存量 0 → 时钟值），以 item 为准更新 index
            index = index.upsert(
                entry.copy(
                    syncUpdatedAt = item.syncUpdatedAt,
                    messageCount = item.nodes.sumOf { it.messages.size },
                ),
                now,
            )
            uploadedInBatch++
            logger("conversation sync: uploaded ${entry.id}")

            // 限速：避免高频 WebDAV 请求触发坚果云 503 限流
            delay(CONVERSATION_UPLOAD_INTERVAL_MS)

            // 每批后：提交 index（断点续传提交点）+ 额外停顿
            if (uploadedInBatch % CONVERSATION_UPLOAD_BATCH == 0) {
                provider.upload(CONVERSATION_INDEX_PATH, encodeConversationIndex(index)).getOrThrow()
                delay(CONVERSATION_UPLOAD_PAUSE_MS)
            }
        }

        if (changed.isNotEmpty() || localDeleted.isNotEmpty()) {
            provider.upload(CONVERSATION_INDEX_PATH, encodeConversationIndex(index)).getOrThrow()
            logger("conversation sync: index updated (${index.activeEntries().size} entries)")
            // 墓碑已写入 index 并上传，从本地清除，避免无限累积 + 每次同步重写 index
            if (localDeleted.isNotEmpty()) {
                access.clearLocalTombstones(localDeleted.map { it.id }.toSet())
            }
        }
    }

    /**
     * 上传单个会话文件；服务端限流（503/429）时等待冷却后重试一次，仍失败则向上抛出。
     * 由于 index 按批提交，重试/中断后已完成的会话不会重复上传。
     */
    private suspend fun uploadConversationItem(
        provider: SyncProvider,
        id: String,
        item: ConversationSyncItem,
    ) {
        val tmp = File(syncPendingDir, "conv-$id.json")
        tmp.parentFile?.mkdirs()
        try {
            tmp.writeBytes(encodeConversationItem(item))
            val relPath = conversationItemPath(id)
            try {
                provider.uploadFile(relPath, tmp).getOrThrow()
            } catch (e: Exception) {
                if (isThrottleError(e)) {
                    logger("conversation sync: throttled, retry after cooldown: $id")
                    delay(CONVERSATION_THROTTLE_RETRY_MS)
                    provider.uploadFile(relPath, tmp).getOrThrow()
                } else {
                    throw e
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun isThrottleError(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return msg.contains("429") || msg.contains("503") || msg.contains("throttle") || msg.contains("Rate limit")
    }

    /** 下载云端会话条目并合并到本地（上传前吸收云端改动 / 下载合并共用）。 */
    private suspend fun downloadAndMergeConversation(
        provider: SyncProvider,
        access: ConversationSyncAccess,
        id: String,
    ) {
        val tmp = File(syncPendingDir, "conv-dl-$id.json")
        tmp.parentFile?.mkdirs()
        try {
            provider.downloadToFile(conversationItemPath(id), tmp).getOrThrow()
            val item = decodeConversationItem(tmp.readBytes())
            if (item != null) access.mergeConversation(item)
        } finally {
            tmp.delete()
        }
    }

    /**
     * 会话增量下载（C2）：应用云端删除（index.deleted）+ 拉取云端更新的会话并合并。
     * 与上传配合：本地新则上传、云端新则下载合并，最终收敛一致。
     */
    private suspend fun conversationSyncDownload(
        provider: SyncProvider,
        access: ConversationSyncAccess,
        onProgress: (relPath: String, status: SyncItemStatus) -> Unit,
    ) {
        val remoteResult = fetchRemoteConversationIndex(provider)
        // 损坏/未来版本：跳过下载（不碰云端，避免错误合并）；不存在：无可下载
        val remoteIndex = when (remoteResult) {
            is RemoteIndexResult.Valid -> remoteResult.index
            is RemoteIndexResult.Corrupt -> {
                logger("conversation sync: remote index corrupt, skip conversation download")
                return
            }

            is RemoteIndexResult.Absent -> return
        }
        val localEntries = access.listLocalIndexEntries()

        // 1) 应用云端会话删除（幂等）
        for (tombstone in remoteIndex.deleted) {
            runTracked(conversationItemPath(tombstone.id), onProgress) {
                access.deleteRemoteConversation(tombstone.id)
            }
            logger("conversation sync: deleted remote ${tombstone.id}")
        }

        // 2) 拉取云端比本地新的会话并合并
        for (entry in remoteIndex.activeEntries()) {
            val localSyncAt = localEntries.firstOrNull { it.id == entry.id }?.syncUpdatedAt ?: 0L
            // 本地已是该版本或更新 → 跳过（本地新由上传负责）
            if (entry.syncUpdatedAt <= localSyncAt) continue
            runTracked(conversationItemPath(entry.id), onProgress) {
                downloadAndMergeConversation(provider, access, entry.id)
            }
            // 限速：避免高频 WebDAV 请求触发限流
            delay(CONVERSATION_UPLOAD_INTERVAL_MS)
            logger("conversation sync: downloaded ${entry.id}")
        }
    }

    /** 只读：本地有、且云端 index 中版本不一致的会话（需要上传）。preview 与执行共用，保证一致性。 */
    private suspend fun conversationUploadDiff(
        remoteIndex: ConversationIndex,
        access: ConversationSyncAccess,
    ): List<ConversationIndexEntry> {
        return access.listLocalIndexEntries().filter { entry ->
            remoteIndex.entryById(entry.id)?.syncUpdatedAt != entry.syncUpdatedAt
        }
    }

    /** 云端 index 的读取结果：不存在（首次）/ 损坏（存在但解析失败）/ 有效。 */
    private sealed interface RemoteIndexResult {
        data object Absent : RemoteIndexResult
        data object Corrupt : RemoteIndexResult
        data class Valid(val index: ConversationIndex) : RemoteIndexResult
    }

    /**
     * 读云端 index.json。
     * 用 head 区分「不存在」（首次同步，允许上传）与「存在但损坏/未来版本」（跳过，绝不覆盖云端，
     * 否则会丢失云端墓碑与其他设备条目，破坏多端收敛）。
     */
    private suspend fun fetchRemoteConversationIndex(provider: SyncProvider): RemoteIndexResult {
        // head 失败（404 等）视为首次同步，云端还没有 index
        if (!provider.head(CONVERSATION_INDEX_PATH).isSuccess) {
            return RemoteIndexResult.Absent
        }
        val tmp = File(syncPendingDir, "conv-index.json")
        tmp.parentFile?.mkdirs()
        return try {
            provider.downloadToFile(CONVERSATION_INDEX_PATH, tmp).getOrThrow()
            val index = decodeConversationIndex(tmp.readBytes())
            if (index != null) RemoteIndexResult.Valid(index) else RemoteIndexResult.Corrupt
        } catch (e: Exception) {
            logger("conversation sync: remote index read failed: ${e.message}")
            RemoteIndexResult.Corrupt
        } finally {
            tmp.delete()
        }
    }

    /**
     * 只读检测本地与云端的差异，返回分组清单（不传输任何数据、不修改同步状态）。
     * 失败（网络/认证/远端列目录异常）时返回 null，调用方应区分「无法检测」与「无差异」。
     */
    suspend fun preview(provider: SyncProvider, config: SyncConfig): SyncPreview? = withContext(Dispatchers.IO) {
        try {
            val plan = computePlan(provider, config)
            val uploads = plan.items.filter { it.action == SyncDecision.Action.Push }
                .map { SyncPreviewItem(it.relPath, humanLabel(it.relPath), it.size) }
                .toMutableList()
            // 会话增量差异（上传方向）：本地新增/修改的会话文件
            if (config.includeConversations) {
                conversationAccess?.let { access ->
                    // 云端 index 损坏/未知格式时不做会话差异预览（避免误导用户"有/无差异"）
                    val remote = fetchRemoteConversationIndex(provider)
                    if (remote !is RemoteIndexResult.Corrupt) {
                        val remoteIndex = (remote as? RemoteIndexResult.Valid)?.index ?: ConversationIndex()
                        conversationUploadDiff(remoteIndex, access).forEach { entry ->
                            uploads += SyncPreviewItem(
                                relPath = conversationItemPath(entry.id),
                                label = entry.title.ifBlank { "(未命名会话)" },
                                size = access.conversationSize(entry.id) ?: 0L,
                            )
                        }
                    }
                }
            }
            SyncPreview(
                uploads = uploads,
                updates = plan.items.filter { it.action == SyncDecision.Action.Pull }
                    .map { SyncPreviewItem(it.relPath, humanLabel(it.relPath), it.size) },
                deletions = plan.items.filter { it.action == SyncDecision.Action.DeleteRemote }
                    .map { SyncPreviewItem(it.relPath, humanLabel(it.relPath), it.size) },
                conflicts = plan.items.filter { it.action == SyncDecision.Action.Conflict }
                    .map { SyncPreviewItem(it.relPath, humanLabel(it.relPath), it.size) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 限流错误向上抛出（调用方提示"稍后再试"），其余失败返回 null（无法检测）
            if (isThrottleError(e)) throw e
            logger("preview failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 单元收集
    // ------------------------------------------------------------------

    /**
     * 只读决策：确保同步根存在、收集本地单元、列远端、逐条走决策表，产出待执行计划。
     * 不执行任何上传/下载/删除，不写同步状态（唯一的磁盘写入是 DB 单元的
     * WAL checkpoint 与 db.meta.json 临时下载，均无同步状态副作用）。
     */
    private suspend fun computePlan(provider: SyncProvider, config: SyncConfig): ComputeResult {
        // 与执行一致：先确保同步根集合存在，否则 listRemote 在首次同步时 404
        provider.ensureBaseCollection().getOrThrow()

        val localSettings = settings.currentSettings()
        val settingsSha = sha256(settingsContent(localSettings))
        val localUnits = collectLocalUnits(config)
        val remoteByPath = provider.listRemote().getOrThrow().associateBy { it.relPath }

        // 远端 db.meta.json（存在时），供 DB 单元校验/冲突
        val remoteDbMeta = if (databaseEnabled(config)) fetchRemoteDbMeta(provider) else null

        // 循环外只读一次 syncedFiles 快照，避免每项重复读 DataStore
        val syncedFiles = stateStore.current().syncedFiles

        val items = mutableListOf<PlanItem>()
        // 只对启用的类型做决策：关闭的类型既不收集本地也不处理远端，
        // 否则关闭某类后本地不再收集会被误判为「本地已删 → 删除远端」
        val allPaths = (localUnits.keys + remoteByPath.keys)
            .filter { isPathEnabled(it, config) }
            .toSortedSet()
        for (relPath in allPaths) {
            if (relPath == DB_META_PATH) continue // db.meta.json 由 DB 单元统一管理
            val unit = localUnits[relPath]
            val remote = remoteByPath[relPath]
            val record = syncedFiles[relPath]

            val remoteTag = remote?.let { remoteTagOf(it) }

            val action = when (unit?.kind) {
                UnitKind.SETTINGS -> decideSettings(
                    localSettingsSha = settingsSha,
                    remote = remote,
                    remoteTag = remoteTag,
                    record = record,
                )

                UnitKind.DATABASE -> decideDatabase(
                    localSha = sha256OfUnit(unit),
                    remoteMeta = remoteDbMeta,
                    record = record,
                )

                else -> SyncDecision.decide(
                    SyncDecision.Input(
                        localExists = unit != null,
                        localSha = unit?.let { fastLocalShaOfUnit(it, record) },
                        localUpdatedAtMs = unit?.let { updatedAtMsOfUnit(it) } ?: 0L,
                        record = record,
                        remoteExists = remote != null,
                        remoteTag = remoteTag,
                        remoteLastModifiedMs = remote?.lastModifiedMs,
                    )
                )
            }

            if (action == SyncDecision.Action.Skip) continue

            val size = when (action) {
                SyncDecision.Action.Push -> when (unit?.kind) {
                    UnitKind.DATABASE -> dbAccess.dbFile().length()
                    else -> unit?.file?.length() ?: 0L
                }

                SyncDecision.Action.Pull -> remote?.size ?: 0L
                else -> 0L
            }

            items += PlanItem(relPath, action, unit, remote, remoteDbMeta, size)
        }

        return ComputeResult(items, localUnits.keys, remoteByPath.keys)
    }

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
        if (config.includeSettings) {
            // settings.json 白名单内容（密钥/凭据在 codec 层剔除）
            units["settings.json"] = SyncUnit("settings.json", UnitKind.SETTINGS)
        }
        if (databaseEnabled(config)) {
            units[DB_PATH] = SyncUnit(DB_PATH, UnitKind.DATABASE)
        }
        if (config.includeChatFiles) {
            collectFolderUnits(FileFolders.UPLOAD, units)
        }
        if (config.includeSkills) {
            collectFolderUnits(FileFolders.SKILLS, units)
        }
        if (config.includeFonts) {
            collectFolderUnits(FileFolders.FONTS, units)
        }
        return units
    }

    private fun collectFolderUnits(folder: String, units: MutableMap<String, SyncUnit>) {
        val dir = File(filesRoot, folder)
        walkFiles(dir, prefix = "$folder/").forEach { (rel, file) ->
            units[rel] = SyncUnit(rel, UnitKind.FILE, file)
        }
    }

    /** relPath 是否属于当前启用的同步类型；未知类型（不在同步边界内）一律不参与决策。 */
    private fun isPathEnabled(relPath: String, config: SyncConfig): Boolean = when {
        relPath == "settings.json" -> config.includeSettings
        relPath == DB_PATH || relPath == DB_META_PATH -> databaseEnabled(config)
        relPath.startsWith("upload/") -> config.includeChatFiles
        relPath.startsWith("skills/") -> config.includeSkills
        relPath.startsWith("fonts/") -> config.includeFonts
        else -> false
    }

    /**
     * 聊天增量同步开启且会话通道已注入（[conversationAccess] 非空）时，
     * 聊天数据走会话通道（conversations/），整库不再上传/下载，避免每次聊天变化都触发
     * 整库全量重传（224MB 级）。未启用会话通道（纯整库模式）时整库照常。
     * 关闭增量后自动恢复整库（回退）。
     */
    private fun databaseEnabled(config: SyncConfig): Boolean =
        config.includeDatabase && !(config.includeConversations && conversationAccess != null)

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

    /**
     * 快速本地 sha（借鉴 mtime+size 快检）：对 FILE 单元，若文件修改时间与大小和上次
     * 同步记录一致，说明内容未变，直接复用记录中的 sha256，避免重新读取文件内容算 hash。
     * 大文件/多文件时显著提速；mtime 变化或记录缺失时回退到完整 sha256（读文件），保证准确。
     */
    private fun fastLocalShaOfUnit(unit: SyncUnit, record: FileSyncRecord?): String? {
        if (unit.kind == UnitKind.FILE && record != null && record.localMtime != 0L) {
            val file = unit.file
            if (file != null &&
                file.lastModified() == record.localMtime &&
                file.length() == record.localSize
            ) {
                return record.sha256
            }
        }
        return sha256OfUnit(unit)
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

    /**
     * 执行单个计划项并上报进度。失败时先标记 FAILED 再向上抛出，
     * 保持「任一失败则整体失败」的既有语义（下次同步会自然补齐）。
     */
    private suspend fun runTracked(
        relPath: String,
        onProgress: (String, SyncItemStatus) -> Unit,
        block: suspend () -> Unit,
    ) {
        onProgress(relPath, SyncItemStatus.IN_PROGRESS)
        try {
            block()
            onProgress(relPath, SyncItemStatus.DONE)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(relPath, SyncItemStatus.FAILED)
            throw e
        }
    }

    private suspend fun performPush(
        provider: SyncProvider,
        config: SyncConfig,
        unit: SyncUnit,
        settingsBytes: ByteArray,
        deviceId: String,
        pushed: MutableList<String>,
    ) {
        when (unit.kind) {
            UnitKind.DATABASE -> {
                pushDatabase(provider, deviceId, pushed)
                return
            }

            UnitKind.SETTINGS -> {
                val result = provider.upload(unit.relPath, settingsBytes).getOrThrow()
                val remoteTag = result.etag ?: result.lastModifiedMs?.let { "lm:$it" } ?: ""
                updateRecord(
                    unit.relPath,
                    FileSyncRecord(
                        sha256 = sha256(settingsBytes),
                        remoteTag = remoteTag,
                        updatedAtMs = updatedAtMsOfUnit(unit),
                        deviceId = deviceId,
                    )
                )
            }

            UnitKind.FILE -> {
                val file = unit.file!!
                // 流式上传，避免大文件整读进内存
                val result = provider.uploadFile(unit.relPath, file).getOrThrow()
                val remoteTag = result.etag ?: result.lastModifiedMs?.let { "lm:$it" } ?: ""
                updateRecord(
                    unit.relPath,
                    FileSyncRecord(
                        sha256 = sha256(file),
                        remoteTag = remoteTag,
                        updatedAtMs = updatedAtMsOfUnit(unit),
                        deviceId = deviceId,
                        localSize = file.length(),
                        localMtime = file.lastModified(),
                    )
                )
            }
        }
        // 并发上传时 pushed 会被多个协程写入，需同步
        synchronized(pushed) { pushed += unit.relPath }
    }

    /** checkpoint 后流式上传单文件 DB，并写 db.meta.json（sha256/timestamp/deviceId/size）。 */
    private suspend fun pushDatabase(
        provider: SyncProvider,
        deviceId: String,
        pushed: MutableList<String>,
    ) {
        dbAccess.checkpoint()
        val dbFile = dbAccess.dbFile()
        val dbSha = sha256(dbFile)
        provider.uploadFile(DB_PATH, dbFile).getOrThrow()
        provider.upload(
            DB_META_PATH,
            JsonInstant.encodeToString(
                DbMeta(
                    sha256 = dbSha,
                    timestamp = System.currentTimeMillis(),
                    deviceId = deviceId,
                    size = dbFile.length(),
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
        synchronized(pushed) { pushed += DB_PATH }
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
                localSize = localTarget.length(),
                localMtime = localTarget.lastModified(),
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
        val target = when {
            relPath.startsWith("upload/") -> File(filesRoot, relPath)
            relPath.startsWith("skills/") -> File(filesRoot, relPath)
            relPath.startsWith("fonts/") -> File(filesRoot, relPath)
            else -> File(filesRoot, relPath.substringAfterLast('/'))
        }
        // 防御：远端 relPath 可能含 ../（异常/恶意 WebDAV 服务器），规范化后确保仍位于 filesRoot 内
        val root = filesRoot.canonicalFile
        val normalized = target.canonicalFile
        return if (normalized == root || normalized.path.startsWith(root.path + File.separator)) {
            target
        } else {
            File(filesRoot, target.name)
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
