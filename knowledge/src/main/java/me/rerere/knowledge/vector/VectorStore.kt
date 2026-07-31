package me.rerere.knowledge.vector

import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.vector.toFloatArray

data class SearchResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
)

class VectorStore(
    private val chunkDao: KnowledgeChunkDao,
) {
    suspend fun search(
        queryEmbedding: FloatArray,
        knowledgeBaseId: String,
        topK: Int = 10,
    ): List<SearchResult> {
        val chunks = chunkDao.getByKnowledgeBaseId(knowledgeBaseId)
        if (chunks.isEmpty()) return emptyList()

        val scored = chunks.mapNotNull { chunk ->
            val embedding = chunk.embedding ?: return@mapNotNull null
            val score = Similarity.cosineSimilarity(queryEmbedding, embedding.toFloatArray())
            chunk to score
        }

        return scored
            .sortedByDescending { it.second }
            .take(topK)
            .mapIndexed { index, (chunk, score) ->
                SearchResult(
                    chunk = chunk,
                    score = score,
                    rank = index + 1,
                )
            }
    }
}