package me.rerere.rikkahub.ui.pages.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.knowledge.KnowledgeManager

class KnowledgeBasesVM(
    private val knowledgeManager: KnowledgeManager,
) : ViewModel() {
    val bases = knowledgeManager.baseRepository.getAllWithDocumentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun createBase(name: String): String {
        val base = knowledgeManager.baseRepository.create(name = name)
        return base.id
    }

    fun deleteBase(id: String) {
        viewModelScope.launch {
            knowledgeManager.baseRepository.delete(id)
        }
    }
}