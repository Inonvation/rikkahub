package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings

/**
 * 信任文件夹项目页 VM：项目列表 + 激活切换 + 项目管理。
 * 操作结果通过 [message] 以 toast 形式反馈给 UI。
 */
class TrustedFoldersVM(
    private val repository: TrustedFolderRepository,
) : ViewModel() {
    val settings: StateFlow<TrustedFolderSettings> =
        repository.settingsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TrustedFolderSettings(),
        )

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    fun addProject(name: String, treeUri: String) = launch {
        runCatching { repository.addProject(name, treeUri) }
            .onSuccess { _message.emit("已添加项目") }
            .onFailure { _message.emit(it.message ?: "添加项目失败") }
    }

    fun removeProject(id: String) = launch {
        runCatching { repository.removeProject(id) }
            .onSuccess { _message.emit("已删除项目") }
            .onFailure { _message.emit(it.message ?: "删除失败") }
    }

    fun renameProject(id: String, name: String) = launch {
        runCatching { repository.renameProject(id, name) }
            .onSuccess { _message.emit("已重命名") }
            .onFailure { _message.emit(it.message ?: "重命名失败") }
    }

    /** 激活/取消激活项目（null = 解除激活） */
    fun setActive(id: String?) = launch {
        runCatching { repository.setActiveProject(id) }
            .onFailure { _message.emit(it.message ?: "切换失败") }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
