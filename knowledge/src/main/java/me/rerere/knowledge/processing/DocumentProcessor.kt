package me.rerere.knowledge.processing

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.knowledge.chunking.FixedSizeChunker
import me.rerere.knowledge.chunking.ParagraphChunker
import me.rerere.knowledge.chunking.SentenceChunker
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.data.dao.KnowledgeDocumentDao
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.vector.toByteArray

class DocumentProcessor(
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun process(
        documentId: String,
        knowledgeBaseId: String,
        text: String,
        chunkSize: Int,
        chunkOverlap: Int,
        chunkStrategy: String,
        provider: Provider<ProviderSetting.OpenAI>,
        providerSetting: ProviderSetting.OpenAI,
        embeddingModel: me.rerere.ai.provider.Model,
        onProgress: (Float) -> Unit = {},
    ) {
        if (text.isBlank()) {
            documentDao.updateStatus(documentId, "failed", "Document is empty or could not be parsed")
            return
        }

        // Update status to processing
        documentDao.updateStatus(documentId, "processing")
        onProgress(0.1f)

        // Chunk
        val chunker = when (chunkStrategy) {
            "paragraph" -> ParagraphChunker()
            "sentence" -> SentenceChunker()
            else -> FixedSizeChunker()
        }
        val chunks = chunker.chunk(text, chunkSize, chunkOverlap)
        if (chunks.isEmpty()) {
            documentDao.updateStatus(documentId, "failed", "No chunks generated")
            return
        }
        onProgress(0.3f)

        // Generate embeddings in batches
        val batchSize = 20
        val totalBatches = (chunks.size + batchSize - 1) / batchSize
        var processedCount = 0

        for (batchIndex in 0 until totalBatches) {
            val start = batchIndex * batchSize
            val end = minOf(start + batchSize, chunks.size)
            val batch = chunks.subList(start, end)

            try {
                val result = provider.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = embeddingModel,
                        input = batch.map { it.content },
                    )
                )

                val chunkEntities = batch.mapIndexed { i, chunk ->
                    val embedding = result.embeddings.getOrNull(i)
                    KnowledgeChunkEntity(
                        id = Uuid.random().toString(),
                        documentId = documentId,
                        knowledgeBaseId = knowledgeBaseId,
                        chunkIndex = processedCount + i,
                        content = chunk.content,
                        embedding = embedding?.toFloatArray()?.toByteArray(),
                        tokenCount = chunk.tokenCount,
                    )
                }

                chunkDao.insertAll(chunkEntities)
                processedCount += batch.size
                onProgress(0.3f + 0.6f * (processedCount.toFloat() / chunks.size))
            } catch (e: Exception) {
                documentDao.updateStatus(documentId, "failed", "Embedding failed: ${e.message}")
                return
            }
        }

        // Update document status
        documentDao.updateChunkCount(documentId, chunks.size, "completed")
        onProgress(1.0f)
    }
}