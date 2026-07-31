package me.rerere.knowledge.retrieval

import me.rerere.knowledge.data.entity.KnowledgeChunkEntity

data class Bm25SearchResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
)

class Bm25Searcher(
    private val chunkDao: me.rerere.knowledge.data.dao.KnowledgeChunkDao,
) {
    suspend fun search(
        query: String,
        knowledgeBaseId: String,
        topK: Int = 10,
    ): List<Bm25SearchResult> {
        val chunks = chunkDao.getByKnowledgeBaseId(knowledgeBaseId)
        if (chunks.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val scored = chunks.map { chunk ->
            val score = bm25Score(chunk.content, queryTerms)
            chunk to score
        }

        return scored
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
            .mapIndexed { index, (chunk, score) ->
                Bm25SearchResult(
                    chunk = chunk,
                    score = score,
                    rank = index + 1,
                )
            }
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
                if (!ch.isWhitespace()) {
                    tokens.add(ch.toString())
                }
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    private fun bm25Score(document: String, queryTerms: List<String>): Float {
        val docTokens = tokenize(document)
        if (docTokens.isEmpty()) return 0f

        val k1 = 1.5f
        val b = 0.75f
        val avgDocLength = 256f

        val docLength = docTokens.size
        val termFreqs = docTokens.groupingBy { it }.eachCount()

        val corpusSize = 1f
        var score = 0f

        for (term in queryTerms) {
            val tf = termFreqs[term]?.toFloat() ?: 0f
            if (tf == 0f) continue

            val df = 1f
            val idf = kotlin.math.ln(1f + (corpusSize - df + 0.5f) / (df + 0.5f))

            val numerator = tf * (k1 + 1f)
            val denominator = tf + k1 * (1f - b + b * docLength / avgDocLength)
            score += idf * numerator / denominator
        }

        return score
    }
}