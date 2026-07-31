package me.rerere.knowledge.chunking

data class Chunk(
    val content: String,
    val chunkIndex: Int,
    val tokenCount: Int,
)

interface Chunker {
    fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<Chunk>
}

fun estimateTokenCount(text: String): Int = (text.length * 0.4).toInt().coerceAtLeast(1)