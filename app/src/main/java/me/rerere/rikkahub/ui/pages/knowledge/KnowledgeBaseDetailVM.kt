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
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.retrieval.RetrievalResult
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.rikkahub.data.DocumentProcessor
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.io.File

// 知识库支持导入的扩展名白名单（与 parseDocument 各分支对应）
private val SUPPORTED_EXTENSIONS = setOf(
    // 文档
    "pdf", "docx", "pptx", "epub", "xlsx",
    // 纯文本 / 标记
    "txt", "csv", "md", "markdown", "json", "xml", "html", "htm",
    // 图片（走 OCR）
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
)

private val SUPPORTED_FORMATS_TEXT = buildString {
    append("支持导入：")
    append("文档(PDF/DOCX/PPTX/EPUB/XLSX)、")
    append("文本(TXT/CSV/MD/JSON/XML/HTML)、")
    append("图片(JPG/PNG/GIF/WEBP/BMP，走 OCR 识别)")
}

class KnowledgeBaseDetailVM(
    private val knowledgeManager: KnowledgeManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val documentProcessor: DocumentProcessor,
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

    // 检索测试可调参数（默认值在首次搜索时用知识库设置填充）
    private val _searchTopK = MutableStateFlow(10)
    val searchTopK = _searchTopK.asStateFlow()

    private val _searchThreshold = MutableStateFlow(0f)
    val searchThreshold = _searchThreshold.asStateFlow()

    private val _searchRerankEnabled = MutableStateFlow(true)
    val searchRerankEnabled = _searchRerankEnabled.asStateFlow()

    // 关键词检索权重（RRF 融合时给关键词侧加权，1=等权）
    private val _searchKeywordWeight = MutableStateFlow(1f)
    val searchKeywordWeight = _searchKeywordWeight.asStateFlow()

    private val _searchDurationMs = MutableStateFlow<Long?>(null)
    val searchDurationMs = _searchDurationMs.asStateFlow()

    // chunkId -> 文档文件名，用于结果来源展示
    private val _documentNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val documentNames = _documentNames.asStateFlow()

    // 一次性消息通知（导入被拒绝等），由 UI 层 toast 展示
    private val _notices = MutableStateFlow<String?>(null)
    val notices = _notices.asStateFlow()

    private var searchParamsInitialized = false

    fun updateSearchTopK(v: Int) { _searchTopK.value = v }
    fun updateSearchThreshold(v: Float) { _searchThreshold.value = v }
    fun updateSearchRerankEnabled(v: Boolean) { _searchRerankEnabled.value = v }
    fun updateSearchKeywordWeight(v: Float) { _searchKeywordWeight.value = v }

    fun consumeNotice() { _notices.value = null }

    fun addDocument(uri: Uri, context: Context) {
        viewModelScope.launch {
            val fileName = uri.lastPathSegment ?: "unknown"
            val fileType = fileName.substringAfterLast('.', "").lowercase()

            // 白名单校验：不支持的类型直接拒绝，不拷贝、不创建记录
            if (fileType !in SUPPORTED_EXTENSIONS) {
                _notices.value = "不支持的文件类型 .$fileType\n$SUPPORTED_FORMATS_TEXT"
                return@launch
            }
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
            documentProcessor.processDocument(doc.id, filePath, fileType) { progress ->
                if (progress >= 1f) {
                    // 处理结束，清除进度条目（避免残留）
                    _processingState.value = _processingState.value - doc.id
                } else {
                    _processingState.value = _processingState.value + (doc.id to progress)
                }
            }
        }
    }

    fun retryDocument(id: String) {
        viewModelScope.launch {
            documentProcessor.reprocessDocument(id) { progress ->
                if (progress >= 1f) {
                    _processingState.value = _processingState.value - id
                } else {
                    _processingState.value = _processingState.value + (id to progress)
                }
            }
        }
    }

    fun searchTest(query: String) {
        viewModelScope.launch {
            _searchLoading.value = true
            _searchDurationMs.value = null
            _searchResults.value = emptyList()
            try {
                val base = this@KnowledgeBaseDetailVM.base.value
                if (base == null) {
                    _searchResults.value = emptyList()
                    return@launch
                }

                val settings = settingsStore.settingsFlow.value

                // 首次搜索时，用知识库配置初始化可调参数
                if (!searchParamsInitialized) {
                    _searchTopK.value = base.topK
                    _searchThreshold.value = base.similarityThreshold
                    _searchRerankEnabled.value = base.rerankModelId != null
                    searchParamsInitialized = true
                }

                val startTime = System.nanoTime()

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

                // Resolve reranker（仅当开关开启）
                val reranker = if (_searchRerankEnabled.value) {
                    val rerankModelId = base.rerankModelId?.let { Uuid.parse(it) } ?: settings.rerankModelId
                    if (rerankModelId != null) {
                        val rerankModel = settings.findModelById(rerankModelId)
                        val rerankProviderSetting = rerankModel?.findProvider(settings.providers)
                        if (rerankModel != null && rerankProviderSetting is ProviderSetting.OpenAI) {
                            @Suppress("UNCHECKED_CAST")
                            val rerankProvider = providerManager.getProviderByType(rerankProviderSetting) as Provider<ProviderSetting.OpenAI>
                            Reranker(rerankProvider, rerankProviderSetting, rerankModel)
                        } else null
                    } else null
                } else null

                val results = knowledgeManager.search(
                    query = query,
                    queryEmbedding = queryEmbedding,
                    knowledgeBaseId = baseId,
                    topK = _searchTopK.value,
                    similarityThreshold = _searchThreshold.value,
                    reranker = reranker,
                    keywordWeight = _searchKeywordWeight.value,
                )
                _searchResults.value = results

                // 构建 chunkId -> 文档文件名 映射（用于结果来源展示）
                val chunkIds = results.map { it.chunk.id }.toSet()
                if (chunkIds.isNotEmpty()) {
                    _documentNames.value = knowledgeManager.chunkDao
                        .getDocumentNamesByChunkIds(chunkIds.toList())
                        .associate { it.chunkId to it.fileName }
                }

                _searchDurationMs.value = (System.nanoTime() - startTime) / 1_000_000
            } catch (e: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _searchLoading.value = false
            }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            knowledgeManager.documentRepository.delete(id)
        }
    }
}