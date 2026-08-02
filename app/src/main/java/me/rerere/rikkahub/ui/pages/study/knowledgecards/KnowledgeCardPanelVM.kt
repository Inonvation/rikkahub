package me.rerere.rikkahub.ui.pages.study.knowledgecards

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity
import me.rerere.rikkahub.ui.pages.study.SubjectFilterVM

class KnowledgeCardPanelVM(
    private val knowledgeCardDao: KnowledgeCardDao,
) : SubjectFilterVM<KnowledgeCardEntity>(
    allFlow = knowledgeCardDao.getAllFlow(),
    subjectsFlow = knowledgeCardDao.getAllSubjectsFlow(),
    subjectOf = { it.subject },
    searchPredicate = { c, q -> c.concept.contains(q, true) || c.explanation.contains(q, true) || c.memoryAid.contains(q, true) },
) {
    fun updateReview(entity: KnowledgeCardEntity) {
        viewModelScope.launch {
            knowledgeCardDao.update(entity.copy(lastReviewedAt = System.currentTimeMillis(), reviewCount = entity.reviewCount + 1))
        }
    }

    fun delete(id: String) { viewModelScope.launch { knowledgeCardDao.deleteById(id) } }
    fun deleteByIds(ids: List<String>) { viewModelScope.launch { if (ids.isNotEmpty()) knowledgeCardDao.deleteByIds(ids) } }
    fun archive(id: String) { viewModelScope.launch { knowledgeCardDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { knowledgeCardDao.restore(id) } }
    suspend fun getArchived(): List<KnowledgeCardEntity> = knowledgeCardDao.getArchived()
}
