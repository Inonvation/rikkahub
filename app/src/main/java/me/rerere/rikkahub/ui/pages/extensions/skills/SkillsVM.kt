package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.GitHubSkillClient
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillSource
import me.rerere.rikkahub.data.files.SkillUpdateManager

class SkillsVM(
    private val skillManager: SkillManager,
    private val gitHubSkillClient: GitHubSkillClient,
    private val skillUpdateManager: SkillUpdateManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
        private const val TAG = "SkillsVM"

        /** 下载进度发射节流间隔：并发完成回调很密，高频写入会引发对话框内容高频重组合 */
        private const val PROGRESS_EMIT_INTERVAL_MS = 150L
    }

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    /** 技能来源注册表（skillName -> 来源），更新徽标 / 更新菜单的数据源 */
    val skillSources: StateFlow<Map<String, SkillSource>> = skillUpdateManager.sources

    /** 自动更新全局总闸（默认关）：关时仅检测提示，per-skill 自动更新不生效 */
    val autoUpdateGloballyEnabled: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { it.skillAutoUpdateEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 正在检查/应用更新的技能名集合，UI 用于禁用重复操作 */
    private val _busySkills = MutableStateFlow<Set<String>>(emptySet())
    val busySkills = _busySkills.asStateFlow()

    /** 已准备好的增量更新（预览弹窗数据源），null 表示无进行中的更新确认 */
    private val _pendingPrepared = MutableStateFlow<SkillUpdateManager.PreparedUpdate?>(null)
    val pendingPrepared = _pendingPrepared.asStateFlow()

    /** 更新相关进度文案（增量下载/批量更新），null 表示无进行中的更新任务 */
    private val _updateProgress = MutableStateFlow<String?>(null)
    val updateProgress = _updateProgress.asStateFlow()

    /** 批量更新进行中标记（进度弹窗展示依据） */
    private val _batchUpdating = MutableStateFlow(false)
    val batchUpdating = _batchUpdating.asStateFlow()

    /** 全量检查进行中标记（顶栏按钮转圈） */
    private val _checkingAll = MutableStateFlow(false)
    val checkingAll = _checkingAll.asStateFlow()

    /** 批量更新取消标记 */
    @Volatile
    private var batchCancelled = false

    /** GitHub 导入进度文案（"正在下载 x/y"），null 表示无进行中的导入；对话框实时展示 */
    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress = _importProgress.asStateFlow()

    /** 进行中的 GitHub 导入任务；用户取消对话框时终止（在途 HTTP 请求返回后生效，最迟约一个超时周期） */
    private var importJob: Job? = null

    /** 进度节流用的上次发射时间；多线程写仅为节流，精度无所谓 */
    @Volatile
    private var lastProgressEmit = 0L

    init {
        loadSkills()
        // 打开页面顺带节流检查（12h 内已查过则直接复用上次结果）；
        // 若自动更新在后台应用了新内容，重载一次列表让描述等元数据与磁盘同步
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { skillUpdateManager.checkAll(force = false) }
                .onSuccess { _skills.value = skillManager.listSkills() }
        }
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            skillUpdateManager.removeSource(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun deleteSkills(names: List<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            names.forEach { name ->
                skillManager.deleteSkill(name)
                skillUpdateManager.removeSource(name)
            }
            _skills.value = skillManager.listSkills()
            persistOrder()
        }
    }

    /** 手动检查单个技能更新（force，跳过节流）。 */
    fun checkForUpdate(name: String, onResult: (SkillUpdateManager.CheckResult) -> Unit) {
        setBusy(name, true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { skillUpdateManager.checkForUpdate(name, force = true) }
                .getOrElse { SkillUpdateManager.CheckResult.Failed(it.message ?: "unknown") }
            setBusy(name, false)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** 顶栏「检查全部」：force 批量检查全部来源并刷新列表。 */
    fun checkAllNow() {
        if (_checkingAll.value) return
        _checkingAll.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { skillUpdateManager.checkAll(force = true) }
            _skills.value = skillManager.listSkills()
            _checkingAll.value = false
        }
    }

    /** 批量检查选中的技能（force）。回调返回有更新数与失败数。 */
    fun checkSkills(names: List<String>, onResult: (available: Int, failed: Int) -> Unit) {
        if (names.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var available = 0
            var failed = 0
            for (name in names) {
                setBusy(name, true)
                val result = runCatching { skillUpdateManager.checkForUpdate(name, force = true) }
                    .getOrElse { SkillUpdateManager.CheckResult.Failed(it.message ?: "unknown") }
                setBusy(name, false)
                when (result) {
                    is SkillUpdateManager.CheckResult.UpdateAvailable -> available++
                    is SkillUpdateManager.CheckResult.Failed -> failed++
                    else -> {}
                }
            }
            withContext(Dispatchers.Main) { onResult(available, failed) }
        }
    }

    /**
     * 手动更新第一步：增量准备（列树比对 + 只下载变更文件），完成后弹出预览。
     * 无实质变化时回调 NoChange（注册表已刷新、徽标消除）。
     */
    fun prepareUpdate(name: String, onResult: (SkillUpdateManager.PrepareResult) -> Unit) {
        setBusy(name, true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                skillUpdateManager.prepareUpdate(name) { done, total ->
                    throttledProgressEmit("正在获取变更 $done/$total")
                }
            }.getOrElse {
                Log.w(TAG, "prepareUpdate failed: $name", it)
                SkillUpdateManager.PrepareResult.Failed(it.message ?: "unknown")
            }
            _updateProgress.value = null
            setBusy(name, false)
            _skills.value = skillManager.listSkills()
            if (result is SkillUpdateManager.PrepareResult.Prepared) {
                _pendingPrepared.value = result.update
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** 预览确认后应用增量更新。 */
    fun applyPrepared(onResult: (SkillUpdateManager.ApplyResult) -> Unit) {
        val prepared = _pendingPrepared.value ?: return
        val name = prepared.skillName
        setBusy(name, true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { skillUpdateManager.applyPrepared(prepared) }
                .getOrElse { SkillUpdateManager.ApplyResult.Failed(it.message ?: "unknown") }
            _skills.value = skillManager.listSkills()
            setBusy(name, false)
            _pendingPrepared.value = null
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** 取消预览（释放已下载内容，不落盘）。 */
    fun dismissPrepared() {
        _pendingPrepared.value = null
    }

    /**
     * 批量更新：对 updateAvailable 且本地未修改的技能逐个「准备→应用」（无需逐个预览，
     * 未改动过的技能应用远端内容没有覆盖风险）；本地已修改的准备阶段发现覆盖冲突时跳过。
     */
    fun updateSkills(names: List<String>, onResult: (updated: Int, skipped: Int, failed: Int) -> Unit) {
        if (names.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            batchCancelled = false
            _batchUpdating.value = true
            var updated = 0
            var skipped = 0
            var failed = 0
            val targets = names.filter { skillUpdateManager.sources.value[it]?.updateAvailable == true }
            for ((index, name) in targets.withIndex()) {
                if (batchCancelled) break
                _updateProgress.value = "正在更新 $name（${index + 1}/${targets.size}）"
                val source = skillUpdateManager.sources.value[name]
                if (source == null || source.localModified) {
                    skipped++
                    continue
                }
                setBusy(name, true)
                val prepared = runCatching { skillUpdateManager.prepareUpdate(name) }
                    .getOrElse {
                        Log.w(TAG, "updateSkills: prepare $name failed", it)
                        null
                    }
                when (prepared) {
                    is SkillUpdateManager.PrepareResult.Prepared ->
                        if (prepared.update.overwriteFiles.isEmpty()) {
                            when (runCatching { skillUpdateManager.applyPrepared(prepared.update) }
                                .getOrElse {
                                    Log.w(TAG, "updateSkills: apply $name failed", it)
                                    null
                                }) {
                                is SkillUpdateManager.ApplyResult.Updated -> updated++
                                else -> failed++
                            }
                        } else {
                            skipped++
                        }

                    is SkillUpdateManager.PrepareResult.NoChange -> updated++
                    else -> failed++
                }
                setBusy(name, false)
            }
            _updateProgress.value = null
            _batchUpdating.value = false
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(updated, skipped, failed) }
        }
    }

    /** 更新横幅「全部更新」：更新所有有更新的技能。 */
    fun updateAllAvailable(onResult: (updated: Int, skipped: Int, failed: Int) -> Unit) {
        updateSkills(skillUpdateManager.sources.value.keys.toList(), onResult)
    }

    /** 取消批量更新（当前技能完成后停止）。 */
    fun cancelBatchUpdate() {
        batchCancelled = true
    }

    private fun throttledProgressEmit(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgressEmit >= PROGRESS_EMIT_INTERVAL_MS) {
            lastProgressEmit = now
            _updateProgress.value = text
        }
    }

    fun setAutoUpdate(name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillUpdateManager.setAutoUpdate(name, enabled)
        }
    }

    fun setAutoUpdateGlobally(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { it.copy(skillAutoUpdateEnabled = enabled) }
        }
    }

    private fun setBusy(name: String, busy: Boolean) {
        _busySkills.value = if (busy) {
            _busySkills.value + name
        } else {
            _busySkills.value - name
        }
    }

    /**
     * 拖动排序：同步更新内存列表（reorderable 手势要求 onMove 回调立刻反映新顺序，
     * 不能等异步的磁盘读 + flow 发射，否则拖动时会抽搐/跳动），持久化放到后台。
     */
    fun reorderSkill(name: String, newIndex: Int) {
        val current = _skills.value
        val oldIndex = current.indexOfFirst { it.name == name }
        if (oldIndex < 0) return
        val newList = current.toMutableList()
        val item = newList.removeAt(oldIndex)
        newList.add(newIndex.coerceIn(0, newList.size), item)
        _skills.value = newList
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.persistSkillOrder(newList.map { it.name })
        }
    }

    /**
     * 按当前内存顺序把排序持久化到 settings.skillOrder。
     */
    private fun persistOrder() {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.persistSkillOrder(_skills.value.map { it.name })
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromFile(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法读取文件") }
                        return@launch
                    }

                val importedNames = if (isZipFile(fileName, bytes)) {
                    importSkillsFromZip(bytes)
                } else {
                    importSkillMarkdown(bytes)
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    /** 取消进行中的 GitHub 导入/绑定（无进行中任务时为空操作） */
    fun cancelImport() {
        _importProgress.value = null
        importJob?.cancel()
        importJob = null
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        importJob?.cancel() // 防御：重复发起时终止上一次
        importJob = viewModelScope.launch(Dispatchers.IO) {
            fun fail(reason: String) {
                _importProgress.value = null
                viewModelScope.launch(Dispatchers.Main) { onResult(false, reason) }
            }
            try {
                val info = gitHubSkillClient.parseGitHubUrl(repoUrl) ?: run {
                    fail("无效的 GitHub 仓库链接")
                    return@launch
                }

                // git trees API 一次列全树（1 个 API 请求，替代逐目录递归）
                val relPaths = when (val listed = gitHubSkillClient.listTreeFiles(info)) {
                    is GitHubSkillClient.ListResult.Success -> listed.paths
                    is GitHubSkillClient.ListResult.Failed -> {
                        fail(listed.reason)
                        return@launch
                    }
                }

                // 技能根发现：根有 SKILL.md → 单技能；否则导入直接子目录下的全部技能
                val roots = gitHubSkillClient.findSkillRoots(relPaths)
                if (roots.isEmpty()) {
                    fail("目录中未找到 SKILL.md")
                    return@launch
                }

                // 预计算每个技能根的下载清单（纯本地计算），用于跨根统一进度计数
                val batches = roots.map { root ->
                    val rootAbs = joinPath(info.path, root)
                    val relToRoot = if (root.isBlank()) {
                        relPaths
                    } else {
                        relPaths.filter { it.startsWith("$root/") }.map { it.removePrefix("$root/") }
                    }
                    Triple(root, rootAbs, relToRoot.map { joinPath(rootAbs, it) })
                }
                val totalFiles = batches.sumOf { it.third.size }
                val downloaded = AtomicInteger()
                if (totalFiles > 0) {
                    lastProgressEmit = System.currentTimeMillis()
                    _importProgress.value = "正在下载 0/$totalFiles"
                }

                val importedNames = mutableListOf<String>()
                for ((root, rootAbs, absPaths) in batches) {
                    val files = when (val fetched = gitHubSkillClient.downloadFilesByAbsPath(info, absPaths) { _, _ ->
                            // 节流发射（末次必发）：并发完成回调可达每秒十几次，
                            // 高频改对话框内容会在 measure 期间引发重组合风暴
                            val done = downloaded.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (done == totalFiles || now - lastProgressEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastProgressEmit = now
                                _importProgress.value = "正在下载 $done/$totalFiles"
                            }
                        }) {
                        is GitHubSkillClient.FetchResult.Success -> fetched.files
                        is GitHubSkillClient.FetchResult.Failed -> {
                            fail(fetched.reason)
                            return@launch
                        }
                    }

                    // key 先从仓库绝对路径转为相对技能根，再找 SKILL.md
                    val rootPrefix = if (rootAbs.isBlank()) "" else "$rootAbs/"
                    val relativeFiles = files.mapKeys { it.key.removePrefix(rootPrefix) }
                    val skillMdContent = relativeFiles["SKILL.md"] ?: run {
                        fail("目录中未找到 SKILL.md")
                        return@launch
                    }
                    val frontmatter = SkillFrontmatterParser.parse(skillMdContent.decodeToString())
                    val name = frontmatter["name"]
                    if (name.isNullOrBlank()) {
                        fail("SKILL.md 格式错误：缺少 name 字段")
                        return@launch
                    }

                    val saved = skillManager.saveSkillFileBytesAtomically(name, relativeFiles)
                    if (!saved) {
                        fail("保存失败")
                        return@launch
                    }
                    // 登记来源供后续更新检查（commit SHA 查询失败不阻塞导入）
                    skillUpdateManager.recordInstall(name, info.copy(path = rootAbs), relativeFiles)
                    importedNames += name
                }

                _importProgress.value = null
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.distinct().joinToString())
                }
            } catch (e: CancellationException) {
                // 用户主动取消：清进度并按取消语义结束，不走失败回调
                _importProgress.value = null
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "importSkillFromGitHub failed", e)
                fail(e.message ?: "未知错误")
            }
        }
    }

    private fun joinPath(base: String, relative: String): String {
        return if (base.isBlank()) relative else "$base/$relative"
    }

    private fun importSkillMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim()
        if (name.isNullOrBlank()) {
            error("SKILL.md 格式错误：缺少 name 字段")
        }
        if (frontmatter["description"].isNullOrBlank()) {
            error("SKILL.md 格式错误：缺少 description 字段")
        }
        val saved = skillManager.saveSkill(name, content) ?: error("保存失败，请检查技能格式")
        return listOf(saved.name)
    }

    private fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeZipEntryPath(entry.name)
                        if (path != null) {
                            files[path] = zipInput.readBytes()
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error("压缩包中未找到 SKILL.md")
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val importedNames = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: error("读取失败：$skillMdPath")
            val frontmatter = SkillFrontmatterParser.parse(skillContent)
            val name = frontmatter["name"]?.trim()
            if (name.isNullOrBlank()) {
                error("$skillMdPath 格式错误：缺少 name 字段")
            }
            if (frontmatter["description"].isNullOrBlank()) {
                error("$skillMdPath 格式错误：缺少 description 字段")
            }

            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) {
                    "SKILL.md"
                } else {
                    relativePath
                }
                skillFiles[targetPath] = content
            }

            val saved = skillManager.saveSkillFileBytesAtomically(name, skillFiles)
            if (!saved) {
                error("保存失败：$name")
            }
            importedNames += name
        }
        return importedNames.distinct()
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }
}
