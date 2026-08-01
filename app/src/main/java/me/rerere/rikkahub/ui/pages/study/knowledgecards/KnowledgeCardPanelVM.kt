package me.rerere.rikkahub.ui.pages.study.knowledgecards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity

class KnowledgeCardPanelVM(
    private val knowledgeCardDao: KnowledgeCardDao,
) : ViewModel() {
    val politicsCards = knowledgeCardDao.getBySubjectFlow("politics")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mechanicsCards = knowledgeCardDao.getBySubjectFlow("mechanics")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateReview(entity: KnowledgeCardEntity) {
        viewModelScope.launch {
            knowledgeCardDao.update(entity.copy(lastReviewedAt = System.currentTimeMillis(), reviewCount = entity.reviewCount + 1))
        }
    }

    fun delete(id: String) { viewModelScope.launch { knowledgeCardDao.deleteById(id) } }
    fun archive(id: String) { viewModelScope.launch { knowledgeCardDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { knowledgeCardDao.restore(id) } }
    suspend fun getArchived(): List<KnowledgeCardEntity> = knowledgeCardDao.getArchived()
}