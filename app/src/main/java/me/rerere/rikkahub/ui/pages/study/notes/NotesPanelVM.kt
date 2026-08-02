package me.rerere.rikkahub.ui.pages.study.notes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.ui.pages.study.SubjectFilterVM

class NotesPanelVM(
    private val noteDao: NoteDao,
) : SubjectFilterVM<me.rerere.rikkahub.data.db.entity.NoteEntity>(
    allFlow = noteDao.getAllFlow(),
    subjectsFlow = noteDao.getAllSubjectsFlow(),
    subjectOf = { it.subject },
    searchPredicate = { n, q -> n.title.contains(q, true) || n.content.contains(q, true) },
) {
    fun delete(id: String) { viewModelScope.launch { noteDao.deleteById(id) } }
    fun deleteByIds(ids: List<String>) { viewModelScope.launch { if (ids.isNotEmpty()) noteDao.deleteByIds(ids) } }
    fun archive(id: String) { viewModelScope.launch { noteDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { noteDao.restore(id) } }
    suspend fun getArchived() = noteDao.getArchived()
}
