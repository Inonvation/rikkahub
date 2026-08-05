package me.rerere.rikkahub.ui.pages.trustedfolders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderProject
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedOp

/**
 * 项目设置页 VM：AI 审批/配置目录保护等设置，全部只作用于指定的 [projectId] 项目，
 * 与其它项目隔离。操作失败通过 [message] 以 toast 反馈。
 */
class TrustedFolderSettingsVM(
    private val repository: TrustedFolderRepository,
    private val projectId: String,
) : ViewModel() {
    /** 目标项目（实时），项目被删除则变 null */
    val project: StateFlow<TrustedFolderProject?> = repository.settingsFlow
        .map { s -> s.projects.find { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    fun updateApproval(op: TrustedOp, enabled: Boolean) = launch {
        runCatching {
            repository.updateProjectSettings(projectId) { p ->
                when (op) {
                    TrustedOp.READ -> p.copy(approvalRead = enabled)
                    TrustedOp.CREATE -> p.copy(approvalCreate = enabled)
                    TrustedOp.EDIT -> p.copy(approvalEdit = enabled)
                    TrustedOp.DELETE -> p.copy(approvalDelete = enabled)
                }
            }
        }.onFailure { _message.emit(it.message ?: "保存设置失败") }
    }

    /** 文件列表中是否显示配置目录（.obsidian 等） */
    fun updateShowConfigFolders(enabled: Boolean) = launch {
        runCatching { repository.updateProjectSettings(projectId) { it.copy(showConfigFolders = enabled) } }
            .onFailure { _message.emit(it.message ?: "保存设置失败") }
    }

    /** 是否允许 AI 修改配置目录 */
    fun updateAllowEditConfigFolders(enabled: Boolean) = launch {
        runCatching { repository.updateProjectSettings(projectId) { it.copy(allowEditConfigFolders = enabled) } }
            .onFailure { _message.emit(it.message ?: "保存设置失败") }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
