package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRouletteTest {

    @Test
    fun `splitApiKeys should trim filter blanks and dedupe`() {
        assertEquals(listOf("a", "b", "c"), splitApiKeys(" a , b\nb c "))
        assertTrue(splitApiKeys("  ,\n , ").isEmpty())
        assertEquals(listOf("sk-1"), splitApiKeys("sk-1"))
    }

    @Test
    fun `DefaultKeyRoulette next with multipleKeys false returns as-is`() {
        val roulette = KeyRoulette.default()
        assertEquals("k1", roulette.next("k1", "p1", multipleKeys = false))
        assertEquals("k1\nk2", roulette.next("k1\nk2", "p1", multipleKeys = false))
    }

    @Test
    fun `DefaultKeyRoulette next with multipleKeys true picks one of the keys`() {
        val roulette = KeyRoulette.default()
        val picked = roulette.next("k1,k2", "p1", multipleKeys = true)
        assertTrue(picked in listOf("k1", "k2"))
    }

    @Test
    fun `retryWithKeyFallback does not retry when multipleKeys is false`() {
        val roulette = KeyRoulette.default()
        var calls = 0
        val result = kotlinx.coroutines.runBlocking {
            roulette.retryWithKeyFallback(
                keys = "k1,k2,k3",
                providerId = "p1",
                multipleKeys = false,
            ) {
                calls++
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithKeyFallback skips retry for single key even when multipleKeys is true`() {
        val roulette = KeyRoulette.default()
        var calls = 0
        val result = kotlinx.coroutines.runBlocking {
            roulette.retryWithKeyFallback(
                keys = "k1",
                providerId = "p1",
                multipleKeys = true,
            ) {
                calls++
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithKeyFallback retries through all keys when first fails with 401`() {
        val roulette = DeterministicKeyRoulette(listOf("k1", "k2", "k3"))
        var calls = 0
        val result = kotlinx.coroutines.runBlocking {
            roulette.retryWithKeyFallback(
                keys = "k1,k2,k3",
                providerId = "p1",
                multipleKeys = true,
            ) { key ->
                calls++
                if (key in listOf("k1", "k2")) throw RetryableHttpException(401, "unauthorized")
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `retryWithKeyFallback throws last exception when all keys fail`() {
        val roulette = DeterministicKeyRoulette(listOf("k1", "k2", "k3"))
        var calls = 0
        var thrown: Throwable? = null
        kotlinx.coroutines.runBlocking {
            try {
                roulette.retryWithKeyFallback(
                    keys = "k1,k2,k3",
                    providerId = "p1",
                    multipleKeys = true,
                ) {
                    calls++
                    throw RetryableHttpException(429, "rate limited")
                }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue(thrown is RetryableHttpException)
        assertEquals(3, calls)
    }

    @Test
    fun `retryWithKeyFallback does not retry on 500`() {
        val roulette = DeterministicKeyRoulette(listOf("k1", "k2"))
        var calls = 0
        var thrown: Throwable? = null
        kotlinx.coroutines.runBlocking {
            try {
                roulette.retryWithKeyFallback(
                    keys = "k1,k2",
                    providerId = "p1",
                    multipleKeys = true,
                ) {
                    calls++
                    throw RetryableHttpException(500, "internal")
                }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue(thrown is RetryableHttpException)
        assertEquals(1, calls)
    }

    @Test
    fun `retryWithKeyFallback stops at first success`() {
        val roulette = DeterministicKeyRoulette(listOf("k1", "k2", "k3"))
        var calls = 0
        val result = kotlinx.coroutines.runBlocking {
            roulette.retryWithKeyFallback(
                keys = "k1,k2,k3",
                providerId = "p1",
                multipleKeys = true,
            ) { key ->
                calls++
                if (key == "k1") throw RetryableHttpException(401, "unauthorized")
                "$key-ok"
            }
        }
        assertEquals("k2-ok", result)
        assertEquals(2, calls)
    }

    /** 确定性的 KeyRoulette，next 始终返回第一个 key，便于预测重试顺序 */
    private class DeterministicKeyRoulette(private val keys: List<String>) : KeyRoulette {
        override fun next(keys: String, providerId: String, multipleKeys: Boolean): String {
            return this.keys.first()
        }

        override fun markFailed(keys: String, providerId: String, key: String) {
        }
    }
}
