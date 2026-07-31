package me.rerere.knowledge.retrieval

import me.rerere.knowledge.data.entity.KnowledgeChunkEntity

data class KeywordSearchResult(
    val chunk: KnowledgeChunkEntity,
    val rank: Int,               // 1-based 排序位（用于展示）
    val snippet: String? = null, // 命中片段（simple_snippet 高亮，带 [..] 标记）
)

/**
 * 关键词检索引擎抽象：FTS5 实现 + BM25 fallback 均适配。
 */
interface KeywordSearcher {
    suspend fun search(
        query: String,
        knowledgeBaseId: String,
        topK: Int,
    ): List<KeywordSearchResult>
}
