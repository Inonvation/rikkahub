package me.rerere.knowledge.retrieval

import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RerankingGenerationParams
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.vector.VectorStore
import me.rerere.knowledge.vector.SearchResult as VectorSearchResult
import me.rerere.knowledge.vector.Similarity

data class RetrievalResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
    val scoreKind: String, // "relevance" (rerank score) or "ranking" (RRF score)
    val snippet: String? = null, // 命中片段（带 [..] 高亮标记），用于 UI 展示
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
            return candidates
        }
    }
}

class RetrievalPipeline(
    private val chunkDao: KnowledgeChunkDao,
    private val vectorStore: VectorStore,
    private val bm25Searcher: Bm25Searcher,
    private val keywordSearcher: KeywordSearcher? = null,
) {
    /**
     * Hybrid 检索：Vector + Keyword → RRF 融合 → MMR → 可选 reranking → threshold → topK
     */
    suspend fun search(
        query: String,
        queryEmbedding: FloatArray?,
        knowledgeBaseId: String,
        topK: Int = 10,
        similarityThreshold: Float = 0f,
        reranker: Reranker? = null,
        keywordWeight: Float = 1f,
        mmrLambda: Float = 0.7f,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)

        val (vectorResults, keywordResults) = coroutineScope {
            val vectorDeferred = async {
                if (queryEmbedding != null) {
                    vectorStore.search(queryEmbedding, knowledgeBaseId, candidateLimit)
                } else {
                    emptyList()
                }
            }
            val keywordDeferred = async {
                keywordSearcher
                    ?.let { searcher ->
                        runCatching { searcher.search(query, knowledgeBaseId, candidateLimit) }.getOrNull()
                    }
                    ?: bm25Searcher.search(query, knowledgeBaseId, candidateLimit)
                        .map { KeywordSearchResult(chunk = it.chunk, rank = it.rank) }
            }
            vectorDeferred.await() to keywordDeferred.await()
        }

        val fused = rrfFusion(vectorResults, keywordResults, keywordWeight = keywordWeight)

        val diversified = mmrDiversify(fused, knowledgeBaseId, queryEmbedding, candidateLimit, mmrLambda)

        val results = if (reranker != null) {
            reranker.rerank(query, diversified, topK)
        } else {
            diversified
        }

        return applyThreshold(results, similarityThreshold).take(topK)
            .mapIndexed { index, it -> it.copy(rank = index + 1) }
    }

    /**
     * 纯语义检索：仅用向量余弦相似度，不做关键词融合。
     */
    suspend fun semanticSearch(
        query: String,
        queryEmbedding: FloatArray,
        knowledgeBaseId: String,
        topK: Int = 10,
        similarityThreshold: Float = 0f,
        reranker: Reranker? = null,
        mmrLambda: Float = 0.7f,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)
        val vectorResults = vectorStore.search(queryEmbedding, knowledgeBaseId, candidateLimit)

        val results = vectorResults.map {
            RetrievalResult(
                chunk = it.chunk,
                score = it.score,
                rank = 0,
                scoreKind = "ranking",
            )
        }

        val diversified = mmrDiversify(results, knowledgeBaseId, queryEmbedding, candidateLimit, mmrLambda)

        val reranked = if (reranker != null) {
            reranker.rerank(query, diversified, topK)
        } else {
            diversified
        }

        return applyThreshold(reranked, similarityThreshold).take(topK)
            .mapIndexed { index, it -> it.copy(rank = index + 1) }
    }

    /**
     * 纯关键词检索：仅用 FTS5/BM25，不做向量融合。
     */
    suspend fun keywordSearch(
        query: String,
        knowledgeBaseId: String,
        topK: Int = 10,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)
        val keywordResults = keywordSearcher
            ?.let { searcher ->
                runCatching { searcher.search(query, knowledgeBaseId, candidateLimit) }.getOrNull()
            }
            ?: bm25Searcher.search(query, knowledgeBaseId, candidateLimit)
                .map { KeywordSearchResult(chunk = it.chunk, rank = it.rank) }

        return keywordResults
            .take(topK)
            .mapIndexed { index, result ->
                RetrievalResult(
                    chunk = result.chunk,
                    score = 1f / (1 + index),
                    rank = index + 1,
                    scoreKind = "ranking",
                    snippet = result.snippet,
                )
            }
    }

    private fun applyThreshold(
        results: List<RetrievalResult>,
        similarityThreshold: Float,
    ): List<RetrievalResult> {
        if (similarityThreshold <= 0f) return results
        return results.filter { result ->
            when (result.scoreKind) {
                "relevance" -> result.score >= similarityThreshold
                else -> {
                    val maxScore = results.maxOf { it.score }
                    if (maxScore > 0f) result.score >= similarityThreshold * maxScore
                    else true
                }
            }
        }
    }

    private fun rrfFusion(
        vectorResults: List<VectorSearchResult>,
        keywordResults: List<KeywordSearchResult>,
        k: Int = 60,
        keywordWeight: Float = 1f,
    ): List<RetrievalResult> {
        val scores = mutableMapOf<String, Pair<KnowledgeChunkEntity, Float>>()
        val snippets = mutableMapOf<String, String?>()

        vectorResults.forEachIndexed { index, result ->
            val rrfScore = 1f / (k + index + 1)
            val existing = scores[result.chunk.id]
            if (existing == null) {
                scores[result.chunk.id] = result.chunk to rrfScore
            } else {
                scores[result.chunk.id] = existing.copy(second = existing.second + rrfScore)
            }
        }

        keywordResults.forEachIndexed { index, result ->
            val rrfScore = 1f / (k + index + 1) * keywordWeight
            val existing = scores[result.chunk.id]
            if (existing == null) {
                scores[result.chunk.id] = result.chunk to rrfScore
            } else {
                scores[result.chunk.id] = existing.copy(second = existing.second + rrfScore)
            }
            if (result.snippet != null) {
                snippets[result.chunk.id] = result.snippet
            }
        }

        return scores.entries
            .sortedByDescending { it.value.second }
            .map { (id, pair) ->
                RetrievalResult(
                    chunk = pair.first,
                    score = pair.second,
                    rank = 0,
                    scoreKind = "ranking",
                    snippet = snippets[id],
                )
            }
    }

    /**
     * MMR（Maximal Marginal Relevance）多样性控制。
     * λ=1 纯相关性排序，λ=0 纯多样性（最小化与已选结果的相似度）。
     * 需要 chunk embedding 来计算 pairwise 相似度。
     */
    private fun mmrDiversify(
        results: List<RetrievalResult>,
        knowledgeBaseId: String,
        queryEmbedding: FloatArray?,
        maxResults: Int,
        lambda: Float,
    ): List<RetrievalResult> {
        if (results.size <= 1) return results

        val selected = mutableListOf<RetrievalResult>()
        val candidates = results.toMutableList()

        // 取第一个（最高分）作为种子
        selected.add(candidates.removeAt(0))

        while (candidates.isNotEmpty() && selected.size < maxResults) {
            var bestIdx = 0
            var bestScore = Float.NEGATIVE_INFINITY

            for (i in candidates.indices) {
                val candidate = candidates[i]
                val relevance = candidate.score

                // 计算与已选结果的最大相似度
                var maxSimilarity = 0f
                if (queryEmbedding != null) {
                    val candEmb = vectorStore.getEmbedding(knowledgeBaseId, candidate.chunk.id)
                    if (candEmb != null) {
                        for (s in selected) {
                            val selEmb = vectorStore.getEmbedding(knowledgeBaseId, s.chunk.id)
                            if (selEmb != null) {
                                val sim = Similarity.cosineSimilarity(candEmb, selEmb)
                                if (!sim.isNaN() && sim > maxSimilarity) {
                                    maxSimilarity = sim
                                }
                            }
                        }
                    }
                }

                val mmr = lambda * relevance - (1f - lambda) * maxSimilarity
                if (mmr > bestScore) {
                    bestScore = mmr
                    bestIdx = i
                }
            }

            selected.add(candidates.removeAt(bestIdx))
        }

        // 追加剩余候选
        selected.addAll(candidates)
        return selected.take(maxResults)
    }
}