package me.rerere.rikkahub.ui.pages.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 三个学习面板（笔记/错题本/知识点卡片）共用的学科筛选基类。
 * selectedSubject = null 表示「全部」；searchQuery 非空时叠加内存搜索过滤。
 */
abstract class SubjectFilterVM<T>(
    allFlow: Flow<List<T>>,
    subjectsFlow: Flow<List<String>>,
    private val subjectOf: (T) -> String,
    private val searchPredicate: (T, String) -> Boolean = { _, _ -> true },
) : ViewModel() {
    val subjects = subjectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedSubject = MutableStateFlow<String?>(null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val allItems = allFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items = combine(allItems, selectedSubject, _searchQuery) { list, subject, query ->
        list.filter {
            (subject == null || subjectOf(it) == subject) &&
                (query.isBlank() || searchPredicate(it, query))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(subject: String?) {
        selectedSubject.value = subject
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
