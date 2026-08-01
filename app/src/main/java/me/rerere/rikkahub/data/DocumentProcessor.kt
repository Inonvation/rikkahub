package me.rerere.rikkahub.data

import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.chunking.Chunk
import me.rerere.knowledge.chunking.FixedSizeChunker
import me.rerere.knowledge.chunking.ParagraphChunker
import me.rerere.knowledge.chunking.SentenceChunker
import me.rerere.knowledge.chunking.SemanticChunker
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.vector.toByteArray
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.fts.KnowledgeChunkFtsManager
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 文档处理引擎：解析 → 分块 → 生成 embedding → 入库。
 * 供知识库详情页（单文档处理）和设置页（分块改动后全库重处理）共用。
 */
class DocumentProcessor(
    private val knowledgeManager: KnowledgeManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val ftsManager: KnowledgeChunkFtsManager,
    private val baseId: String,
) {
    /**
     * 处理单个文档。onProgress 报告 0..1 进度。
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun processDocument(
        documentId: String,
        filePath: String,
        fileType: String,
        onProgress: (Float) -> Unit = {},
    ) {
        onProgress(0f)
        knowledgeManager.documentRepository.updateStatus(documentId, "processing")

        try {
            // Check file size (limit to 100MB)
            val file = File(filePath)
            val fileSizeMB = file.length() / (1024 * 1024)
            if (fileSizeMB > 100) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文件过大 (${fileSizeMB}MB > 100MB)")
                return
            }

            // 计算文件 hash，同库重复文档做覆盖处理
            val fileHash = computeFileHash(file)
            val duplicate = fileHash?.let { hash ->
                knowledgeManager.documentRepository.getByFileHashAndKnowledgeBaseId(hash, baseId)
                    ?.takeIf { it.id != documentId }
            }
            if (duplicate != null) {
                // 删除旧文档的 chunk 和记录，当前文档继续处理并继承 hash
                knowledgeManager.chunkDao.deleteByDocumentId(duplicate.id)
                knowledgeManager.documentRepository.delete(duplicate.id)
                knowledgeManager.invalidateVectorCache(baseId)
            }
            knowledgeManager.documentRepository.getById(documentId)?.let { doc ->
                knowledgeManager.documentRepository.update(doc.copy(fileHash = fileHash))
            }

            // 1. Parse document
            val text = withContext(Dispatchers.IO) { parseDocument(filePath, fileType) }
            if (text.isBlank()) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文档内容为空或解析失败")
                return
            }
            // Check parsed text size to avoid OOM
            if (text.length > 1_000_000) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "文档文本过长 (${text.length} 字符)，请分割后导入")
                return
            }
            onProgress(0.1f)

            // 2. Get chunk config
            val base = knowledgeManager.baseRepository.getById(baseId) ?: return
            val chunkSize = base.chunkSize
            val chunkOverlap = base.chunkOverlap
            val chunkStrategy = base.chunkStrategy

            // 3. Chunk
            val chunker = when (chunkStrategy) {
                "paragraph" -> ParagraphChunker()
                "sentence" -> SentenceChunker()
                "semantic" -> SemanticChunker()
                else -> FixedSizeChunker()
            }
            val chunks = chunker.chunk(text, chunkSize, chunkOverlap)
            if (chunks.isEmpty()) {
                knowledgeManager.documentRepository.updateStatus(documentId, "failed", "No chunks")
                return
            }

            // Small-to-Big：如果配置了 parent_chunk_size，先创建父块再在各父块内创建子块
            val parentChunkSize = base.parentChunkSize
            val useSmallToBig = parentChunkSize > 0 && parentChunkSize > chunkSize
            val parentChunks: List<Chunk> = if (useSmallToBig) {
                chunker.chunk(text, parentChunkSize, chunkOverlap.coerceAtMost(parentChunkSize / 10))
            } else {
                emptyList()
            }

            // 3.5 提取文档标题和章节结构，生成上下文前缀（Contextual Chunking）
            val docTitle = knowledgeManager.documentRepository.getById(documentId)?.fileName
                ?.substringBeforeLast(".") ?: ""
            val sectionHeaders = extractSectionHeaders(text)
            onProgress(0.3f)

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
            val batchSize = 10
            var processedCount = 0

            // 为每个 chunk 生成上下文前缀（Contextual Chunking）
            val chunkContexts = chunks.map { chunk ->
                val section = findSectionForChunk(chunk.content, text, sectionHeaders)
                buildContextPrefix(docTitle, section)
            }

            // Small-to-Big：先存父块（无 embedding），再存子块（有 embedding，关联父块）
            if (useSmallToBig) {
                // 构建父块 id 映射：子块 → 父块
                val childToParentMap = buildChildToParentMap(chunks, parentChunks)
                val parentEntities = mutableMapOf<String, String>() // content hash → entity id

                // 先插入父块
                parentChunks.forEachIndexed { index, parent ->
                    val parentId = Uuid.random().toString()
                    parentEntities[parent.content] = parentId
                    knowledgeManager.chunkDao.insertAll(listOf(
                        KnowledgeChunkEntity(
                            id = parentId,
                            documentId = documentId,
                            knowledgeBaseId = baseId,
                            chunkIndex = index,
                            content = parent.content,
                            embedding = null,
                            tokenCount = parent.tokenCount,
                            contextPrefix = "",
                        )
                    ))
                }

                // 再插入子块（带 embedding）
                val totalBatches = (chunks.size + batchSize - 1) / batchSize
                for (batchIndex in 0 until totalBatches) {
                    val start = batchIndex * batchSize
                    val end = minOf(start + batchSize, chunks.size)
                    val batch = chunks.subList(start, end)
                    val contextBatch = chunkContexts.subList(start, end)

                    val embeddingInputs = batch.mapIndexed { i, chunk ->
                        val prefix = contextBatch[i]
                        if (prefix.isNotEmpty()) "$prefix\n\n${chunk.content}" else chunk.content
                    }

                    val result = provider.generateEmbedding(
                        providerSetting = providerSetting,
                        params = EmbeddingGenerationParams(
                            model = model,
                            input = embeddingInputs,
                        )
                    )

                    val chunkEntities = batch.mapIndexed { i, chunk ->
                        val embeddingBytes = result.embeddings.getOrNull(i)?.toFloatArray()?.toByteArray()
                        val parentId = childToParentMap[chunk.content]
                        KnowledgeChunkEntity(
                            id = Uuid.random().toString(),
                            documentId = documentId,
                            knowledgeBaseId = baseId,
                            chunkIndex = processedCount + i,
                            content = chunk.content,
                            embedding = embeddingBytes,
                            tokenCount = chunk.tokenCount,
                            contextPrefix = contextBatch[i],
                            parentChunkId = parentId,
                        )
                    }

                    knowledgeManager.chunkDao.insertAll(chunkEntities)
                    processedCount += batch.size
                    onProgress(0.3f + 0.6f * (processedCount.toFloat() / chunks.size))
                }
            } else {
                // 普通模式：直接嵌入子块
                val totalBatches = (chunks.size + batchSize - 1) / batchSize
                for (batchIndex in 0 until totalBatches) {
                    val start = batchIndex * batchSize
                    val end = minOf(start + batchSize, chunks.size)
                    val batch = chunks.subList(start, end)
                    val contextBatch = chunkContexts.subList(start, end)

                    val embeddingInputs = batch.mapIndexed { i, chunk ->
                        val prefix = contextBatch[i]
                        if (prefix.isNotEmpty()) "$prefix\n\n${chunk.content}" else chunk.content
                    }

                    val result = provider.generateEmbedding(
                        providerSetting = providerSetting,
                        params = EmbeddingGenerationParams(
                            model = model,
                            input = embeddingInputs,
                        )
                    )

                    val chunkEntities = batch.mapIndexed { i, chunk ->
                        val embeddingBytes = result.embeddings.getOrNull(i)?.toFloatArray()?.toByteArray()
                        KnowledgeChunkEntity(
                            id = Uuid.random().toString(),
                            documentId = documentId,
                            knowledgeBaseId = baseId,
                            chunkIndex = processedCount + i,
                            content = chunk.content,
                            embedding = embeddingBytes,
                            tokenCount = chunk.tokenCount,
                            contextPrefix = contextBatch[i],
                        )
                    }

                    knowledgeManager.chunkDao.insertAll(chunkEntities)
                    processedCount += batch.size
                    onProgress(0.3f + 0.6f * (processedCount.toFloat() / chunks.size))
                }
            }

            knowledgeManager.documentRepository.updateChunkCount(documentId, chunks.size, "completed")
            // 文档索引变更后，清除该知识库的向量缓存，避免检索命中旧数据
            knowledgeManager.invalidateVectorCache(baseId)
            // 强制重建 FTS 索引，确保新增/更新内容立即可检索
            rebuildFtsIndex()
        } catch (e: OutOfMemoryError) {
            // OOM 是 Error 不是 Exception，需单独捕获：标记失败而非崩溃
            Log.e(TAG, "processDocument OOM: $documentId", e)
            knowledgeManager.documentRepository.updateStatus(documentId, "failed", "内存不足，请将文档分割后再导入")
        } catch (e: CancellationException) {
            // 协程取消是正常控制流，不能误判为文档失败（否则会写入 "job was cancelled"）
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "processDocument failed: $documentId", e)
            knowledgeManager.documentRepository.updateStatus(documentId, "failed", e.message ?: "Unknown error")
        } finally {
            // 所有路径（成功/失败/提前返回）都报告完成，让调用方清除进度
            onProgress(1f)
        }
    }

    /**
     * 删除旧 chunk 并重新处理单个文档（重试语义）。
     */
    suspend fun reprocessDocument(
        documentId: String,
        onProgress: (Float) -> Unit = {},
    ) {
        val doc = knowledgeManager.documentRepository.getById(documentId) ?: return
        knowledgeManager.chunkDao.deleteByDocumentId(documentId)
        // 删除旧索引后立即失效缓存，避免中间状态被命中
        knowledgeManager.invalidateVectorCache(baseId)
        knowledgeManager.documentRepository.updateChunkCount(documentId, 0, "pending")
        processDocument(doc.id, doc.filePath, doc.fileType, onProgress)
    }

    /**
     * 重新处理某知识库下全部文档（分块设置改动后调用）。
     * onDocumentProgress 报告 (documentId, 0..1) 进度。
     */
    suspend fun reprocessAll(
        onDocumentProgress: (documentId: String, progress: Float) -> Unit = { _, _ -> },
    ) {
        val docs = knowledgeManager.documentRepository.getByKnowledgeBaseIdList(baseId)
        if (docs.isEmpty()) return
        docs.forEach { doc ->
            knowledgeManager.chunkDao.deleteByDocumentId(doc.id)
            // 每删除一个文档的索引就失效缓存，避免中间状态被命中
            knowledgeManager.invalidateVectorCache(baseId)
            knowledgeManager.documentRepository.updateChunkCount(doc.id, 0, "pending")
            processDocument(doc.id, doc.filePath, doc.fileType) { p ->
                onDocumentProgress(doc.id, p)
            }
        }
    }

    private suspend fun parseDocument(filePath: String, fileType: String): String {
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
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" ->
                    ocrImage(file)
                "csv", "txt", "md", "markdown", "json", "xml", "html", "htm" -> file.readText()
                else -> {
                    // 未知类型：尝试按文本读，若含大量二进制控制字符则拒绝，避免 OOM
                    if (isBinaryFile(file)) {
                        "不支持的二进制文件类型 (.$fileType)，仅支持文本类、Office/PDF 文档和图片"
                    } else {
                        file.readText()
                    }
                }
            }
        } catch (e: Exception) {
            "解析失败: ${e.message}"
        }
    }

    /**
     * 用配置的 OCR 模型识别图片文本。未配置 OCR 模型时返回提示文本，而不是让下游 OOM。
     */
    private suspend fun ocrImage(file: File): String {
        val url = "file://${file.absolutePath}"
        val result = OcrTransformer.performOcr(UIMessagePart.Image(url))
        // 跳过设备无关的封装标签，只保留识别到的正文
        val cleaned = result
            .removePrefix("<image_file_ocr>")
            .removeSuffix("</image_file_ocr>")
            .removePrefix("* The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.")
            .trim()
        return if (cleaned.isBlank()) "图片中未识别到文本" else cleaned
    }

    /**
     * 计算文件 SHA-256 哈希的十六进制字符串，用于同库去重。
     */
    private fun computeFileHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 快速判断是否为二进制文件：采样前 8KB，若含多个 NUL 或非打印控制字符则视为二进制。
     */
    private fun isBinaryFile(file: File): Boolean {
        try {
            file.inputStream().use { input ->
                val bytes = ByteArray(8192)
                val read = input.read(bytes)
                if (read <= 0) return false
                var controls = 0
                for (i in 0 until read) {
                    val b = bytes[i].toInt() and 0xFF
                    if (b == 0x00 || (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D)) controls++
                }
                return controls > read / 10
            }
        } catch (_: Exception) {
            return false
        }
    }

    private companion object {
        const val TAG = "DocumentProcessor"

        /**
         * 从文档文本中提取章节标题，用于 Contextual Chunking。
         * 识别 Markdown 标题 (#)、中文章节标记（第X章/节）、以及编号标题（一、1.）。
         */
        private fun extractSectionHeaders(text: String): List<Pair<Int, String>> {
            val headers = mutableListOf<Pair<Int, String>>()
            val pattern = Regex(
                """(?:^|\n)\s*((?:#{1,3}\s+.+)|(?:第[一二三四五六七八九十百千\d]+[章节部篇]\s*.*)|(?:[一二三四五六七八九十]+[、，]\s*.+)|(?:\d+[\.\、]\s+.+))""",
                RegexOption.MULTILINE
            )
            pattern.findAll(text).forEach { match ->
                val header = match.groupValues[1].trim()
                if (header.length in 2..80) {
                    headers.add(match.range.first to header)
                }
            }
            return headers
        }

        /**
         * 为 chunk 找到所属的章节标题。
         */
        private fun findSectionForChunk(
            chunkContent: String,
            fullText: String,
            sectionHeaders: List<Pair<Int, String>>,
        ): String {
            if (sectionHeaders.isEmpty()) return ""
            // 在全文文本中查找 chunk 内容的位置
            val chunkPos = fullText.indexOf(chunkContent)
            if (chunkPos < 0) return ""
            // 找到 chunk 之前最近的章节标题
            val section = sectionHeaders.lastOrNull { it.first < chunkPos }
            return section?.second ?: ""
        }

        /**
         * 构建上下文前缀，格式：[文档: 《xxx》] [章节: xxx]
         */
        private fun buildContextPrefix(docTitle: String, section: String): String {
            if (docTitle.isBlank() && section.isBlank()) return ""
            val parts = mutableListOf<String>()
            if (docTitle.isNotBlank()) {
                parts.add("文档: 《$docTitle》")
            }
            if (section.isNotBlank()) {
                parts.add("章节: $section")
            }
            return parts.joinToString(" ")
        }

        /**
         * Small-to-Big：为每个子块找到包含它的父块。
         */
        private fun buildChildToParentMap(
            childChunks: List<Chunk>,
            parentChunks: List<Chunk>,
        ): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (child in childChunks) {
                // 在父块中查找包含该子块的父块
                val parent = parentChunks.find { it.content.contains(child.content) }
                if (parent != null) {
                    map[child.content] = parent.content
                }
            }
            return map
        }
    }

    private suspend fun rebuildFtsIndex() {
        if (ftsManager.ensureIndex()) {
            val chunks = knowledgeManager.chunkDao.getByKnowledgeBaseId(baseId)
            ftsManager.rebuildBase(baseId, chunks)
        }
    }
}

