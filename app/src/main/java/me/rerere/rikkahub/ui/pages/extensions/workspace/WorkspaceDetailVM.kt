package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    /** 初始存储区（从聊天跳转到文件所在目录时传入），null=默认 FILES */
    private val initialArea: WorkspaceStorageArea? = null,
    /** 初始目录路径（相对存储区根，""=根目录），用于定位到文件所在目录 */
    private val initialPath: String = "",
    /** 目标文件名（或区相对路径）：目录加载后列表滚动到该项并高亮，超时自动渐隐 */
    private val initialHighlight: String? = null,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _installToolsState = MutableStateFlow(InstallToolsState())
    val installToolsState = _installToolsState.asStateFlow()

    /** 目录缓存：进入过的目录条目快照（key = 存储区/路径），返回上级时秒开、不重复请求 */
    private val dirCache = mutableMapOf<String, List<WorkspaceFileEntry>>()

    /** 定位高亮的自动清除任务；换目录时取消 */
    private var highlightClearJob: Job? = null

    init {
        loadWorkspace()
        // 从聊天跳转定位：直接进入文件所在目录并加载该目录内容
        navigate(initialPath, initialArea ?: WorkspaceStorageArea.FILES)
        // 定位高亮：列表滚动到目标文件并突出显示，数秒后渐隐（navigate 已把高亮清空的路径留在这里重新设置）
        initialHighlight?.takeIf { it.isNotBlank() }?.let { highlight ->
            _state.update { it.copy(highlightPath = highlight) }
            highlightClearJob = viewModelScope.launch {
                delay(HIGHLIGHT_AUTO_CLEAR_MS)
                clearHighlight()
            }
        }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        navigate("", area)
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        navigate(entry.path)
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        navigate(path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    /** 导航到目录：缓存命中直接秒开；未命中则加载并写入缓存 */
    fun navigate(path: String, area: WorkspaceStorageArea? = null) {
        clearHighlight()
        val targetArea = area ?: state.value.area
        val cached = dirCache[cacheKey(targetArea, path)]
        if (cached != null) {
            _state.update {
                it.copy(area = targetArea, path = path, entries = cached, loading = false, error = null)
            }
        } else {
            _state.update {
                it.copy(area = targetArea, path = path, entries = emptyList(), loading = true, error = null)
            }
            load(path, targetArea)
        }
    }

    /** 强制刷新当前目录（顶栏刷新/从编辑器返回）：重新请求并更新缓存 */
    fun refresh() {
        load(state.value.path, state.value.area)
    }

    /** 文件变更后：清空目录缓存保证一致性，再刷新当前目录 */
    private fun refreshAfterMutation() {
        dirCache.clear()
        refresh()
    }

    private fun load(path: String, area: WorkspaceStorageArea) {
        viewModelScope.launch {
            runCatching {
                repository.listFiles(
                    id = id,
                    area = area,
                    path = path,
                )
            }.onSuccess { entries ->
                dirCache[cacheKey(area, path)] = entries
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    private fun cacheKey(area: WorkspaceStorageArea, path: String): String = "${area.name}/$path"

    /** 清除定位高亮（渐隐动画由 UI 侧 animateColorAsState 承担）；调用方负责取消挂起的清除任务 */
    fun clearHighlight() {
        highlightClearJob?.cancel()
        highlightClearJob = null
        if (_state.value.highlightPath != null) {
            _state.update { it.copy(highlightPath = null) }
        }
    }

    /** 移入回收站(软删除), 可从统一回收站恢复 */
    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.trashFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refreshAfterMutation()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "移入回收站失败") }
            }
        }
    }

    /** 彻底删除(不经过回收站, 不可恢复) */
    fun deletePermanently(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refreshAfterMutation()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    /** 批量移入回收站（多选） */
    fun trashEntries(entries: List<WorkspaceFileEntry>) {
        viewModelScope.launch {
            val area = state.value.area
            val errors = mutableListOf<String>()
            dedupeNestedEntries(entries).forEach { entry ->
                runCatching {
                    repository.trashFile(
                        id = id,
                        area = area,
                        path = entry.path,
                        recursive = entry.isDirectory,
                    )
                }.onFailure { error -> errors += "${entry.name}：${error.message}" }
            }
            reportBatchErrors(errors)
            refreshAfterMutation()
        }
    }

    /** 批量彻底删除（多选） */
    fun deleteEntries(entries: List<WorkspaceFileEntry>) {
        viewModelScope.launch {
            val area = state.value.area
            val errors = mutableListOf<String>()
            dedupeNestedEntries(entries).forEach { entry ->
                runCatching {
                    repository.deleteFile(
                        id = id,
                        area = area,
                        path = entry.path,
                        recursive = entry.isDirectory,
                    )
                }.onFailure { error -> errors += "${entry.name}：${error.message}" }
            }
            reportBatchErrors(errors)
            refreshAfterMutation()
        }
    }

    /**
     * 批量移动到目标目录。[targetDir] 为当前存储区内相对路径（"" 表示根目录）。
     * 逐个移动；自动跳过 no-op（源已在目标目录）与"目录移入自身或其子目录"。
     */
    fun moveEntries(entries: List<WorkspaceFileEntry>, targetDir: String) {
        viewModelScope.launch {
            val area = state.value.area
            val errors = mutableListOf<String>()
            dedupeNestedEntries(entries).forEach { entry ->
                val target = if (targetDir.isBlank()) entry.name else "$targetDir/${entry.name}"
                // no-op：源已在目标目录
                if (target == entry.path) return@forEach
                // 目录不能移入自身或其子目录
                if (entry.isDirectory && target.startsWith(entry.path + "/")) {
                    errors += "${entry.name}：不能移动到自身或其子目录"
                    return@forEach
                }
                runCatching {
                    repository.moveFile(
                        id = id,
                        source = entry.path,
                        target = target,
                        overwrite = false,
                        area = area,
                    )
                }.onFailure { error -> errors += "${entry.name}：${error.message}" }
            }
            reportBatchErrors(errors)
            refreshAfterMutation()
        }
    }

    /** 批量操作的失败汇总写入错误卡片（最多展示前 3 条） */
    private fun reportBatchErrors(errors: List<String>) {
        if (errors.isEmpty()) return
        _state.update {
            it.copy(
                error = errors.take(3).joinToString("\n") +
                    if (errors.size > 3) "\n… 共 ${errors.size} 项失败" else "",
            )
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refreshAfterMutation()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    /** 当前目录下新建空文件 */
    fun createFile(fileName: String) {
        val name = fileName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val base = state.value.path
            runCatching {
                repository.writeText(
                    id = id,
                    path = if (base.isBlank()) name else "$base/$name",
                    text = "",
                    overwrite = false,
                )
            }.onSuccess { refreshAfterMutation() }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "新建文件失败") }
            }
        }
    }

    /** 当前目录下新建目录(含多级) */
    fun createDirectory(dirName: String) {
        val name = dirName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val base = state.value.path
            runCatching {
                repository.createDirectory(
                    id = id,
                    area = state.value.area,
                    path = if (base.isBlank()) name else "$base/$name",
                )
            }.onSuccess { refreshAfterMutation() }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "新建目录失败") }
            }
        }
    }

    /** 重命名: 用 moveFile 在当前目录内改名(文件与目录均可) */
    fun rename(entry: WorkspaceFileEntry, newName: String) {
        val name = newName.trim()
        if (name.isBlank() || name == entry.name) return
        viewModelScope.launch {
            val parent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
            val target = if (parent.isBlank()) name else "$parent/$name"
            runCatching {
                repository.moveFile(
                    id = id,
                    source = entry.path,
                    target = target,
                    overwrite = false,
                    area = state.value.area,
                )
            }.onSuccess { refreshAfterMutation() }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "重命名失败") }
            }
        }
    }

    /** 把 files 区打包为 zip 备份导出 */
    fun backupTo(outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching { repository.backupFiles(id, outputStream) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "备份失败") }
                }
        }
    }

    /** 从备份 zip 恢复 files 区 (覆盖现有内容) */
    fun restoreFrom(inputStream: InputStream) {
        viewModelScope.launch {
            runCatching { repository.restoreFiles(id, inputStream) }
                .onSuccess { refreshAfterMutation() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "恢复失败") }
                }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    /** 安装常用工具链 (python/git/curl 等), 完成后刷新 AGENTS 自动生成区让 AI 看到新工具 */
    fun installCommonTools() {
        viewModelScope.launch {
            _installToolsState.update { it.copy(running = true, error = null) }
            try {
                val result = repository.installCommonTools(id)
                if (result.exitCode != 0) {
                    _installToolsState.update {
                        it.copy(running = false, error = "安装失败: ${result.stderr.trim().take(200)}")
                    }
                } else {
                    runCatching { repository.ensureAgentsFile(id) }
                    _installToolsState.update { it.copy(running = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installToolsState.update { it.copy(running = false, error = error.message ?: "安装失败") }
            }
        }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 定位高亮的条目（文件名或区相对路径），null=无高亮 */
    val highlightPath: String? = null,
)

/** 定位高亮展示时长：足以注意到并确认目标，之后渐隐不常驻 */
private const val HIGHLIGHT_AUTO_CLEAR_MS = 4500L

data class InstallToolsState(
    val running: Boolean = false,
    val error: String? = null,
)
