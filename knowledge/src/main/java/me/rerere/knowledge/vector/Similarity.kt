package me.rerere.knowledge.vector

import kotlin.math.sqrt

object Similarity {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}

fun FloatArray.toByteArray(): ByteArray {
    val bytes = ByteArray(size * 4)
    for (i in indices) {
        val bits = java.lang.Float.floatToRawIntBits(this[i])
        bytes[i * 4] = (bits shr 24).toByte()
        bytes[i * 4 + 1] = (bits shr 16).toByte()
        bytes[i * 4 + 2] = (bits shr 8).toByte()
        bytes[i * 4 + 3] = bits.toByte()
    }
    return bytes
}

fun ByteArray.toFloatArray(): FloatArray {
    val floats = FloatArray(size / 4)
    for (i in floats.indices) {
        val bits = ((this[i * 4].toInt() and 0xFF) shl 24) or
                ((this[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((this[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (this[i * 4 + 3].toInt() and 0xFF)
        floats[i] = java.lang.Float.intBitsToFloat(bits)
    }
    return floats
}