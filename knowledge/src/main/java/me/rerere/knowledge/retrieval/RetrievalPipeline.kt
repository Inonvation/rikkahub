package me.rerere.knowledge.retrieval

import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RerankingGenerationParams
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.vector.VectorStore
import me.rerere.knowledge.vector.SearchResult as VectorSearchResult

data class RetrievalResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
    val scoreKind: String, // "relevance" (rerank score) or "ranking" (RRF score)
)

class Reranker(
    private val provider: Provider<ProviderSetting.OpenAI>,
    private val providerSetting: ProviderSetting.OpenAI,
    private val model: me.rerere.ai.provider.Model,
) {
    suspend fun rerank(query: String, candidates: List<RetrievalResult>, topN: Int): List<RetrievalResult> {
        if (candidates.isEmpty()) return emptyList()
        try {
            val result = provider.rerank(
                providerSetting = providerSetting,
                params = RerankingGenerationParams(
                    model = model,
                    query = query,
                    documents = candidates.map { it.chunk.content },
                    topN = topN,
                )
            )
            val scored = result.results.associateBy { it.index }
            return candidates.mapIndexed { index, r ->
                val rerankScore = scored[index]?.relevanceScore
                if (rerankScore != null) {
                    r.copy(score = rerankScore, scoreKind = "relevance")
                } else r
            }.sortedByDescending { it.score }
        } catch (e: Exception) {
            // Rerank failed, fall back to original order
            return candidates
        }
    }
}

class RetrievalPipeline(
    private val chunkDao: KnowledgeChunkDao,
    private val vectorStore: VectorStore,
    private val bm25Searcher: Bm25Searcher,
) {
    /**
     * Cherry Studio 风格的检索:
     * 1. Over-fetch (topK × 3) 候选
     * 2. Vector + BM25 → RRF fusion
     * 3. 可选 reranking
     * 4. Threshold 过滤
     * 5. 裁剪到 topK 并排序
     */
    suspend fun search(
        query: String,
        queryEmbedding: FloatArray?,
        knowledgeBaseId: String,
        topK: Int = 10,
        similarityThreshold: Float = 0f,
        reranker: Reranker? = null,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)

        // 并行执行 vector search 和 BM25 search (over-fetch)
        val vectorResults = if (queryEmbedding != null) {
            vectorStore.search(queryEmbedding, knowledgeBaseId, candidateLimit)
        } else {
            emptyList()
        }

        val bm25Results = bm25Searcher.search(query, knowledgeBaseId, candidateLimit)

        // RRF 融合
        val fused = rrfFusion(vectorResults, bm25Results)

        // 可选 reranking
        val results = if (reranker != null) {
            reranker.rerank(query, fused.take(candidateLimit), topK)
        } else {
            fused
        }

        // Threshold 过滤 + 裁剪
        return results
            .filter { result ->
                // ranking 分数不过滤(来自 RRF), relevance 分数按阈值过滤
                if (result.scoreKind == "relevance") {
                    result.score >= similarityThreshold
                } else true
            }
            .take(topK)
            .mapIndexed { index, it ->
                it.copy(rank = index + 1)
            }
    }

    private fun rrfFusion(
        vectorResults: List<VectorSearchResult>,
        bm25Results: List<Bm25SearchResult>,
        k: Int = 60,
    ): List<RetrievalResult> {
        val scores = mutableMapOf<String, Pair<KnowledgeChunkEntity, Float>>()

        vectorResults.forEachIndexed { index, result ->
            val rrfScore = 1f / (k + index + 1)
            val existing = scores[result.chunk.id]
            if (existing == null) {
                scores[result.chunk.id] = result.chunk to rrfScore
            } else {
                scores[result.chunk.id] = existing.copy(second = existing.second + rrfScore)
            }
        }

        bm25Results.forEachIndexed { index, result ->
            val rrfScore = 1f / (k + index + 1)
            val existing = scores[result.chunk.id]
            if (existing == null) {
                scores[result.chunk.id] = result.chunk to rrfScore
            } else {
                scores[result.chunk.id] = existing.copy(second = existing.second + rrfScore)
            }
        }

        return scores.entries
            .sortedByDescending { it.value.second }
            .map { (_, pair) ->
                RetrievalResult(
                    chunk = pair.first,
                    score = pair.second,
                    rank = 0,
                    scoreKind = "ranking",
                )
            }
    }
}