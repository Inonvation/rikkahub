package me.rerere.knowledge.chunking

class FixedSizeChunker : Chunker {
    override fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        var current = StringBuilder(chunkSize + 256)
        var i = 0
        val len = text.length

        while (i < len) {
            // 找下一个段落或行边界
            var lineEnd = i
            while (lineEnd < len) {
                if (lineEnd + 1 < len && text[lineEnd] == '\n' && text[lineEnd + 1] == '\n') {
                    break // 段落边界 \n\n
                }
                if (text[lineEnd] == '\n') {
                    break // 行边界 \n
                }
                lineEnd++
            }
            // 跳过空行
            val line = text.substring(i, lineEnd).trim()
            val lineLen = line.length

            if (lineLen > 0) {
                if (current.length + lineLen + 2 <= chunkSize) {
                    if (current.isNotEmpty()) current.append("\n")
                    current.append(line)
                } else {
                    // 当前行放不下，先保存当前 chunk
                    if (current.isNotEmpty()) {
                        chunks.add(current.toString())
                        current = StringBuilder(chunkSize + 256)
                        if (chunkOverlap > 0 && chunks.isNotEmpty()) {
                            current.append(chunks.last().takeLast(chunkOverlap))
                        }
                    }
                    // 如果行本身比 chunkSize 大，分段切割
                    if (lineLen > chunkSize) {
                        chunks.addAll(splitLongText(line, chunkSize, chunkOverlap))
                    } else {
                        current.append(line)
                    }
                }
            }
            i = lineEnd + 1
            if (lineEnd >= len) break
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

    private fun splitLongText(text: String, chunkSize: Int, chunkOverlap: Int): List<String> {
        val expectedCount = (text.length / (chunkSize - chunkOverlap)) + 1
        val chunks = ArrayList<String>(expectedCount)
        var start = 0
        while (start < text.length) {
            var end = (start + chunkSize).coerceAtMost(text.length)
            if (end < text.length) {
                val breakPoint = findBreakPoint(text, end, start)
                if (breakPoint > start) end = breakPoint
            }
            chunks.add(text.substring(start, end))
            start = end - chunkOverlap
            if (start >= text.length) break
        }
        return chunks
    }

    private fun findBreakPoint(text: String, pos: Int, minPos: Int): Int {
        for (i in pos downTo minPos) {
            when (text[i]) {
                '\n' -> if (i + 1 < text.length && text[i + 1] == '\n') return i + 2
                '.', '!', '?' -> return i + 1
                '。', '！', '？' -> return i + 1
            }
        }
        for (i in pos downTo minPos) {
            if (text[i] == ' ') return i + 1
        }
        return -1
    }
}