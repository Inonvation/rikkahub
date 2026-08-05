package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderEntry
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository

/**
 * 信任文件夹文件浏览器 VM。目录导航 + 列目录 + 增删改移，全部基于指定的 [projectId] 项目，
 * 与「激活项目」无关（用户可同时浏览多个项目）。
 * 操作成功自动刷新列表，失败写入 [TrustedFolderDetailState.error] 供 UI 展示。
 */
class TrustedFolderDetailVM(
    private val repository: TrustedFolderRepository,
    private val projectId: String,
    /** 初始目录路径（相对项目根，空串=根目录），用于从入口跳转定位 */
    initialPath: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(TrustedFolderDetailState(path = initialPath))
    val state = _state.asStateFlow()

    /** 项目名（顶栏标题），项目被删除则变 null */
    val projectName: StateFlow<String?> = repository.settingsFlow
        .map { s -> s.projects.find { it.id == projectId }?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 目录缓存：进入过的目录条目快照，返回上级时秒开、不重复请求 SAF */
    private val dirCache = mutableMapOf<String, List<TrustedFolderEntry>>()

    init {
        navigate(initialPath)
    }

    fun open(entry: TrustedFolderEntry) {
        if (!entry.isDirectory) return
        navigate(entry.path)
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        navigate(path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    /** 导航到目录：缓存命中直接秒开；未命中则加载并写入缓存 */
    fun navigate(path: String) {
        val cached = dirCache[path]
        if (cached != null) {
            _state.update { it.copy(path = path, entries = cached, loading = false, error = null) }
        } else {
            _state.update { it.copy(path = path, entries = emptyList(), loading = true, error = null) }
            load(path)
        }
    }

    /** 强制刷新当前目录（顶栏刷新/从编辑器返回）：重新请求 SAF 并更新缓存 */
    fun refresh() {
        load(state.value.path)
    }

    private fun load(path: String) {
        viewModelScope.launch {
            runCatching {
                val showConfig = repository.showConfigFoldersOf(projectId)
                val entries = repository.list(path, projectId)
                // 未开启「显示配置文件夹」时过滤 .obsidian 等点开头目录
                if (showConfig) entries else entries.filter { !it.name.startsWith(".") }
            }.onSuccess { entries ->
                dirCache[path] = entries
                _state.update { it.copy(entries = entries, loading = false) }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(entries = emptyList(), loading = false, error = error.message ?: "加载失败")
                }
            }
        }
    }

    fun createFile(fileName: String) = runOp("新建文件失败") {
        repository.writeText(fullPath(fileName), text = "", overwrite = false, projectId = projectId)
    }

    fun createFolder(dirName: String) = runOp("新建文件夹失败") {
        repository.createFolder(fullPath(dirName), projectId = projectId)
    }

    fun rename(entry: TrustedFolderEntry, newName: String) = runOp("重命名失败") {
        repository.rename(entry.path, newName, projectId = projectId)
    }

    fun delete(entry: TrustedFolderEntry) = runOp("删除失败") {
        repository.delete(entry.path, projectId = projectId)
    }

    /** 批量删除（多选模式） */
    fun deleteEntries(entries: List<TrustedFolderEntry>) = runOp("删除失败") {
        entries.forEach { repository.delete(it.path, projectId = projectId) }
    }

    /** 批量移动到其他目录（多选模式），保持各自原名 */
    fun moveEntries(entries: List<TrustedFolderEntry>, targetDir: String) = runOp("移动失败") {
        entries.forEach {
            if (targetDir != it.path.substringBeforeLast('/', missingDelimiterValue = "")) {
                repository.move(it.path, targetDir, projectId = projectId)
            }
        }
    }

    /** 移动到项目内其他目录（[targetDir] 相对项目根，空串=根目录），保持原名 */
    fun moveTo(entry: TrustedFolderEntry, targetDir: String) = runOp("移动失败") {
        if (targetDir == entry.path.substringBeforeLast('/', missingDelimiterValue = "")) {
            // 已在目标目录，视为 no-op
        } else {
            repository.move(entry.path, targetDir, projectId = projectId)
        }
    }

    private fun fullPath(name: String): String {
        val base = state.value.path
        return if (base.isBlank()) name else "$base/$name"
    }

    private fun runOp(errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    // 文件已变更：清空目录缓存保证一致性，再刷新当前目录
                    dirCache.clear()
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = "$errorPrefix: ${e.message}") }
                }
        }
    }
}

data class TrustedFolderDetailState(
    /** 当前目录（相对项目根，空串=根目录） */
    val path: String = "",
    val entries: List<TrustedFolderEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
