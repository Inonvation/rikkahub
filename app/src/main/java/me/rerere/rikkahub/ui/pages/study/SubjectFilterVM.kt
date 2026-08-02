package me.rerere.rikkahub.ui.pages.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 三个学习面板（笔记/错题本/知识点卡片）共用的学科筛选基类。
 * selectedSubject = null 表示「全部」。
 */
abstract class SubjectFilterVM<T>(
    allFlow: Flow<List<T>>,
    subjectsFlow: Flow<List<String>>,
    private val subjectOf: (T) -> String,
) : ViewModel() {
    val subjects = subjectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedSubject = MutableStateFlow<String?>(null)

    private val allItems = allFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items = combine(allItems, selectedSubject) { list, subject ->
        if (subject == null) list else list.filter { subjectOf(it) == subject }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(subject: String?) {
        selectedSubject.value = subject
    }
}
