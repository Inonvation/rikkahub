package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressContextDialogTest {
    @Test
    fun modelContextLengthTakesPriority() {
        assertEquals(1_000_000, resolveContextTokenLimit(1_000_000, 128_000))
    }

    @Test
    fun fallsBackToAssistantLimitWhenModelUnknown() {
        assertEquals(128_000, resolveContextTokenLimit(null, 128_000))
        assertEquals(64_000, resolveContextTokenLimit(null, 64_000))
    }

    @Test
    fun invalidModelLengthFallsBackToAssistant() {
        assertEquals(64_000, resolveContextTokenLimit(0, 64_000))
        assertEquals(64_000, resolveContextTokenLimit(-1, 64_000))
    }

    @Test
    fun usesDefaultWhenBothMissingOrInvalid() {
        assertEquals(128_000, resolveContextTokenLimit(null, 0))
        assertEquals(128_000, resolveContextTokenLimit(0, 0))
    }

    @Test
    fun autoCompressTriggersAtOrAboveThreshold() {
        assertTrue(autoCompressShouldTrigger(80_000, 100_000, 80, true))
        assertTrue(autoCompressShouldTrigger(100_000, 100_000, 80, true))
        assertFalse(autoCompressShouldTrigger(79_999, 100_000, 80, true))
        assertFalse(autoCompressShouldTrigger(100_000, 100_000, 80, false))
        assertFalse(autoCompressShouldTrigger(0, 100_000, 80, true))
        assertFalse(autoCompressShouldTrigger(100_000, 0, 80, true))
    }

    @Test
    fun autoCompressResetUsesHysteresis() {
        assertEquals(70, autoCompressResetThreshold(80))
        assertEquals(1, autoCompressResetThreshold(5))
        assertEquals(1, autoCompressResetThreshold(1))
    }
}
