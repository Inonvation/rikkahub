package me.rerere.knowledge.chunking

class ParagraphChunker : Chunker {
    override fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (para in paragraphs) {
            if (current.length + para.length <= chunkSize) {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(para)
            } else {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current = StringBuilder()
                    if (chunkOverlap > 0 && chunks.isNotEmpty()) {
                        val prev = chunks.last()
                        current.append(prev.takeLast(chunkOverlap))
                    }
                }
                current.append(para)
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
}