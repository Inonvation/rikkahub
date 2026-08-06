package me.rerere.rikkahub.ui.pages.knowledge

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** 分路检索诊断：向量 / 关键词 / 融合 各自的命中数与耗时 */
    data class RouteDiagnostic(
        val route: String,
        val available: Boolean,
        val hitCount: Int,
        val durationMs: Long,
        val note: String? = null,
    )

    private val _routeDiagnostics = MutableStateFlow<List<RouteDiagnostic>>(emptyList())
    val routeDiagnostics = _routeDiagnostics.asStateFlow()

    // 检索错误信息（embedding 失败 / 检索异常等），UI 展示失败原因而非静默空列表
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()

    // 是否配置了 embedding 模型（决定检索是否含语义成分，纯关键词模式无阈值）
    private val _semanticAvailable = MutableStateFlow(false)
    val semanticAvailable = _semanticAvailable.asStateFlow()

    // chunkId -> 文档文件名，用于结果来源展示
    private val _documentNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val documentNames = _documentNames.asStateFlow()

    // 一次性消息通知（导入被拒绝等），由 UI 层 toast 展示
    private val _notices = MutableStateFlow<String?>(null)
    val notices = _notices.asStateFlow()

    // 文档导入处理结果（成功/失败），UI 层弹 toast
    data class ImportResult(val fileName: String, val success: Boolean, val message: String?)
    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    private var searchParamsInitialized = false

    fun updateSearchTopK(v: Int) { _searchTopK.value = v }
    fun updateSearchThreshold(v: Float) { _searchThreshold.value = v }
    fun updateSearchRerankEnabled(v: Boolean) { _searchRerankEnabled.value = v }
    fun updateSearchKeywordWeight(v: Float) { _searchKeywordWeight.value = v }

    fun consumeNotice() { _notices.value = null }
    fun consumeSearchError() { _searchError.value = null }
    fun consumeImportResult() { _importResult.value = null }

    fun addDocument(uri: Uri, context: Context) {
        viewModelScope.launch {
            // 优先用 contentResolver 查真实文件名（云盘/相册的 lastPathSegment 常是数字 ID）
            val fileName = queryDisplayName(uri, context)
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
                updateProgress(doc.id, progress)
            }

            // 处理后查最终状态，弹导入结果 toast
            val finalDoc = knowledgeManager.documentRepository.getById(doc.id)
            _importResult.value = when (finalDoc?.status) {
                "completed" -> ImportResult(fileName, success = true, message = null)
                "failed" -> ImportResult(fileName, success = false, message = finalDoc.error ?: "处理失败")
                else -> ImportResult(fileName, success = true, message = "已加入处理队列")
            }
        }
    }

    fun retryDocument(id: String) {
        viewModelScope.launch {
            documentProcessor.reprocessDocument(id) { progress ->
                updateProgress(id, progress)
            }
        }
    }

    private var reprocessAllJob: Job? = null

    /**
     * 按当前分块/模型设置重新处理全部文档（分块设置改动后手动触发）。
     */
    fun reprocessAll() {
        reprocessAllJob?.cancel()
        reprocessAllJob = viewModelScope.launch {
            documentProcessor.reprocessAll { docId, progress ->
                updateProgress(docId, progress)
            }
        }
    }

    /** 更新单文档处理进度；进度 >=1 表示处理结束，清除进度条目（避免残留）。 */
    private fun updateProgress(id: String, progress: Float) {
        _processingState.value = if (progress >= 1f) {
            _processingState.value - id
        } else {
            _processingState.value + (id to progress)
        }
    }

    fun searchTest(query: String) {
        viewModelScope.launch {
            _searchLoading.value = true
            _searchDurationMs.value = null
            _searchResults.value = emptyList()
            _routeDiagnostics.value = emptyList()
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
                    // rerank 开关与实际检索的模型解析保持一致：知识库没单独配时回退全局配置
                    _searchRerankEnabled.value = base.rerankModelId != null || settings.rerankModelId != null
                    searchParamsInitialized = true
                }

                val startTime = System.nanoTime()

                // Resolve embedding model
                val embModelId = base.embeddingModelId?.let { Uuid.parse(it) } ?: settings.embeddingModelId
                _semanticAvailable.value = embModelId != null
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

                // ---- 分路检索诊断（向量 / 关键词 / 融合）----
                val routeDiag = mutableListOf<RouteDiagnostic>()

                // 关键词路（FTS，总是可用）
                val kwStart = System.nanoTime()
                val kwResults = knowledgeManager.keywordSearch(
                    query = query,
                    knowledgeBaseId = baseId,
                    topK = _searchTopK.value,
                )
                routeDiag += RouteDiagnostic(
                    route = "关键词",
                    available = true,
                    hitCount = kwResults.size,
                    durationMs = (System.nanoTime() - kwStart) / 1_000_000,
                )

                // 向量路（需 embedding 模型）
                if (queryEmbedding != null) {
                    val semStart = System.nanoTime()
                    val semResults = knowledgeManager.semanticSearch(
                        query = query,
                        queryEmbedding = queryEmbedding,
                        knowledgeBaseId = baseId,
                        topK = _searchTopK.value,
                        similarityThreshold = _searchThreshold.value,
                        reranker = reranker,
                        mmrLambda = base.mmrLambda,
                    )
                    routeDiag += RouteDiagnostic(
                        route = "向量",
                        available = true,
                        hitCount = semResults.size,
                        durationMs = (System.nanoTime() - semStart) / 1_000_000,
                    )
                } else {
                    routeDiag += RouteDiagnostic(
                        route = "向量",
                        available = false,
                        hitCount = 0,
                        durationMs = 0,
                        note = "未配置 embedding 模型",
                    )
                }

                // 融合路（hybrid + 可选 rerank）
                val hyStart = System.nanoTime()
                val results = knowledgeManager.search(
                    query = query,
                    queryEmbedding = queryEmbedding,
                    knowledgeBaseId = baseId,
                    topK = _searchTopK.value,
                    similarityThreshold = _searchThreshold.value,
                    reranker = reranker,
                    keywordWeight = _searchKeywordWeight.value,
                    mmrLambda = base.mmrLambda,
                )
                routeDiag += RouteDiagnostic(
                    route = "融合",
                    available = true,
                    hitCount = results.size,
                    durationMs = (System.nanoTime() - hyStart) / 1_000_000,
                )

                _routeDiagnostics.value = routeDiag
                _searchResults.value = results

                // 构建 chunkId -> 文档文件名 映射（用于结果来源展示）
                val chunkIds = results.map { it.chunk.id }.toSet()
                if (chunkIds.isNotEmpty()) {
                    _documentNames.value = knowledgeManager.chunkDao
                        .getDocumentNamesByChunkIds(chunkIds.toList())
                        .associate { it.chunkId to it.fileName }
                }

                _searchDurationMs.value = (System.nanoTime() - startTime) / 1_000_000
                _searchError.value = null
            } catch (e: Exception) {
                _searchResults.value = emptyList()
                _routeDiagnostics.value = emptyList()
                _searchError.value = e.message ?: e.javaClass.simpleName
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

/**
 * 从 contentResolver 查询文档真实文件名（OpenableColumns.DISPLAY_NAME）。
 * 云盘 / 相册的 uri.lastPathSegment 常是数字 ID，不是真实文件名。
 * 查询失败或名字为空时回退到 lastPathSegment。
 */
private fun queryDisplayName(uri: Uri, context: Context): String {
    var name: String? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
    }
    val raw = name ?: uri.lastPathSegment ?: "unknown"
    // 去掉可能混入的路径前缀（部分 provider 返回带目录的名字）
    return raw.substringAfterLast('/').takeIf { it.isNotBlank() } ?: raw
}