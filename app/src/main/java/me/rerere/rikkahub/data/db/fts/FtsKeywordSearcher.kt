package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.retrieval.KeywordSearchResult
import me.rerere.knowledge.retrieval.KeywordSearcher

/**
 * 基于 FTS5 + jieba 的关键词检索引擎（知识库）。
 *
 * 每次检索前做一次 count 对账，chunk 表与 FTS 索引数量不一致时重建，
 * 保证新增/删除/重试文档后索引自动同步。
 * FTS 不可用时（建表失败 / 查询异常）抛异常，由上游回退到内置 BM25。
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
        val hits = ftsManager.search(query, knowledgeBaseId, topK)
        if (hits.isEmpty()) return@withContext emptyList()

        val byId = chunkDao.getByKnowledgeBaseId(knowledgeBaseId).associateBy { it.id }
        hits.mapNotNull { hit ->
            byId[hit.chunkId]?.let { chunk ->
                KeywordSearchResult(
                    chunk = chunk,
                    rank = hit.rank,
                    snippet = hit.snippet,
                )
            }
        }
    }

    /** 对账：chunk 表与 FTS 索引数量不一致则重建该知识库索引。 */
    private suspend fun reconcile(knowledgeBaseId: String) {
        val real = chunkDao.countByKnowledgeBaseId(knowledgeBaseId).toLong()
        val indexed = ftsManager.countIndexed(knowledgeBaseId)
        if (real != indexed) {
            ftsManager.rebuildBase(knowledgeBaseId, chunkDao.getByKnowledgeBaseId(knowledgeBaseId))
        }
    }
}
