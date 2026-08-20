package me.rerere.rikkahub.data.ai.cost

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
