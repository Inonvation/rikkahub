package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val dbWorkspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 本地可排序列表，同步 DB 数据
    val workspaces: SnapshotStateList<WorkspaceEntity> = mutableStateListOf()

    private var lastDbHash: Int = 0

    init {
        viewModelScope.launch {
            dbWorkspaces.collect { list ->
                val hash = list.hashCode()
                if (hash != lastDbHash) {
                    lastDbHash = hash
                    workspaces.clear()
                    workspaces.addAll(list)
                }
            }
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { repository.create(name) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            repository.delete(workspace.id)
        }
    }

    fun deleteWorkspaces(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { repository.delete(it) }
        }
    }

    fun reorderWorkspaces(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in workspaces.indices || toIndex !in workspaces.indices) return
        // 只在本地同步排序，拖拽即时生效；持久化由 persistOrder 在拖拽结束后执行
        workspaces.add(toIndex, workspaces.removeAt(fromIndex))
    }

    /** 拖拽结束后调用，把当前本地顺序落库。基于快照遍历，避免遍历被并发修改的列表。 */
    fun persistOrder() {
        val orderedIds = workspaces.map { it.id }.toList()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            orderedIds.forEachIndexed { index, wsId ->
                repository.updateTimestamp(wsId, now - index * 1000L)
            }
        }
    }
}
