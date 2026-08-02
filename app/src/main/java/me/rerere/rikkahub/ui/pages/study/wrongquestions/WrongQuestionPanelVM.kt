package me.rerere.rikkahub.ui.pages.study.wrongquestions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity
import me.rerere.rikkahub.ui.pages.study.SubjectFilterVM

class WrongQuestionPanelVM(
    private val wrongQuestionDao: WrongQuestionDao,
) : SubjectFilterVM<WrongQuestionEntity>(
    allFlow = wrongQuestionDao.getAllFlow(),
    subjectsFlow = wrongQuestionDao.getAllSubjectsFlow(),
    subjectOf = { it.subject },
) {
    fun updateReview(entity: WrongQuestionEntity) {
        viewModelScope.launch {
            wrongQuestionDao.update(entity.copy(lastReviewedAt = System.currentTimeMillis(), reviewCount = entity.reviewCount + 1))
        }
    }

    fun delete(id: String) { viewModelScope.launch { wrongQuestionDao.deleteById(id) } }
    fun deleteByIds(ids: List<String>) { viewModelScope.launch { if (ids.isNotEmpty()) wrongQuestionDao.deleteByIds(ids) } }
    fun archive(id: String) { viewModelScope.launch { wrongQuestionDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { wrongQuestionDao.restore(id) } }
    suspend fun getArchived(): List<WrongQuestionEntity> = wrongQuestionDao.getArchived()
}
