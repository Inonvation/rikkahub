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
import me.rerere.rikkahub.data.DocumentProcessor

class KnowledgeBaseSettingsVM(
    private val knowledgeManager: KnowledgeManager,
    private val documentProcessor: DocumentProcessor,
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
    var chunkSize by mutableStateOf(KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE)
        private set
    var chunkOverlap by mutableStateOf(KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP)
        private set
    var chunkStrategy by mutableStateOf(KnowledgeBaseEntity.DEFAULT_CHUNK_STRATEGY)
        private set
    var topK by mutableStateOf(KnowledgeBaseEntity.DEFAULT_TOP_K)
        private set
    var similarityThreshold by mutableStateOf(0f)
        private set
    var useHyde by mutableStateOf(false)
        private set

    // 手动重处理全部文档的任务状态
    var reprocessing by mutableStateOf(false)
        private set

    // 分块设置已改、索引尚未重建的提示（会话级内存态，不落库）
    var hasPendingReprocess by mutableStateOf(false)
        private set

    private var saveJob: Job? = null
    private var reprocessJob: Job? = null
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
        useHyde = entity.useHyde
        loaded = true
    }

    fun updateName(value: String) { name = value; scheduleSave() }
    fun updateDescription(value: String) { description = value; scheduleSave() }
    fun updateEmbeddingModelId(value: String?) { embeddingModelId = value; scheduleSave(0) }
    fun updateRerankModelId(value: String?) { rerankModelId = value; scheduleSave(0) }

    // 数字字段不立即保存：先写内存态（供 UI 反馈），防抖后才落库。
    // 不做 coerce 钳制，避免悄悄改掉用户输入；钳制在 doSave 持久化时统一做。
    fun updateChunkSize(value: Int) { chunkSize = value; scheduleSave() }
    fun updateChunkOverlap(value: Int) { chunkOverlap = value; scheduleSave() }
    fun updateChunkStrategy(value: String) { chunkStrategy = value; scheduleSave() }
    fun updateTopK(value: Int) { topK = value; scheduleSave() }
    fun updateSimilarityThreshold(value: Float) { similarityThreshold = value.coerceIn(0f, 1f); scheduleSave() }
    fun updateUseHyde(value: Boolean) { useHyde = value; scheduleSave() }

    /** 防抖保存；delayMs=0 时立即保存。 */
    private fun scheduleSave(delayMs: Long = 500) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            doSave()
        }
    }

    private suspend fun doSave() {
        if (!loaded) return
        val current = knowledgeManager.baseRepository.getById(baseId) ?: return

        // 范围钳制统一在持久化时做，并把钳制结果同步回内存态（输入框会据此回显实际保存值）
        val safeChunkSize = chunkSize.coerceIn(128, 8192)
        val safeChunkOverlap = chunkOverlap.coerceIn(0, safeChunkSize - 1)
        val safeTopK = topK.coerceIn(1, 100)
        chunkSize = safeChunkSize
        chunkOverlap = safeChunkOverlap
        topK = safeTopK

        val chunkConfigChanged =
            current.chunkSize != safeChunkSize ||
                current.chunkOverlap != safeChunkOverlap ||
                current.chunkStrategy != chunkStrategy

        knowledgeManager.baseRepository.update(
            current.copy(
                name = name,
                description = description,
                embeddingModelId = embeddingModelId,
                rerankModelId = rerankModelId,
                chunkSize = safeChunkSize,
                chunkOverlap = safeChunkOverlap,
                chunkStrategy = chunkStrategy,
                topK = safeTopK,
                similarityThreshold = similarityThreshold,
                useHyde = useHyde,
                updatedAt = System.currentTimeMillis(),
            )
        )

        // 分块设置变化：只标记"索引未重建"，由用户手动触发重处理，避免自动重处理被打断
        hasPendingReprocess = chunkConfigChanged
    }

    /**
     * 重新处理该知识库全部文档（分块设置或模型改动后手动触发）。
     * 新任务开始前取消旧任务，避免并发重处理互相干扰。
     */
    fun reprocessAll() {
        reprocessJob?.cancel()
        reprocessJob = viewModelScope.launch {
            reprocessing = true
            try {
                documentProcessor.reprocessAll()
                hasPendingReprocess = false
            } finally {
                reprocessing = false
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            knowledgeManager.baseRepository.delete(baseId)
        }
    }
}