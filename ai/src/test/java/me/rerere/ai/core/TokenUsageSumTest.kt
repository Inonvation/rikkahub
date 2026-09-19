package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenUsageSumTest {
    @Test
    fun `sum accumulates prompt completion cached and cache write`() {
        val a = TokenUsage(
            promptTokens = 100,
            completionTokens = 20,
            cachedTokens = 80,
            cacheWriteTokens = 10,
        )
        val b = TokenUsage(
            promptTokens = 150,
            completionTokens = 30,
            cachedTokens = 120,
            cacheWriteTokens = 40,
        )
        val sum = a.sum(b)
        assertEquals(250, sum.promptTokens)
        assertEquals(50, sum.completionTokens)
        assertEquals(200, sum.cachedTokens)
        assertEquals(50, sum.cacheWriteTokens)
        assertEquals(300, sum.totalTokens)
    }

    @Test
    fun `sum with null receiver normalizes totalTokens`() {
        val b = TokenUsage(promptTokens = 7, completionTokens = 3, totalTokens = 0)
        val sum = (null as TokenUsage?).sum(b)
        assertEquals(7, sum.promptTokens)
        assertEquals(3, sum.completionTokens)
        // null receiver 时仍重算 totalTokens（不透传可能是 0 的旧字段）
        assertEquals(10, sum.totalTokens)
    }

    @Test
    fun `sum totalTokens is always prompt plus completion even when source totals were zero`() {
        val a = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 0)
        val b = TokenUsage(promptTokens = 20, completionTokens = 15, totalTokens = 0)
        val sum = a.sum(b)
        assertEquals(30, sum.promptTokens)
        assertEquals(20, sum.completionTokens)
        assertEquals(50, sum.totalTokens)
    }
}
