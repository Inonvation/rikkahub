package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 技能更新管理器：来源注册表 + 手动/自动更新（增量）。
 *
 * 检测模式（对标 git-based 工具的 SHA 对比做法）：
 * 1. GitHub 导入成功后登记来源（repo/branch/path + 安装时影响该路径的最新 commit + ETag + 内容指纹
 *    + 每文件 git blob SHA 清单）；
 * 2. 检查时查 commits API（带 If-None-Match 条件请求，304 无变化且不消耗配额），对比 SHA；
 * 3. 应用更新走增量流程：列树（1 个请求，带每文件 blob SHA）→ 与清单比对得变更集 → 只下载有变化的
 *    文件 → staging 组装（未变化文件与用户新增文件本地保留）→ 原子替换 → 刷新清单。
 *
 * 自动更新为两级开关，且均默认关闭：
 * - 全局总闸 settings.skillAutoUpdateEnabled（默认关）：关时只检测提示，绝不自动应用；
 * - 按技能开关 SkillSource.autoUpdate：总闸开启后，仅对显式开启的技能自动应用。
 *
 * 安全护栏：
 * - 「本地已修改」细化为清单内 tracked 文件被改动/删除（用户新增文件不算，自动更新放行且保留）；
 *   自动更新遇到会覆盖用户改动的文件时跳过，手动更新在预览弹窗中明示；
 * - 自动检查按 lastCheckedAt 节流（未认证 GitHub API 配额 60 次/小时/IP）；
 * - 所有注册表读写经 [mutex] 串行化，启动检查 / WorkManager / 页面入口并发安全。
 */
