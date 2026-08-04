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

/**
 * 检索分数来源。决定 UI 如何展示分数、阈值如何生效。
 */
enum class ScoreSource {
    /** Rerank 模型重排后的相关度（0~1）。 */
    RERANK,

    /** 纯语义检索的余弦相似度（0~1）。 */
    SEMANTIC,

    /** 混合检索（语义 + 关键词融合）。display 分数取语义相似度（0~1）。 */
    HYBRID,

    /** 纯关键词匹配（FTS5 BM25）。display 分数为相对分 1/(1+rank)，非真实相似度。 */
    KEYWORD,
}

data class RetrievalResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,               // 原始分（RRF 融合分 / 余弦相似度 / rerank 分），排序用
    val normalizedScore: Float,     // 统一 0~1 展示分，UI 用它显示"相似度 %"
    val scoreSource: ScoreSource,   // 分数来源
    val rank: Int,
    val snippet: String? = null,    // 命中片段（带 [..] 高亮标记），用于 UI 展示
    val matchCount: Int = 0,        // 关键词在该 chunk 内出现次数（仅 KEYWORD 来源有意义）
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
                    r.copy(
                        score = rerankScore,
                        normalizedScore = rerankScore,
                        scoreSource = ScoreSource.RERANK,
                    )
                } else r
            }.sortedByDescending { it.normalizedScore }
        } catch (e: Exception) {
            // rerank 失败：保留原有结果与分数，不让检索整体失败
            return candidates
        }
    }
}

