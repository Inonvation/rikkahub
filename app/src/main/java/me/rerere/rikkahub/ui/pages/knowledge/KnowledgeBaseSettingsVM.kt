package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.data.entity.KnowledgeBaseEntity

class KnowledgeBaseSettingsVM(
    private val knowledgeManager: KnowledgeManager,
    private val baseId: String,
) : ViewModel() {
    val base = knowledgeManager.baseRepository.getByIdFlow(baseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    var embeddingModelId by mutableStateOf<String?>(null)
        private set
    var rerankModelId by mutableStateOf<String?>(null)
        private set
    var chunkSize by mutableStateOf(1024)
        private set
    var chunkOverlap by mutableStateOf(200)
        private set
    var chunkStrategy by mutableStateOf("fixed_size")
        private set
    var topK by mutableStateOf(10)
        private set
    var similarityThreshold by mutableStateOf(0f)
        private set

    private var saveJob: Job? = null
    private var loaded = false

    init {
        viewModelScope.launch {
            knowledgeManager.baseRepository.getById(baseId)?.let { loadFromEntity(it) }
        }
    }

    private fun loadFromEntity(entity: KnowledgeBaseEntity) {
        loaded = false
        name = entity.name
        description = entity.description
        embeddingModelId = entity.embeddingModelId
        rerankModelId = entity.rerankModelId
        chunkSize = entity.chunkSize
        chunkOverlap = entity.chunkOverlap
        chunkStrategy = entity.chunkStrategy
        topK = entity.topK
        similarityThreshold = entity.similarityThreshold
        loaded = true
    }

    fun updateName(value: String) { name = value; scheduleSave() }
    fun updateDescription(value: String) { description = value; scheduleSave() }
    fun updateEmbeddingModelId(value: String?) { embeddingModelId = value; saveNow() }
    fun updateRerankModelId(value: String?) { rerankModelId = value; saveNow() }
    fun updateChunkSize(value: Int) { chunkSize = value.coerceIn(128, 8192); saveNow() }
    fun updateChunkOverlap(value: Int) { chunkOverlap = value.coerceIn(0, chunkSize - 1); saveNow() }
    fun updateChunkStrategy(value: String) { chunkStrategy = value; saveNow() }
    fun updateTopK(value: Int) { topK = value.coerceIn(1, 100); saveNow() }
    fun updateSimilarityThreshold(value: Float) { similarityThreshold = value.coerceIn(0f, 1f); saveNow() }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            doSave()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            doSave()
        }
    }

    private suspend fun doSave() {
        if (!loaded) return
        val current = knowledgeManager.baseRepository.getById(baseId) ?: return
        knowledgeManager.baseRepository.update(
            current.copy(
                name = name,
                description = description,
                embeddingModelId = embeddingModelId,
                rerankModelId = rerankModelId,
                chunkSize = chunkSize,
                chunkOverlap = chunkOverlap,
                chunkStrategy = chunkStrategy,
                topK = topK,
                similarityThreshold = similarityThreshold,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun delete() {
        viewModelScope.launch {
            knowledgeManager.baseRepository.delete(baseId)
        }
    }
}