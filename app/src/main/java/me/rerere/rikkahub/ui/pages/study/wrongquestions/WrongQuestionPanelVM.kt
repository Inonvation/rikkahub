package me.rerere.rikkahub.ui.pages.study.wrongquestions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity

class WrongQuestionPanelVM(
    private val wrongQuestionDao: WrongQuestionDao,
) : ViewModel() {
    val mathQuestions = wrongQuestionDao.getBySubjectFlow("math")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mechanicsQuestions = wrongQuestionDao.getBySubjectFlow("mechanics")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateReview(entity: WrongQuestionEntity) {
        viewModelScope.launch {
            wrongQuestionDao.update(entity.copy(lastReviewedAt = System.currentTimeMillis(), reviewCount = entity.reviewCount + 1))
        }
    }

    fun delete(id: String) { viewModelScope.launch { wrongQuestionDao.deleteById(id) } }
    fun archive(id: String) { viewModelScope.launch { wrongQuestionDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { wrongQuestionDao.restore(id) } }
    suspend fun getArchived(): List<WrongQuestionEntity> = wrongQuestionDao.getArchived()
}