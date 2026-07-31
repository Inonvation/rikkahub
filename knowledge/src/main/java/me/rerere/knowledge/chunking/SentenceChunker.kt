package me.rerere.knowledge.chunking

class SentenceChunker : Chunker {
    private val sentenceEndPattern = Regex("(?<=[.!?。！？])\\s+")

    override fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val sentences = text.split(sentenceEndPattern)
            .filter { it.isNotBlank() }
            .ifEmpty { text.split(" ") }
            .ifEmpty { listOf(text) }

        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (current.length + trimmed.length + 1 <= chunkSize) {
                if (current.isNotEmpty()) current.append(" ")
                current.append(trimmed)
            } else {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current = StringBuilder()
                    if (chunkOverlap > 0 && chunks.isNotEmpty()) {
                        val prev = chunks.last()
                        current.append(prev.takeLast(chunkOverlap))
                    }
                }
                if (trimmed.length > chunkSize) {
                    val subChunks = splitLongSentence(trimmed, chunkSize, chunkOverlap)
                    chunks.addAll(subChunks)
                } else {
                    current.append(trimmed)
                }
            }
        }
        if (current.isNotBlank()) chunks.add(current.toString())

        return chunks.mapIndexed { index, content ->
            Chunk(
                content = content,
                chunkIndex = index,
                tokenCount = estimateTokenCount(content),
            )
        }
    }

    private fun splitLongSentence(text: String, chunkSize: Int, chunkOverlap: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + chunkSize).coerceAtMost(text.length)
            if (end < text.length) {
                val spaceIdx = text.lastIndexOf(' ', end)
                if (spaceIdx > start) end = spaceIdx
            }
            chunks.add(text.substring(start, end).trim())
            start = end - chunkOverlap
            if (start >= text.length) break
        }
        return chunks
    }
}