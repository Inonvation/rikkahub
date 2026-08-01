package me.rerere.knowledge.chunking

/**
 * 语义分块器：检测话题边界，在语义转折点切分，避免在话题中间强行切断。
 *
 * 算法：
 * 1. 将文本拆成句子
 * 2. 计算相邻句子的词重叠度（Jaccard 相似度）
 * 3. 当重叠度低于阈值 × 平均重叠度时，视为话题边界，开始新 chunk
 * 4. 累积句子直到达到 chunkSize，然后强制切分
 */
class SemanticChunker : Chunker {
    private val sentenceEndPattern = Regex("(?<=[.!?。！？])\\s+")

    override fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val sentences = text.split(sentenceEndPattern)
            .filter { it.isNotBlank() }
            .ifEmpty { text.split(" ") }
            .ifEmpty { listOf(text) }

        if (sentences.size <= 1) {
            return listOf(
                Chunk(
                    content = text.trim(),
                    chunkIndex = 0,
                    tokenCount = estimateTokenCount(text),
                )
            )
        }

        // 计算相邻句子的词重叠度
        val coherenceScores = computeCoherenceScores(sentences)
        val avgCoherence = if (coherenceScores.isNotEmpty()) {
            coherenceScores.average().toFloat()
        } else {
            0.3f
        }
        // 阈值：低于平均的 40% 视为话题边界
        val boundaryThreshold = (avgCoherence * 0.4f).coerceAtLeast(0.05f)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var prevSentence = ""

        for ((i, sentence) in sentences.withIndex()) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue

            val shouldBreak = when {
                // 第一个句子直接加
                i == 0 -> false
                // 当前 buffer 加上新句子会超限
                current.length + trimmed.length + 1 > chunkSize -> true
                // 话题边界检测
                else -> {
                    val score = coherenceScores.getOrNull(i - 1) ?: 1f
                    score < boundaryThreshold
                }
            }

            if (shouldBreak && current.isNotEmpty()) {
                chunks.add(current.toString())
                current = StringBuilder()
                // overlap：从前一个 chunk 末尾取 overlap 字符
                if (chunkOverlap > 0 && chunks.isNotEmpty()) {
                    val prev = chunks.last()
                    current.append(prev.takeLast(chunkOverlap))
                }
            }

            if (current.isNotEmpty()) current.append(" ")
            current.append(trimmed)

            // 超长句子：强制按 chunk_size 切分
            if (trimmed.length > chunkSize && current.length > chunkSize) {
                val subChunks = splitLongSentence(trimmed, chunkSize)
                // 替换当前 buffer 中的超长句子
                chunks.addAll(subChunks.dropLast(1))
                current = StringBuilder(subChunks.last())
                if (chunkOverlap > 0) {
                    current.insert(0, subChunks.dropLast(1).lastOrNull()?.takeLast(chunkOverlap) ?: "")
                }
            }
        }

        if (current.isNotBlank()) {
            chunks.add(current.toString())
        }

        return chunks.mapIndexed { index, content ->
            Chunk(
                content = content,
                chunkIndex = index,
                tokenCount = estimateTokenCount(content),
            )
        }
    }

    /**
     * 计算相邻句子的词重叠度（Jaccard 相似度）。
     * 对中文按字符级 bigram 分词，英文按空格分词。
     */
    private fun computeCoherenceScores(sentences: List<String>): List<Float> {
        val scores = mutableListOf<Float>()
        for (i in 0 until sentences.size - 1) {
            val tokens1 = tokenize(sentences[i])
            val tokens2 = tokenize(sentences[i + 1])
            if (tokens1.isEmpty() || tokens2.isEmpty()) {
                scores.add(0f)
                continue
            }
            val intersection = tokens1.intersect(tokens2).size
            val union = tokens1.union(tokens2).size
            scores.add(if (union > 0) intersection.toFloat() / union else 0f)
        }
        return scores
    }

    /**
     * 分词：中文用 bigram，英文用空格分词。
     */
    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        // 英文单词
        val wordPattern = Regex("[a-zA-Z0-9]+")
        wordPattern.findAll(text).forEach { tokens.add(it.value.lowercase()) }
        // 中文 bigram
        val chineseChars = text.filter { it.code > 0x4E00 }
        for (i in 0 until chineseChars.length - 1) {
            tokens.add(chineseChars.substring(i, i + 2))
        }
        return tokens
    }

    private fun splitLongSentence(text: String, chunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + chunkSize).coerceAtMost(text.length)
            if (end < text.length) {
                val spaceIdx = text.lastIndexOf(' ', end)
                if (spaceIdx > start) end = spaceIdx
            }
            if (end <= start) end = start + 1
            chunks.add(text.substring(start, end).trim())
            start = end
        }
        return chunks
    }
}