class RetrievalPipeline(
    private val chunkDao: KnowledgeChunkDao,
    private val vectorStore: VectorStore,
    private val keywordSearcher: KeywordSearcher,
) {
    companion object {
        /**
         * 关键词分数归一化上限：chunk 内出现该次数及以上视为满分（1.0）。
         * 避免"出现 50 次"和"出现 3 次"的分数差异过大。
         */
        const val KEYWORD_SCORE_CEILING = 5f
    }
    /**
     * Hybrid 检索：Vector + Keyword → RRF 融合 → MMR → 可选 reranking → threshold → topK
     *
     * 未配置 embedding 模型（queryEmbedding == null）时无法做语义检索，
     * 诚实退化为纯关键词检索（KEYWORD 来源，不参与相似度阈值）。
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
        // 无 embedding：退化纯关键词，避免把关键词相对分伪装成语义相似度
        if (queryEmbedding == null) {
            return keywordSearch(query, knowledgeBaseId, topK)
        }

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
                keywordSearcher.search(query, knowledgeBaseId, candidateLimit)
                    .map { KeywordSearchResult(chunk = it.chunk, rank = it.rank, snippet = it.snippet) }
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
                normalizedScore = it.score,
                scoreSource = ScoreSource.SEMANTIC,
                rank = 0,
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
     * 纯关键词检索：仅用 FTS5，不做向量融合。
     *
     * 排序与分数都基于"检索词在 chunk 内出现的次数"（真实相关度）：
     * - 出现次数多的排前面
     * - normalizedScore = 匹配次数归一化到 0~1（出现次数 >= [KEYWORD_SCORE_CEILING] 视为满分）
     * - 不再用 1/(1+rank)：那是排名倒数，会让人以为"第 2 名就 50% 相关"。
     */
    suspend fun keywordSearch(
        query: String,
        knowledgeBaseId: String,
        topK: Int = 10,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)
        val keywordResults = keywordSearcher.search(query, knowledgeBaseId, candidateLimit)

        return keywordResults
            .sortedWith(
                compareByDescending<KeywordSearchResult> { it.matchCount }
                    .thenBy { it.rank }
            )
            .take(topK)
            .mapIndexed { index, result ->
                val normalized = (result.matchCount.toFloat() / KEYWORD_SCORE_CEILING).coerceIn(0f, 1f)
                RetrievalResult(
                    chunk = result.chunk,
                    score = result.matchCount.toFloat(),
                    normalizedScore = normalized,
                    scoreSource = ScoreSource.KEYWORD,
                    rank = index + 1,
                    snippet = result.snippet,
                    matchCount = result.matchCount,
                )
            }
    }

    /**
     * 阈值过滤：
     * - SEMANTIC / RERANK / HYBRID：用 normalizedScore（0~1 语义相关度）做绝对比较。
     * - KEYWORD：关键词匹配不做相似度过滤，永远保留。
     */
    private fun applyThreshold(
        results: List<RetrievalResult>,
        similarityThreshold: Float,
    ): List<RetrievalResult> {
        if (similarityThreshold <= 0f) return results
        return results.filter { result ->
            when (result.scoreSource) {
                ScoreSource.KEYWORD -> true
                else -> result.normalizedScore >= similarityThreshold
            }
        }
    }

    /**
     * RRF 融合向量与关键词结果。
     * - 有语义命中（向量结果里出现）→ HYBRID，display 分数 = 余弦相似度（真实语义相关度）。
     * - 仅关键词命中 → KEYWORD，display 用 matchCount（真实匹配次数），不伪装成语义相似度。
     * RRF 分数仅用于融合排序，不作为展示分数。
     */
    private fun rrfFusion(
        vectorResults: List<VectorSearchResult>,
        keywordResults: List<KeywordSearchResult>,
        k: Int = 60,
        keywordWeight: Float = 1f,
    ): List<RetrievalResult> {
        // chunkId -> (entity, rrfScore, semanticScore?, keywordMatchCount, snippet?)
        data class Acc(
            val chunk: KnowledgeChunkEntity,
            var rrf: Float,
            var semantic: Float?,
            var keywordMatchCount: Int,
            var snippet: String?,
        )

        val scores = LinkedHashMap<String, Acc>()

        vectorResults.forEachIndexed { index, result ->
            val acc = scores.getOrPut(result.chunk.id) {
                Acc(result.chunk, 0f, null, 0, null)
            }
            acc.rrf += 1f / (k + index + 1)
            acc.semantic = result.score
        }

        keywordResults.forEachIndexed { index, result ->
            val acc = scores.getOrPut(result.chunk.id) {
                Acc(result.chunk, 0f, null, 0, null)
            }
            acc.rrf += 1f / (k + index + 1) * keywordWeight
            if (result.matchCount > acc.keywordMatchCount) {
                acc.keywordMatchCount = result.matchCount
            }
            if (result.snippet != null) acc.snippet = result.snippet
        }

        return scores.values
            .sortedByDescending { it.rrf }
            .map { acc ->
                val semantic = acc.semantic
                if (semantic != null) {
                    // 语义命中：HYBRID，display 用真实余弦相似度
                    RetrievalResult(
                        chunk = acc.chunk,
                        score = acc.rrf,
                        normalizedScore = semantic,
                        scoreSource = ScoreSource.HYBRID,
                        rank = 0,
                        snippet = acc.snippet,
                    )
                } else {
                    // 仅关键词命中：KEYWORD，display 用真实匹配次数，不伪装成语义相似度
                    val normalized = (acc.keywordMatchCount.toFloat() / KEYWORD_SCORE_CEILING).coerceIn(0f, 1f)
                    RetrievalResult(
                        chunk = acc.chunk,
                        score = acc.rrf,
                        normalizedScore = normalized,
                        scoreSource = ScoreSource.KEYWORD,
                        rank = 0,
                        snippet = acc.snippet,
                        matchCount = acc.keywordMatchCount,
                    )
                }
            }
    }

    /**
     * MMR（Maximal Marginal Relevance）多样性控制。
     * λ=1 纯相关性排序，λ=0 纯多样性（最小化与已选结果的相似度）。
     * relevance 用 normalizedScore（0~1），与相似度同量级，避免 RRF 小分数主导。
     *
     * 性能：预计算候选间相似度矩阵（一次批量取 embedding），避免 O(n²) 次逐 chunk 缓存扫描。
     */
    private fun mmrDiversify(
        results: List<RetrievalResult>,
        knowledgeBaseId: String,
        queryEmbedding: FloatArray?,
        maxResults: Int,
        lambda: Float,
    ): List<RetrievalResult> {
        if (results.size <= 1) return results

        // 预计算候选间相似度矩阵：chunkId -> (chunkId -> 相似度)
        val simMatrix = if (queryEmbedding != null) {
            val embeddings = vectorStore.getEmbeddings(knowledgeBaseId, results.map { it.chunk.id })
            if (embeddings.isNotEmpty()) {
                val matrix = mutableMapOf<String, MutableMap<String, Float>>()
                val ids = results.map { it.chunk.id }
                for (a in ids) {
                    val ea = embeddings[a] ?: continue
                    for (b in ids) {
                        if (a == b) continue
                        val eb = embeddings[b] ?: continue
                        val sim = Similarity.cosineSimilarity(ea, eb)
                        if (!sim.isNaN()) {
                            matrix.getOrPut(a) { mutableMapOf() }[b] = sim
                        }
                    }
                }
                matrix
            } else null
        } else null

        val selected = mutableListOf<RetrievalResult>()
        val candidates = results.toMutableList()

        // 取第一个（最高分）作为种子
        selected.add(candidates.removeAt(0))

        while (candidates.isNotEmpty() && selected.size < maxResults) {
            var bestIdx = 0
            var bestScore = Float.NEGATIVE_INFINITY

            for (i in candidates.indices) {
                val candidate = candidates[i]
                val relevance = candidate.normalizedScore

                // 计算与已选结果的最大相似度（查预计算矩阵，O(1)）
                var maxSimilarity = 0f
                if (simMatrix != null) {
                    val row = simMatrix[candidate.chunk.id]
                    if (row != null) {
                        for (s in selected) {
                            val sim = row[s.chunk.id] ?: 0f
                            if (sim > maxSimilarity) maxSimilarity = sim
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
