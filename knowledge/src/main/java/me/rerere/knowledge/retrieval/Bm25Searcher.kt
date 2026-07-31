package me.rerere.knowledge.retrieval

import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity

data class Bm25SearchResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
)

class Bm25Searcher(
    private val chunkDao: KnowledgeChunkDao,
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
        val chineseBuffer = StringBuilder()
        val englishBuffer = StringBuilder()

        fun flushChinese() {
            if (chineseBuffer.isNotEmpty()) {
                addChineseTokens(chineseBuffer.toString(), tokens)
                chineseBuffer.clear()
            }
        }

        fun flushEnglish() {
            if (englishBuffer.isNotEmpty()) {
                tokens.add(englishBuffer.toString().lowercase())
                englishBuffer.clear()
            }
        }

        for (ch in text) {
            when {
                ch.isChineseCharacter() -> {
                    flushEnglish()
                    chineseBuffer.append(ch)
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '-' -> {
                    flushChinese()
                    englishBuffer.append(ch)
                }
                else -> {
                    flushChinese()
                    flushEnglish()
                }
            }
        }
        flushChinese()
        flushEnglish()

        return tokens
    }

    /** 对中文文本做单字 + 相邻二元组 token。 */
    private fun addChineseTokens(text: String, tokens: MutableList<String>) {
        if (text.isEmpty()) return
        for (i in text.indices) {
            tokens.add(text[i].toString())
            if (i + 1 < text.length) {
                tokens.add(text.substring(i, i + 2))
            }
        }
    }

    /** 判断是否为 CJK 汉字（含中日韩统一表意文字）。 */
    private fun Char.isChineseCharacter(): Boolean {
        val code = this.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x20000..0x2A6DF ||
            code in 0x2A700..0x2B73F ||
            code in 0x2B740..0x2B81F ||
            code in 0x2B820..0x2CEAF ||
            code in 0xF900..0xFAFF
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