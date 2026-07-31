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
            // Rerank failed, fall back to original order
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
     * Cherry Studio 风格的检索:
     * 1. Over-fetch (topK × 3) 候选
     * 2. Vector + Keyword → RRF fusion（关键词侧可按 keywordWeight 加权）
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
        keywordWeight: Float = 1f,
    ): List<RetrievalResult> {
        val candidateLimit = (topK * 3).coerceAtMost(100)

        // 并行执行 vector search 和关键词 search (over-fetch)
        val (vectorResults, keywordResults) = coroutineScope {
            val vectorDeferred = async {
                if (queryEmbedding != null) {
                    vectorStore.search(queryEmbedding, knowledgeBaseId, candidateLimit)
                } else {
                    emptyList()
                }
            }
            val keywordDeferred = async {
                // 关键词侧优先用 FTS5+jieba（真正的 BM25 排序）；不可用时回退到内置 BM25
                keywordSearcher
                    ?.let { searcher ->
                        runCatching { searcher.search(query, knowledgeBaseId, candidateLimit) }.getOrNull()
                    }
                    ?: bm25Searcher.search(query, knowledgeBaseId, candidateLimit)
                        .map { KeywordSearchResult(chunk = it.chunk, rank = it.rank) }
            }
            vectorDeferred.await() to keywordDeferred.await()
        }

        // RRF 融合
        val fused = rrfFusion(vectorResults, keywordResults, keywordWeight = keywordWeight)

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
            // 关键词命中的 snippet 透传给融合结果，供 UI 展示命中上下文
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
}