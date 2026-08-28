package me.rerere.rikkahub.data.ai.cost

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostCalculatorTest {

    private fun beijingMillis(hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 8, 20, hour, minute, 0)
            .toInstant(TimeZone.of("Asia/Shanghai"))
            .toEpochMilliseconds()

    @Test
    fun `peak time follows Beijing hours`() {
        assertFalse(CostCalculator.isPeakTime(beijingMillis(8, 59)))
        assertTrue(CostCalculator.isPeakTime(beijingMillis(9, 0)))
        assertTrue(CostCalculator.isPeakTime(beijingMillis(11, 59)))
        assertFalse(CostCalculator.isPeakTime(beijingMillis(12, 0)))
        assertTrue(CostCalculator.isPeakTime(beijingMillis(14, 0)))
        assertTrue(CostCalculator.isPeakTime(beijingMillis(17, 59)))
        assertFalse(CostCalculator.isPeakTime(beijingMillis(18, 0)))
    }

    @Test
    fun `deepseek cost doubles during peak`() {
        val usage = TokenUsage(
            promptTokens = 1_000_000,
            completionTokens = 1_000_000,
        )
        val offPeak = CostCalculator.costUsd(
            "deepseek-v4-flash",
            usage,
            emptyList(),
            beijingMillis(8, 0),
        )
        val peak = CostCalculator.costUsd(
            "deepseek-v4-flash",
            usage,
            emptyList(),
            beijingMillis(9, 0),
        )

        assertEquals(0.88, offPeak, 1e-9)
        assertEquals(offPeak * 2, peak, 1e-9)
    }

    @Test
    fun `cache hit rate follows hit over total input`() {
        // DeepSeek 官方口径：命中率 = hit ÷ (hit + miss)；
        // 本项目 promptTokens 已含缓存读出部分，故 = cached ÷ promptTokens。
        val usages = listOf(
            TokenUsage(promptTokens = 10_000, cachedTokens = 8_000),
            TokenUsage(promptTokens = 4_000, cachedTokens = 2_000),
        )
        assertEquals(10_000.0 / 14_000.0, CostCalculator.cacheHitRate(usages)!!, 1e-9)
    }

    @Test
    fun `cache write counts as miss like deepseek`() {
        // Anthropic 合并口径：prompt = normal + cache_read + cache_creation，
        // 写缓存那次输入按未命中计费，同样计入分母（与 DeepSeek 的 miss 定义一致）。
        val usages = listOf(
            TokenUsage(promptTokens = 10_000, cachedTokens = 5_000, cacheWriteTokens = 3_000),
        )
        assertEquals(0.5, CostCalculator.cacheHitRate(usages)!!, 1e-9)
    }

    @Test
    fun `cache hit rate is null without input tokens`() {
        assertNull(CostCalculator.cacheHitRate(emptyList()))
        assertNull(CostCalculator.cacheHitRate(listOf(null, TokenUsage())))
    }

    @Test
    fun `cache hit rate clamps at one hundred percent`() {
        // 个别网关 prompt_tokens 不含缓存子集时 raw 比值 > 1，钳制到 1.0
        val usages = listOf(
            TokenUsage(promptTokens = 1_000, cachedTokens = 1_500),
        )
        assertEquals(1.0, CostCalculator.cacheHitRate(usages)!!, 1e-9)
    }

    @Test
    fun `cache hit rate ignores null usages`() {
        val usages = listOf(
            null,
            TokenUsage(promptTokens = 10_000, cachedTokens = 5_000),
            null,
        )
        assertEquals(0.5, CostCalculator.cacheHitRate(usages)!!, 1e-9)
    }
}
