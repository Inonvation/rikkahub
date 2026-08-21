package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
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
}
