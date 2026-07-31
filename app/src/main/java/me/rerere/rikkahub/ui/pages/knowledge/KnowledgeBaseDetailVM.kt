package me.rerere.rikkahub.ui.pages.knowledge

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.chunking.FixedSizeChunker
import me.rerere.knowledge.chunking.ParagraphChunker
import me.rerere.knowledge.chunking.SentenceChunker
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.retrieval.RetrievalResult
import me.rerere.knowledge.vector.toByteArray
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.io.File

class KnowledgeBaseDetailVM(
    private val knowledgeManager: KnowledgeManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val baseId: String,
) : ViewModel() {
    val base = knowledgeManager.baseRepository.getByIdFlow(baseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val documents = knowledgeManager.documentRepository.getByKnowledgeBaseId(baseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _processingState = MutableStateFlow<Map<String, Float>>(emptyMap())
    val processingState = _processingState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<RetrievalResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private var _searchLoading = MutableStateFlow(false)
    val searchLoading = _searchLoading.asStateFlow()

    fun addDocument(uri: Uri, context: Context) {
        viewModelScope.launch {
            val fileName = uri.lastPathSegment ?: "unknown"
            val fileType = fileName.substringAfterLast('.', "txt").lowercase()
            val filePath = "${context.filesDir}/knowledge/${baseId}/raw/${fileName}"

            withContext(Dispatchers.IO) {
                val destFile = File(filePath)
                destFile.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val doc = knowledgeManager.documentRepository.create(
                knowledgeBaseId = baseId,
                fileName = fileName,
                fileType = fileType,
                filePath = filePath,
                fileSize = File(filePath).length(),
            )

            // Auto-process
            processDocument(doc.id, filePath, fileType)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun processDocument(documentId: String, filePath: String, fileType: String) {
        _processingState.value = _processingState.value + (documentId to 0f)
        knowledgeManager.documentRepository.updateStatus(documentId, "processing")

        try {
            // Check file size (limit to 50MB)
            val file = File(filePath)
            val fileSizeMB = file.length() / (1024 * 1024)
            if (fileSizeMB > 50) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文件过大 (${fileSizeMB}MB > 50MB)")
                return
            }

            // 1. Parse document
            val text = withContext(Dispatchers.IO) { parseDocument(filePath, fileType) }
            if (text.isBlank()) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文档内容为空或解析失败")
                return
            }
            // Check parsed text size to avoid OOM
            if (text.length > 500_000) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文档文本过长 (${text.length} 字符)，请分割后导入")
                return
            }
            _processingState.value = _processingState.value + (documentId to 0.1f)

            // 2. Get chunk config
            val base = base.value ?: return
            val chunkSize = base.chunkSize
            val chunkOverlap = base.chunkOverlap
            val chunkStrategy = base.chunkStrategy

            // 3. Chunk
            val chunker = when (chunkStrategy) {
                "paragraph" -> ParagraphChunker()
                "sentence" -> SentenceChunker()
                else -> FixedSizeChunker()
            }
            val chunks = chunker.chunk(text, chunkSize, chunkOverlap)
            if (chunks.isEmpty()) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "No chunks")
                return
            }
            _processingState.value = _processingState.value + (documentId to 0.3f)

            // 4. Resolve embedding model
            val settings = settingsStore.settingsFlow.value
            val modelId = base.embeddingModelId?.let { Uuid.parse(it) } ?: settings.embeddingModelId
            if (modelId == null) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "No embedding model configured")
                return
            }
            val model = settings.findModelById(modelId) ?: run {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "Embedding model not found")
                return
            }
            val providerSetting = model.findProvider(settings.providers) ?: run {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "Provider not found")
                return
            }
            if (providerSetting !is ProviderSetting.OpenAI) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "Only OpenAI-compatible providers support embedding")
                return
            }

            @Suppress("UNCHECKED_CAST")
            val provider = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting.OpenAI>

            // 5. Generate embeddings in batches
            val batchSize = 20
            val totalBatches = (chunks.size + batchSize - 1) / batchSize
            var processedCount = 0

            for (batchIndex in 0 until totalBatches) {
                val start = batchIndex * batchSize
                val end = minOf(start + batchSize, chunks.size)
                val batch = chunks.subList(start, end)

                val result = provider.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = batch.map { it.content },
                    )
                )

                val chunkEntities = batch.mapIndexed { i, chunk ->
                    val embedding = result.embeddings.getOrNull(i)
                    KnowledgeChunkEntity(
                        id = Uuid.random().toString(),
                        documentId = documentId,
                        knowledgeBaseId = baseId,
                        chunkIndex = processedCount + i,
                        content = chunk.content,
                        embedding = embedding?.toFloatArray()?.toByteArray(),
                        tokenCount = chunk.tokenCount,
                    )
                }

                knowledgeManager.chunkDao.insertAll(chunkEntities)
                processedCount += batch.size
                _processingState.value = _processingState.value + (documentId to 0.3f + 0.6f * (processedCount.toFloat() / chunks.size))
            }

            knowledgeManager.documentRepository.updateChunkCount(documentId, chunks.size, "completed")
        } catch (e: Exception) {
            knowledgeManager.documentRepository.updateStatus(documentId, "failed", e.message ?: "Unknown error")
        } finally {
            _processingState.value = _processingState.value - documentId
        }
    }

    private fun parseDocument(filePath: String, fileType: String): String {
        val file = File(filePath)
        if (!file.exists()) return ""
        return try {
            when (fileType) {
                "pdf" -> PdfParser.parserPdf(file)
                "docx" -> DocxParser.parse(file)
                "pptx" -> PptxParser.parse(file)
                "epub" -> EpubParser.parse(file)
                "xlsx" -> XlsxParser.parse(file)
                    "xls" -> "旧版 .xls 格式暂不支持，请在 Excel 中另存为 .xlsx 格式"
                "csv", "txt", "md", "markdown", "json", "xml", "html", "htm" -> file.readText()
                else -> file.readText()
            }
        } catch (e: Exception) {
            "解析失败: ${e.message}"
        }
    }

    fun searchTest(query: String) {
        viewModelScope.launch {
            _searchLoading.value = true
            _searchResults.value = emptyList()
            try {
                val base = this@KnowledgeBaseDetailVM.base.value
                if (base == null) {
                    _searchResults.value = emptyList()
                    return@launch
                }

                val settings = settingsStore.settingsFlow.value

                // Resolve embedding model
                val embModelId = base.embeddingModelId?.let { Uuid.parse(it) } ?: settings.embeddingModelId
                val queryEmbedding: FloatArray? = if (embModelId != null) {
                    val model = settings.findModelById(embModelId)
                    val providerSetting = model?.findProvider(settings.providers)
                    if (model != null && providerSetting is ProviderSetting.OpenAI) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val provider = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting.OpenAI>
                            val result = provider.generateEmbedding(
                                providerSetting = providerSetting,
                                params = EmbeddingGenerationParams(model = model, input = listOf(query))
                            )
                            result.embeddings.firstOrNull()?.toFloatArray()
                        } catch (e: Exception) { null }
                    } else null
                } else null

                // Resolve reranker if configured
                val rerankModelId = base.rerankModelId?.let { Uuid.parse(it) } ?: settings.rerankModelId
                val reranker = if (rerankModelId != null) {
                    val rerankModel = settings.findModelById(rerankModelId)
                    val rerankProviderSetting = rerankModel?.findProvider(settings.providers)
                    if (rerankModel != null && rerankProviderSetting is ProviderSetting.OpenAI) {
                        @Suppress("UNCHECKED_CAST")
                        val rerankProvider = providerManager.getProviderByType(rerankProviderSetting) as Provider<ProviderSetting.OpenAI>
                        Reranker(rerankProvider, rerankProviderSetting, rerankModel)
                    } else null
                } else null

                val results = knowledgeManager.search(
                    query = query,
                    queryEmbedding = queryEmbedding,
                    knowledgeBaseId = baseId,
                    topK = base.topK,
                    similarityThreshold = base.similarityThreshold,
                    reranker = reranker,
                )
                _searchResults.value = results
            } catch (e: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _searchLoading.value = false
            }
        }
    }

    fun retryDocument(id: String) {
        viewModelScope.launch {
            val doc = knowledgeManager.documentRepository.getById(id) ?: return@launch
            // Delete old chunks
            knowledgeManager.chunkDao.deleteByDocumentId(id)
            knowledgeManager.documentRepository.updateChunkCount(id, 0, "pending")
            // Re-process
            processDocument(doc.id, doc.filePath, doc.fileType)
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            knowledgeManager.documentRepository.delete(id)
        }
    }
}