package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount

class KnowledgeBasesVM(
    private val knowledgeManager: KnowledgeManager,
) : ViewModel() {
    private val dbBases = knowledgeManager.baseRepository.getAllWithDocumentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 本地可排序列表，同步 DB 数据
    val bases: SnapshotStateList<KnowledgeBaseWithDocumentCount> = mutableStateListOf()

    private var lastDbHash: Int = 0

    init {
        viewModelScope.launch {
            dbBases.collect { list ->
                val hash = list.hashCode()
                if (hash != lastDbHash) {
                    lastDbHash = hash
                    bases.clear()
                    bases.addAll(list)
                }
            }
        }
    }

    /**
     * 创建知识库。同名已存在时返回 null（由调用方提示），不重复创建。
     */
    suspend fun createBase(name: String): String? {
        val trimmed = name.trim()
        // 查重：同名（忽略大小写）已存在则不创建
        if (bases.any { it.name.trim().equals(trimmed, ignoreCase = true) }) {
            return null
        }
        val base = knowledgeManager.baseRepository.create(name = trimmed)
        return base.id
    }

    fun deleteBase(id: String) {
        viewModelScope.launch {
            knowledgeManager.baseRepository.delete(id)
        }
    }

    fun deleteBases(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { knowledgeManager.baseRepository.delete(it) }
        }
    }

    fun reorderBases(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in bases.indices || toIndex !in bases.indices) return
        // 只在本地同步排序，拖拽即时生效；持久化由 persistOrder 在拖拽结束后执行
        bases.add(toIndex, bases.removeAt(fromIndex))
    }

    /** 拖拽结束后调用，把当前本地顺序落库。基于快照遍历，避免遍历被并发修改的列表。 */
    fun persistOrder() {
        // 捕获排序后的 id 快照
        val orderedIds = bases.map { it.id }.toList()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            orderedIds.forEachIndexed { index, id ->
                knowledgeManager.baseRepository.updateTimestamp(id, now - index * 1000L)
            }
        }
    }
}
