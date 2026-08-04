package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    /**
     * 本次写入缓存的 token 数（cache write，如 Anthropic 的 cache_creation_input_tokens）。
     * 写缓存那次天然全 miss，命中率统计时应从分母剔除，否则会把「读」的命中率系统性拉低。
     * 仅缓存按 token 计数的 provider 会有此值，其余为 0。
     */
    val cacheWriteTokens: Int = 0,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    val cacheWriteTokens = if (other.cacheWriteTokens > 0) {
        other.cacheWriteTokens
    } else {
        this?.cacheWriteTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cacheWriteTokens = cacheWriteTokens,
    )
}

fun TokenUsage?.sum(other: TokenUsage): TokenUsage {
    val promptTokens = (this?.promptTokens ?: 0) + other.promptTokens
    val completionTokens = (this?.completionTokens ?: 0) + other.completionTokens
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = (this?.cachedTokens ?: 0) + other.cachedTokens,
        cacheWriteTokens = (this?.cacheWriteTokens ?: 0) + other.cacheWriteTokens,
        totalTokens = promptTokens + completionTokens,
    )
}
