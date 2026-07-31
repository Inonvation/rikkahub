package me.rerere.knowledge

import me.rerere.knowledge.data.dao.KnowledgeBaseDao
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.data.dao.KnowledgeDocumentDao
import me.rerere.knowledge.data.repository.KnowledgeBaseRepository
import me.rerere.knowledge.data.repository.KnowledgeDocumentRepository
import me.rerere.knowledge.retrieval.Bm25Searcher
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.retrieval.RetrievalPipeline
import me.rerere.knowledge.retrieval.RetrievalResult
import me.rerere.knowledge.vector.VectorStore

class KnowledgeManager(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val knowledgeDocumentDao: KnowledgeDocumentDao,
    val chunkDao: KnowledgeChunkDao,
) {
    val baseRepository = KnowledgeBaseRepository(knowledgeBaseDao)
    val documentRepository = KnowledgeDocumentRepository(knowledgeDocumentDao)

    private val vectorStore = VectorStore(chunkDao)
    private val bm25Searcher = Bm25Searcher(chunkDao)
    private val retrievalPipeline = RetrievalPipeline(
        chunkDao = chunkDao,
        vectorStore = vectorStore,
        bm25Searcher = bm25Searcher,
    )

    suspend fun search(
        query: String,
        queryEmbedding: FloatArray?,
        knowledgeBaseId: String,
        topK: Int = 10,
        similarityThreshold: Float = 0f,
        reranker: Reranker? = null,
    ): List<RetrievalResult> {
        return retrievalPipeline.search(
            query = query,
            queryEmbedding = queryEmbedding,
            knowledgeBaseId = knowledgeBaseId,
            topK = topK,
            similarityThreshold = similarityThreshold,
            reranker = reranker,
        )
    }
}