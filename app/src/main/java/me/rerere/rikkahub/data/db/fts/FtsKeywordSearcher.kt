package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.retrieval.KeywordSearchResult
import me.rerere.knowledge.retrieval.KeywordSearcher

/**
 * 基于 FTS5 + jieba 的关键词检索引擎（知识库）。
 *
 * 每次检索前做一次按文档粒度的增量对账，只重建有差异的文档索引，
 * 保证新增/删除/重试文档后索引自动同步。
 * FTS 不可用时（建表失败 / 查询异常）抛异常，由上游把错误呈现给用户，而非静默回退。
 */
class FtsKeywordSearcher(
    private val ftsManager: KnowledgeChunkFtsManager,
    private val chunkDao: KnowledgeChunkDao,
) : KeywordSearcher {

    override suspend fun search(
        query: String,
        knowledgeBaseId: String,
        topK: Int,
    ): List<KeywordSearchResult> = withContext(Dispatchers.IO) {
        if (!ftsManager.ensureIndex()) {
            throw IllegalStateException("FTS unavailable")
        }
        reconcile(knowledgeBaseId)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // 双路径召回：
        // 1. FTS5 + jieba_query（精确分词召回，BM25 排名准）——主路径，走索引快
        // 2. LIKE 子串兜底（救援模式）：仅当 FTS 命中不足 topK 时执行，
        //    且 LIMIT 约束常见词的全表扫描成本，避免每次检索都全表 LIKE。
        val ftsHits = ftsManager.search(trimmed, knowledgeBaseId)
        val byId = chunkDao.getByChunkIds(ftsHits.map { it.chunkId }).associateBy { it.id }
        val merged = LinkedHashMap<String, KeywordSearchResult>()

        ftsHits.forEach { hit ->
            byId[hit.chunkId]?.let { chunk ->
                merged[hit.chunkId] = KeywordSearchResult(
                    chunk = chunk,
                    rank = hit.rank,
                    snippet = hit.snippet,
                    matchCount = countMatches(chunk.content, trimmed),
                )
            }
        }

        // 救援模式：FTS 命中不足 topK 时，才用 LIKE 兜底补足，避免全表扫描
        if (merged.size < topK) {
            val likeLimit = topK - merged.size
            chunkDao.searchBySubstring(knowledgeBaseId, trimmed, likeLimit).forEach { chunk ->
                if (chunk.id !in merged) {
                    merged[chunk.id] = KeywordSearchResult(
                        chunk = chunk,
                        rank = Int.MAX_VALUE,
                        snippet = null,
                        matchCount = countMatches(chunk.content, trimmed),
                    )
                }
            }
        }

        merged.values.take(topK)
    }

    /**
     * 统计检索词在文本中出现的次数（大小写不敏感，非重叠计数）。
     * 作为关键词"真实相关度"的依据：出现越多越相关。
     */
    private fun countMatches(text: String, query: String): Int {
        if (text.isEmpty() || query.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(query, index, ignoreCase = true)
            if (found < 0) break
            count++
            index = found + query.length
        }
        return count
    }

    /**
     * 对账：按文档粒度比对 chunk 表与 FTS 索引，只重建有差异的文档。
     * 带节流：5 秒内同一知识库只对账一次，避免连续检索时反复跑对账查询。
     */
    private suspend fun reconcile(knowledgeBaseId: String) {
        val now = System.currentTimeMillis()
        val last = lastReconcileAt[knowledgeBaseId] ?: 0L
        if (now - last < RECONCILE_INTERVAL_MS) return
        lastReconcileAt[knowledgeBaseId] = now

        val realDocIds = chunkDao.getDocumentIdsByKnowledgeBaseId(knowledgeBaseId).toSet()
        val indexedDocIds = ftsManager.getIndexedDocumentIds(knowledgeBaseId)

        val toRebuild = realDocIds - indexedDocIds
        // 索引里有但 chunk 表没有的文档 = 已删除/已重试无结果，需清掉其索引行
        val toPrune = indexedDocIds - realDocIds

        if (toRebuild.isEmpty() && toPrune.isEmpty()) return

        (toPrune + toRebuild).forEach { docId ->
            val chunks = chunkDao.getByDocumentId(docId)
            ftsManager.rebuildDocument(knowledgeBaseId, docId, chunks)
        }
    }

    private companion object {
        const val RECONCILE_INTERVAL_MS = 5_000L
    }

    private val lastReconcileAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
}