class SkillUpdateManager(
    private val skillManager: SkillManager,
    private val client: GitHubSkillClient,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillUpdateManager"

        /** 自动检查最小间隔：12h。手动检查（force）不受限。 */
        const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }

    private val mutex = Mutex()

    private val registry: SkillSourceRegistry
    private val _sources = MutableStateFlow<Map<String, SkillSource>>(emptyMap())

    /** 注册表快照（skillName -> 来源），UI 直接收集 */
    val sources: StateFlow<Map<String, SkillSource>> = _sources.asStateFlow()

    init {
        registry = SkillSourceRegistry(
            skillManager.getSkillsDir().resolve(SkillSourceRegistry.REGISTRY_FILE_NAME),
        )
        _sources.value = registry.load()
    }

    sealed class CheckResult {
        data object UpToDate : CheckResult()

        data class UpdateAvailable(
            val remoteSha: String,
            val commitMessage: String? = null,
            val commitTime: Long? = null,
        ) : CheckResult()

        data class Failed(val reason: String) : CheckResult()
    }

    sealed class ApplyResult {
        /** 已下载并替换本地文件 */
        data object Updated : ApplyResult()

        /** 远端内容与本地一致（可能只是仓库其他路径有新提交），只刷新了记录 */
        data object NoChange : ApplyResult()

        data class Failed(val reason: String) : ApplyResult()
    }

    /**
     * 增量更新准备结果：下载完成、待用户确认后 [applyPrepared] 落盘。
     * 持有下载好的新文件内容，确认前不要长期持有引用。
     */
    class PreparedUpdate internal constructor(
        val skillName: String,
        /** 远端新增文件（相对路径） */
        val added: List<String>,
        /** 远端修改文件（相对路径） */
        val modified: List<String>,
        /** 远端删除文件（相对路径） */
        val removed: List<String>,
        /** 其中同时被本地改过的文件 —— 应用后会覆盖用户的修改（预览弹窗须明示） */
        val overwriteFiles: List<String>,
        val commitMessage: String?,
        val commitTime: Long?,
        internal val toWrite: Map<String, ByteArray>,
        internal val toCopy: Set<String>,
        internal val newManifest: Map<String, String>,
        internal val head: GitHubSkillClient.CommitCheck.Head?,
        internal val etag: String?,
    ) {
        val changeCount: Int get() = added.size + modified.size + removed.size
    }

    sealed class PrepareResult {
        data class Prepared(val update: PreparedUpdate) : PrepareResult()

        /** 远端内容与本地一致：注册表已顺手刷新 */
        data object NoChange : PrepareResult()

        data class Failed(val reason: String) : PrepareResult()
    }

    /** GitHub 导入成功后登记来源。保留已有的 autoUpdate 开关；SHA 查询失败记空串（下次检查自愈）。 */
    suspend fun recordInstall(
        skillName: String,
        info: GitHubSkillClient.GitHubRepoInfo,
        files: Map<String, ByteArray>,
    ): Unit = withContext(Dispatchers.IO) {
        val head = runCatching { client.getPathCommitHead(info, etag = null) }.getOrNull()
        mutex.withLock {
            mutateLocked(skillName) { existing ->
                SkillSource(
                    skillName = skillName,
                    repoOwner = info.owner,
                    repoName = info.repo,
                    branch = info.branch,
                    path = info.path,
                    commitSha = (head as? GitHubSkillClient.CommitCheck.Head)?.sha.orEmpty(),
                    etag = (head as? GitHubSkillClient.CommitCheck.Head)?.etag,
                    contentHash = SkillContentHash.computeFilesHash(files),
                fileHashes = SkillContentHash.computeFilesBlobShas(files),
                    autoUpdate = existing?.autoUpdate ?: false,
                    localModified = false,
                    updateAvailable = false,
                    remoteSha = null,
                    installedAt = existing?.installedAt ?: System.currentTimeMillis(),
                    // 安装时已拿到最新 commit，视为刚检查过，节流窗口内不再打请求
                    lastCheckedAt = if (head is GitHubSkillClient.CommitCheck.Head) {
                        System.currentTimeMillis()
                    } else {
                        0
                    },
                )
            }
        }
    }

    /** 检查单个技能。force=true 跳过节流（用户显式触发）。 */
    suspend fun checkForUpdate(skillName: String, force: Boolean): CheckResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { checkLocked(skillName, force) }
        }

    /**
     * 批量检查（App 冷启动 / WorkManager / 技能页打开）。带节流与缺失清理；
     * 命中更新且开启了自动更新、本地 tracked 文件未被改动的技能直接后台增量应用。
     */
    suspend fun checkAll(force: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked()
            // 总闸默认关：关时只检测亮徽标，绝不自动应用（per-skill 开关仅在总闸开启后生效）
            val autoUpdateGloballyEnabled = settingsStore.settingsFlow.value.skillAutoUpdateEnabled
            for (name in _sources.value.keys.toList()) {
                val result = runCatching { checkLocked(name, force) }.getOrElse { e ->
                    Log.w(TAG, "checkAll: check $name failed", e)
                    null
                }
                if (result is CheckResult.UpdateAvailable && autoUpdateGloballyEnabled) {
                    val source = _sources.value[name] ?: continue
                    if (source.autoUpdate && !source.localModified) {
                        val prepared = runCatching { prepareUpdateLocked(name, onProgress = null) }
                            .getOrElse { e ->
                                Log.w(TAG, "checkAll: prepare $name failed", e)
                                null
                            }
                        when (prepared) {
                            null -> Log.w(TAG, "auto update $name crashed")
                            is PrepareResult.Failed ->
                                Log.w(TAG, "auto update $name failed: ${prepared.reason}")
                            is PrepareResult.NoChange -> Log.i(TAG, "auto update $name: no change")
                            is PrepareResult.Prepared ->
                                if (prepared.update.overwriteFiles.isEmpty()) {
                                    when (val applied = runCatching { applyPreparedLocked(prepared.update) }
                                        .getOrElse { e ->
                                            Log.w(TAG, "checkAll: auto apply $name failed", e)
                                            null
                                        }) {
                                        null -> Log.w(TAG, "auto update $name crashed")
                                        is ApplyResult.Failed ->
                                            Log.w(TAG, "auto update $name failed: ${applied.reason}")
                                        else -> Log.i(TAG, "auto updated skill: $name")
                                    }
                                } else {
                                    // 准备阶段发现远端改动与用户本地改动重叠：守住不自动覆盖
                                    Log.i(TAG, "auto update $name skipped: would overwrite local changes")
                                }
                        }
                    }
                }
            }
        }
    }

    /**
     * 增量更新准备（手动更新路径第一步）：列树比对清单 → 只下载变更文件 → 返回变更预览数据。
     * 无实质变化时刷新注册表并返回 [PrepareResult.NoChange]。不落盘。
     */
    suspend fun prepareUpdate(
        skillName: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): PrepareResult = withContext(Dispatchers.IO) {
        mutex.withLock { prepareUpdateLocked(skillName, onProgress) }
    }

    /** 应用已准备好的更新（用户在预览弹窗确认后调用）。 */
    suspend fun applyPrepared(prepared: PreparedUpdate): ApplyResult = withContext(Dispatchers.IO) {
        mutex.withLock { applyPreparedLocked(prepared) }
    }

    /** 计算指定技能本地改动的 tracked 文件清单（详情页展示用）。无来源/无清单时返回空列表。 */
    fun localModifiedFiles(skillName: String): List<String> {
        val source = _sources.value[skillName] ?: return emptyList()
        if (source.fileHashes.isEmpty()) return emptyList()
        val dir = skillManager.getSkillDir(skillName) ?: return emptyList()
        val disk = SkillContentHash.computeDirBlobShas(dir) ?: return emptyList()
        return SkillUpdateDiff.computeLocalState(source.fileHashes, disk).modifiedTracked
    }

    suspend fun setAutoUpdate(skillName: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_sources.value[skillName] == null) return@withLock
            mutateLocked(skillName) { it?.copy(autoUpdate = enabled) }
        }
    }

    suspend fun removeSource(skillName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutateLockedOrNull(skillName) { null }
        }
    }

    // ---- 内部实现（须持锁调用） ----

    private fun checkLocked(skillName: String, force: Boolean): CheckResult {
        val source = _sources.value[skillName]
            ?: return CheckResult.Failed("未找到技能来源")
        val now = System.currentTimeMillis()
        if (!force && source.lastCheckedAt > 0 && now - source.lastCheckedAt < AUTO_CHECK_INTERVAL_MS) {
            return if (source.updateAvailable) {
                CheckResult.UpdateAvailable(source.remoteSha.orEmpty())
            } else {
                CheckResult.UpToDate
            }
        }

        val info = GitHubSkillClient.GitHubRepoInfo(source.repoOwner, source.repoName, source.branch, source.path)
        return when (val head = client.getPathCommitHead(info, source.etag)) {
            is GitHubSkillClient.CommitCheck.NotModified -> {
                // 条件请求 304：内容无变化，刷新检查时间即可
                mutateLocked(skillName) {
                    it?.copy(lastCheckedAt = now, localModified = detectLocalModified(source))
                }
                CheckResult.UpToDate
            }

            is GitHubSkillClient.CommitCheck.Head -> {
                val remoteSha = head.sha
                val changed = remoteSha != source.commitSha
                mutateLocked(skillName) {
                    it?.copy(
                        etag = head.etag ?: it.etag,
                        lastCheckedAt = now,
                        localModified = detectLocalModified(source),
                        updateAvailable = changed,
                        remoteSha = if (changed) remoteSha else null,
                        remoteCommitMessage = if (changed) head.message else null,
                        remoteCommitTime = if (changed) head.timeEpochMs else null,
                    )
                }
                if (changed) {
                    CheckResult.UpdateAvailable(remoteSha, head.message, head.timeEpochMs)
                } else {
                    CheckResult.UpToDate
                }
            }

            is GitHubSkillClient.CommitCheck.Failed -> {
                // 404（仓库没了）/403（限流）都推进检查时间，避免每次启动反复打失败请求
                if (head.code == 403 || head.code == 404) {
                    mutateLocked(skillName) { it?.copy(lastCheckedAt = now) }
                }
                CheckResult.Failed(head.reason)
            }
        }
    }

    /**
     * 增量准备（须持锁调用）：列树（1 个请求，含每文件 blob SHA）→ 与安装清单比对得变更集 →
     * 只下载 Added/Modified 文件 → 组装 staging 写入计划。旧记录无清单（fileHashes 空）时
     * 全部远端文件视为变更，自然退化为全量下载。
     */
    private suspend fun prepareUpdateLocked(
        skillName: String,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ): PrepareResult {
        val source = _sources.value[skillName]
            ?: return PrepareResult.Failed("未找到技能来源")
        val skillDir = skillManager.getSkillDir(skillName)
            ?: return PrepareResult.Failed("技能不存在")

        val info = GitHubSkillClient.GitHubRepoInfo(source.repoOwner, source.repoName, source.branch, source.path)
        // 先取 SHA 再列树/下载：若远端在窗口内又前进，下次检查会再次提示（收敛不漏报）
        val headInfo = when (val head = client.getPathCommitHead(info, etag = null)) {
            is GitHubSkillClient.CommitCheck.Head -> head
            is GitHubSkillClient.CommitCheck.Failed -> return PrepareResult.Failed(head.reason)
            GitHubSkillClient.CommitCheck.NotModified -> null
        }

        val remote = when (val listed = client.listTreeWithBlobs(info)) {
            is GitHubSkillClient.TreeResult.Success -> listed.blobs
            is GitHubSkillClient.TreeResult.Failed -> return PrepareResult.Failed(listed.reason)
        }

        val manifest = source.fileHashes
        // 旧记录无 per-file 清单（安装早于清单特性）：以本地磁盘为差异基线（磁盘 blob SHA
        // 与 trees API 同源可比），仅与远端不一致的文件进入下载集，不再退化为全量下载。
        val legacyDiskShas = if (manifest.isEmpty()) {
            SkillContentHash.computeDirBlobShas(skillDir).orEmpty()
        } else null
        val changes = if (legacyDiskShas != null) {
            SkillUpdateDiff.computeChangeSetFromDisk(legacyDiskShas, remote)
        } else {
            SkillUpdateDiff.computeChangeSet(manifest, remote)
        }
        if (changes.isEmpty()) {
            // 内容实际一致：只刷新 SHA/ETag 记录，不重写文件；旧记录顺带补上清单，
            // 此后走精确增量比对与本地改动检测
            mutateLocked(skillName) {
                it?.copy(
                    commitSha = headInfo?.sha ?: it.commitSha,
                    etag = headInfo?.etag ?: it.etag,
                    fileHashes = if (manifest.isEmpty()) remote else it.fileHashes,
                    lastCheckedAt = System.currentTimeMillis(),
                    updateAvailable = false,
                    remoteSha = null,
                    remoteCommitMessage = null,
                    remoteCommitTime = null,
                )
            }
            return PrepareResult.NoChange
        }

        // 本地磁盘逐文件 blob SHA：判断哪些变更文件会覆盖用户改动 + 哪些本地文件要保留
        val diskShas = legacyDiskShas ?: SkillContentHash.computeDirBlobShas(skillDir).orEmpty()
        val localState = SkillUpdateDiff.computeLocalState(manifest, diskShas)

        val toWritePaths = changes.filterNot { it is FileChange.Removed }.map { it.path }
        val absPrefix = if (info.path.isBlank()) "" else "${info.path}/"
        val filesBytes = when (val fetched = client.downloadFilesByAbsPath(
            info,
            toWritePaths.map { absPrefix + it },
            onProgress ?: { _, _ -> },
        )) {
            is GitHubSkillClient.FetchResult.Success -> fetched.files.mapKeys { it.key.removePrefix(absPrefix) }
            is GitHubSkillClient.FetchResult.Failed -> return PrepareResult.Failed(fetched.reason)
        }

        // 新清单 = 本次列树的远端全量（含变更与未变化文件；blob SHA 来自 API，与下载内容同源）
        val newManifest = remote
        if ("SKILL.md" !in newManifest) {
            return PrepareResult.Failed("远端目录中缺少 SKILL.md，拒绝应用更新")
        }

        // staging 保留计划：本地文件除「远端已删除的 tracked 文件」外全部保留
        //（tracked 未变化 + 用户新增）；与写入集合取差集避免重复
        val removedPaths = changes.filterIsInstance<FileChange.Removed>().map { it.path }.toSet()
        val toCopy = (diskShas.keys - toWritePaths.toSet() - removedPaths).toSet()

        // 会覆盖用户改动的文件 = 远端改了 且 本地 tracked 也被用户改了
        val locallyModifiedSet = localState.modifiedTracked.toSet()
        val overwriteFiles = changes
            .filterIsInstance<FileChange.Modified>()
            .map { it.path }
            .filter { it in locallyModifiedSet }

        return PrepareResult.Prepared(
            PreparedUpdate(
                skillName = skillName,
                added = changes.filterIsInstance<FileChange.Added>().map { it.path },
                modified = changes.filterIsInstance<FileChange.Modified>().map { it.path },
                removed = removedPaths.toList(),
                overwriteFiles = overwriteFiles,
                commitMessage = headInfo?.message,
                commitTime = headInfo?.timeEpochMs,
                toWrite = filesBytes,
                toCopy = toCopy,
                newManifest = newManifest,
                head = headInfo,
                etag = headInfo?.etag,
            )
        )
    }

    /** 落盘已准备好的增量更新（须持锁调用）：staging 原子替换 + 刷新注册表清单。 */
    private fun applyPreparedLocked(prepared: PreparedUpdate): ApplyResult {
        val skillDir = skillManager.getSkillDir(prepared.skillName)
            ?: return ApplyResult.Failed("技能不存在")

        val saved = skillManager.applySkillFilesUpdate(prepared.skillName, prepared.toWrite, prepared.toCopy)
        if (!saved) return ApplyResult.Failed("保存失败")

        // 落盘后重算整目录 sha256 指纹，保持 contentHash 快速路径与磁盘一致
        val diskHash = SkillContentHash.computeDirHash(skillDir)
        val source = _sources.value[prepared.skillName]
        mutateLocked(prepared.skillName) {
            SkillSource(
                skillName = prepared.skillName,
                repoOwner = source?.repoOwner ?: "",
                repoName = source?.repoName ?: "",
                branch = source?.branch ?: "",
                path = source?.path ?: "",
                commitSha = prepared.head?.sha ?: source?.commitSha ?: "",
                etag = prepared.head?.etag ?: prepared.etag,
                contentHash = diskHash ?: source?.contentHash ?: "",
                fileHashes = prepared.newManifest,
                autoUpdate = source?.autoUpdate ?: false,
                localModified = false,
                updateAvailable = false,
                remoteSha = null,
                remoteCommitMessage = null,
                remoteCommitTime = null,
                installedAt = source?.installedAt ?: 0,
                lastCheckedAt = System.currentTimeMillis(),
            )
        }
        return ApplyResult.Updated
    }

    /**
     * 本地是否被修改。两层判断：整目录 sha256 一致 → 未修改（快路径）；
     * 不一致时若存在 blob SHA 清单则细分为 tracked 改动（用户新增文件不算修改），
     * 无清单（旧记录）时退化为旧的整目录语义。
     */
    private fun detectLocalModified(source: SkillSource): Boolean {
        if (source.contentHash.isBlank()) return false
        val dir = skillManager.getSkillDir(source.skillName) ?: return false
        val diskHash = SkillContentHash.computeDirHash(dir) ?: return false
        if (diskHash == source.contentHash) return false
        if (source.fileHashes.isEmpty()) return true
        val diskShas = SkillContentHash.computeDirBlobShas(dir) ?: return true
        return SkillUpdateDiff.computeLocalState(source.fileHashes, diskShas).hasModifiedTracked
    }

    /** 清理已不存在的注册表条目（App 外直接删除技能目录 / SKILL.md 的场景）。 */
    private fun pruneMissingLocked() {
        // getSkillDir 只做路径解析不检查存在性，必须对照实际技能清单
        val existing = skillManager.listSkills().mapTo(HashSet()) { it.name }
        val stale = _sources.value.keys.filter { it !in existing }
        for (name in stale) {
            Log.i(TAG, "prune stale skill source: $name")
            mutateLockedOrNull(name) { null }
        }
    }

    /** 变更单条注册表项并写盘 + 发射。[transform] 返回 null 表示删除该条。 */
    private fun mutateLockedOrNull(skillName: String, transform: (SkillSource?) -> SkillSource?) {
        val map = _sources.value.toMutableMap()
        val result = transform(map[skillName])
        if (result == null) {
            map.remove(skillName)
        } else {
            map[skillName] = result
        }
        runCatching { registry.save(map) }
            .onFailure { e -> Log.w(TAG, "registry save failed", e) }
        _sources.value = map
    }

    private fun mutateLocked(skillName: String, transform: (SkillSource?) -> SkillSource?) {
        mutateLockedOrNull(skillName, transform)
    }
}
