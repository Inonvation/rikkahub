package me.rerere.rikkahub.ui.pages.study.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.NoteDao

class NotesPanelVM(
    private val noteDao: NoteDao,
) : ViewModel() {
    val notes = noteDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: String) { viewModelScope.launch { noteDao.deleteById(id) } }
    fun archive(id: String) { viewModelScope.launch { noteDao.archive(id) } }
    fun restore(id: String) { viewModelScope.launch { noteDao.restore(id) } }
    suspend fun getArchived() = noteDao.getArchived()
}