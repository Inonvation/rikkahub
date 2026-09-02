package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatingThinkingLabelTest {

    // ---- phraseGroupIndexFor：按思考时长选组 ----

    @Test
    fun `early group before 10s`() {
        assertEquals(0, phraseGroupIndexFor(0L))
        assertEquals(0, phraseGroupIndexFor(9_999L))
    }

    @Test
    fun `mid group from 10s to 30s`() {
        assertEquals(1, phraseGroupIndexFor(10_000L))
        assertEquals(1, phraseGroupIndexFor(29_999L))
    }

    @Test
    fun `late group from 30s`() {
        assertEquals(2, phraseGroupIndexFor(30_000L))
        assertEquals(2, phraseGroupIndexFor(120_000L))
    }

    // ---- phraseIntervalFor：间隔随时长收窄，与分组边界一致 ----

    @Test
    fun `interval narrows as thinking gets longer`() {
        assertEquals(THINKING_PHRASE_INTERVAL_EARLY_MS, phraseIntervalFor(0L))
        assertEquals(THINKING_PHRASE_INTERVAL_EARLY_MS, phraseIntervalFor(9_999L))
        assertEquals(THINKING_PHRASE_INTERVAL_MID_MS, phraseIntervalFor(10_000L))
        assertEquals(THINKING_PHRASE_INTERVAL_MID_MS, phraseIntervalFor(29_999L))
        assertEquals(THINKING_PHRASE_INTERVAL_LATE_MS, phraseIntervalFor(30_000L))
        assertEquals(THINKING_PHRASE_INTERVAL_LATE_MS, phraseIntervalFor(120_000L))
    }

    // ---- shuffledPhraseOrder：洗牌后的下标序列 ----

    @Test
    fun `empty and single element`() {
        assertEquals(emptyList<Int>(), shuffledPhraseOrder(0))
        assertEquals(listOf(0), shuffledPhraseOrder(1))
    }

    @Test
    fun `shuffled order is a permutation of range`() {
        repeat(50) {
            val order = shuffledPhraseOrder(8)
            assertEquals(8, order.size)
            assertEquals((0 until 8).toList(), order.sorted())
        }
    }

    @Test
    fun `avoids repeating first item when requested`() {
        repeat(100) {
            val order = shuffledPhraseOrder(5, avoidFirst = 3)
            assertEquals((0 until 5).toList(), order.sorted())
            assertTrue("avoidFirst 不应出现在首位", order.first() != 3)
        }
    }

    @Test
    fun `avoidFirst outside range is ignored`() {
        repeat(50) {
            val order = shuffledPhraseOrder(4, avoidFirst = 99)
            assertEquals((0 until 4).toList(), order.sorted())
            assertEquals(4, order.size)
        }
    }
}