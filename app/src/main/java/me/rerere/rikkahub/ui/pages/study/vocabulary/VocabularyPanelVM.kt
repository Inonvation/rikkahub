package me.rerere.rikkahub.ui.pages.study.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.entity.VocabularyEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class VocabularySettings(
    val cooldownSeconds: Int = 3,
    val sortBy: String = "time",
    val showDefinitionOnCard: Boolean = false,
)

data class WordGroup(
    val dateLabel: String,
    val timestamp: Long,
    val words: List<VocabularyEntity>,
)

class VocabularyPanelVM(
    private val vocabularyDao: VocabularyDao,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val settings = MutableStateFlow(VocabularySettings())

    private val rawWords = vocabularyDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wordGroups: StateFlow<List<WordGroup>> = combine(
        rawWords, _searchQuery, settings
    ) { words, query, s ->
        val filtered = if (query.isBlank()) words
        else words.filter { it.word.contains(query, ignoreCase = true) }
        val sorted = when (s.sortBy) {
            "alphabetical" -> filtered.sortedBy { it.word.lowercase() }
            "reviewCount" -> filtered.sortedByDescending { it.reviewCount }
            else -> filtered
        }
        groupByDate(sorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSettings(newSettings: VocabularySettings) {
        settings.value = newSettings
    }

    fun updateReview(entity: VocabularyEntity) {
        viewModelScope.launch {
            vocabularyDao.update(
                entity.copy(
                    lastReviewedAt = System.currentTimeMillis(),
                    reviewCount = entity.reviewCount + 1,
                )
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { vocabularyDao.deleteById(id) }
    }

    fun deleteByIds(ids: List<String>) {
        viewModelScope.launch { if (ids.isNotEmpty()) vocabularyDao.deleteByIds(ids) }
    }

    fun archive(id: String) {
        viewModelScope.launch { vocabularyDao.archive(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { vocabularyDao.restore(id) }
    }

    suspend fun getArchived(): List<VocabularyEntity> = vocabularyDao.getArchived()

    companion object {
        private val dateFormatter = DateTimeFormatter.ofPattern("MM月dd日")
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun formatTime(epochMs: Long): String {
            val instant = Instant.ofEpochMilli(epochMs)
            return instant.atZone(ZoneId.systemDefault()).format(timeFormatter)
        }

        private fun groupByDate(words: List<VocabularyEntity>): List<WordGroup> {
            if (words.isEmpty()) return emptyList()
            val dateKeyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val grouped = words.groupBy { word ->
                val instant = Instant.ofEpochMilli(word.createdAt)
                instant.atZone(ZoneId.systemDefault()).format(dateKeyFormatter)
            }
            return grouped.entries.map { (_, wordList) ->
                val firstWord = wordList.first()
                WordGroup(
                    dateLabel = formatDate(firstWord.createdAt),
                    timestamp = firstWord.createdAt,
                    words = wordList,
                )
            }.sortedByDescending { it.timestamp }
        }

        private fun formatDate(epochMs: Long): String {
            val local = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
            val date = local.toLocalDate()
            return when {
                date == today -> "今天"
                date == today.minusDays(1) -> "昨天"
                date == today.minusDays(2) -> "前天"
                else -> local.format(dateFormatter)
            }
        }
    }
}