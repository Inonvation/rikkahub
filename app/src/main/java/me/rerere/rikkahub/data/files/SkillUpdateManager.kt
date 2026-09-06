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

/**
 * 技能更新管理器：来源注册表 + 手动/自动更新。
 *
 * 检测模式（对标 git-based 工具的 SHA 对比做法）：
 * 1. GitHub 导入成功后登记来源（repo/branch/path + 安装时影响该路径的最新 commit + ETag + 内容指纹）；
 * 2. 检查时查 commits API（带 If-None-Match 条件请求，304 无变化且不消耗配额），对比 SHA；
 * 3. 应用更新 = 重跑导入（递归列目录 + 下载 + 原子替换），内容无变化时只刷新 SHA 消除徽标。
 *
 * 安全护栏：
 * - 磁盘内容指纹 != 安装指纹 视为「本地已修改」，自动更新跳过（防静默覆盖用户编辑），
 *   手动更新需用户确认覆盖；
 * - 自动检查按 lastCheckedAt 节流（未认证 GitHub API 配额 60 次/小时/IP）；
 * - 所有注册表读写经 [mutex] 串行化，启动检查 / WorkManager / 页面入口并发安全。
 */
class SkillUpdateManager(
    private val skillManager: SkillManager,
    private val client: GitHubSkillClient,
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

        data class UpdateAvailable(val remoteSha: String) : CheckResult()

        data class Failed(val reason: String) : CheckResult()
    }

    sealed class ApplyResult {
        /** 已下载并替换本地文件 */
        data object Updated : ApplyResult()

        /** 远端内容与本地一致（可能只是仓库其他路径有新提交），只刷新了记录 */
        data object NoChange : ApplyResult()

        /** 本地已修改且未确认覆盖，跳过（手动更新路径会转确认弹窗） */
        data object SkippedLocalModified : ApplyResult()

        data class Failed(val reason: String) : ApplyResult()
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
     * 命中更新且开启了自动更新、本地未修改的技能直接后台应用。
     */
    suspend fun checkAll(force: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked()
            for (name in _sources.value.keys.toList()) {
                val result = runCatching { checkLocked(name, force) }.getOrElse { e ->
                    Log.w(TAG, "checkAll: check $name failed", e)
                    null
                }
                if (result is CheckResult.UpdateAvailable) {
                    val source = _sources.value[name] ?: continue
                    if (source.autoUpdate && !source.localModified) {
                        val applied = runCatching { applyUpdateLocked(name, overwriteLocal = false) }
                            .getOrElse { e ->
                                Log.w(TAG, "checkAll: auto apply $name failed", e)
                                null
                            }
                        when (applied) {
                            null -> Log.w(TAG, "auto update $name crashed")
                            is ApplyResult.Failed -> Log.w(TAG, "auto update $name failed: ${applied.reason}")
                            is ApplyResult.SkippedLocalModified -> Log.i(TAG, "auto update $name skipped: local modified")
                            else -> Log.i(TAG, "auto updated skill: $name")
                        }
                    }
                }
            }
        }
    }

    /** 手动应用更新。[overwriteLocal] 仅在用户确认覆盖本地修改后为 true。 */
    suspend fun applyUpdate(skillName: String, overwriteLocal: Boolean): ApplyResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { applyUpdateLocked(skillName, overwriteLocal) }
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
                    )
                }
                if (changed) CheckResult.UpdateAvailable(remoteSha) else CheckResult.UpToDate
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

    private fun applyUpdateLocked(skillName: String, overwriteLocal: Boolean): ApplyResult {
        val source = _sources.value[skillName] ?: return ApplyResult.Failed("未找到技能来源")
        val skillDir = skillManager.getSkillDir(skillName)
            ?: return ApplyResult.Failed("技能不存在")
        val diskHash = SkillContentHash.computeDirHash(skillDir)
        if (isLocalModified(source, diskHash) && !overwriteLocal) {
            // 顺手刷新 localModified，让 UI 能提示「本地已修改」
            mutateLocked(skillName) { it?.copy(localModified = true) }
            return ApplyResult.SkippedLocalModified
        }

        val info = GitHubSkillClient.GitHubRepoInfo(source.repoOwner, source.repoName, source.branch, source.path)
        // 先取 SHA 再下载：若远端在下载窗口内又前进，下次检查会再次提示（收敛不漏报）
        val head = client.getPathCommitHead(info, etag = null)
        val filesBytes = when (val fetched = client.fetchSkillFiles(info)) {
            is GitHubSkillClient.FetchResult.Success -> fetched.files
            is GitHubSkillClient.FetchResult.Failed -> return ApplyResult.Failed(fetched.reason)
        }
        val newHash = SkillContentHash.computeFilesHash(filesBytes)

        if (diskHash != null && newHash == diskHash) {
            // 内容实际一致：只刷新 SHA/ETag 记录，不重写文件
            mutateLocked(skillName) {
                it?.copy(
                    commitSha = (head as? GitHubSkillClient.CommitCheck.Head)?.sha ?: it.commitSha,
                    etag = (head as? GitHubSkillClient.CommitCheck.Head)?.etag ?: it.etag,
                    contentHash = newHash,
                    lastCheckedAt = System.currentTimeMillis(),
                    localModified = false,
                    updateAvailable = false,
                    remoteSha = null,
                )
            }
            return ApplyResult.NoChange
        }

        val saved = skillManager.saveSkillFileBytesAtomically(skillName, filesBytes)
        if (!saved) return ApplyResult.Failed("保存失败")
        mutateLocked(skillName) { existing ->
            SkillSource(
                skillName = skillName,
                repoOwner = source.repoOwner,
                repoName = source.repoName,
                branch = source.branch,
                path = source.path,
                commitSha = (head as? GitHubSkillClient.CommitCheck.Head)?.sha ?: source.commitSha,
                etag = (head as? GitHubSkillClient.CommitCheck.Head)?.etag,
                contentHash = newHash,
                autoUpdate = source.autoUpdate,
                localModified = false,
                updateAvailable = false,
                remoteSha = null,
                installedAt = source.installedAt,
                lastCheckedAt = System.currentTimeMillis(),
            )
        }
        return ApplyResult.Updated
    }

    private fun detectLocalModified(source: SkillSource): Boolean {
        if (source.contentHash.isBlank()) return false
        val dir = skillManager.getSkillDir(source.skillName) ?: return false
        val diskHash = SkillContentHash.computeDirHash(dir) ?: return false
        return diskHash != source.contentHash
    }

    private fun isLocalModified(source: SkillSource, diskHash: String?): Boolean {
        if (source.contentHash.isBlank() || diskHash == null) return false
        return diskHash != source.contentHash
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
