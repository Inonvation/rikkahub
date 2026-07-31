package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRouletteTest {

    @Test
    fun `DefaultKeyRoulette next picks random key from list`() {
        val roulette = KeyRoulette.default()
        // splitKey splits on whitespace and commas
        val picked = roulette.next("k1,k2", "p1")
        assertTrue(picked in listOf("k1", "k2"))
    }

    @Test
    fun `DefaultKeyRoulette next returns as-is for single key`() {
        val roulette = KeyRoulette.default()
        assertEquals("sk-test", roulette.next("sk-test", "p1"))
    }

    @Test
    fun `DefaultKeyRoulette next returns as-is for empty`() {
        val roulette = KeyRoulette.default()
        assertEquals("", roulette.next("", "p1"))
    }
}