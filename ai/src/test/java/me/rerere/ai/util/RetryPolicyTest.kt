package me.rerere.ai.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    // ---- 退避计算 ----

    @Test
    fun `backoff doubles for early attempts and is capped at maxDelay`() {
        val policy = RetryPolicy(initialDelayMs = 100, maxDelayMs = 10_000, jitterRatio = 0.0)
        val random = kotlin.random.Random(42)

        assertEquals(100L, retryBackoffDelay(policy, 1, random = random))
        assertEquals(200L, retryBackoffDelay(policy, 2, random = random))
        assertEquals(400L, retryBackoffDelay(policy, 3, random = random))
        // 很大次数后封顶到 maxDelayMs
        assertEquals(10_000L, retryBackoffDelay(policy, 20, random = random))
        assertEquals(10_000L, retryBackoffDelay(policy, 200, random = random))
    }

    @Test
    fun `backoff jitter stays within symmetric ratio bounds`() {
        val policy = RetryPolicy(initialDelayMs = 1000, maxDelayMs = 10_000, jitterRatio = 0.1)
        val random = kotlin.random.Random(7)
        repeat(100) {
            val delay = retryBackoffDelay(policy, 1, random = random)
            assertTrue(delay in 900L..1100L)
        }
    }

    @Test
    fun `retry-after is used directly without jitter`() {
        val policy = RetryPolicy(initialDelayMs = 500, maxDelayMs = 10_000, jitterRatio = 0.5)
        val random = kotlin.random.Random(1)
        assertEquals(1500L, retryBackoffDelay(policy, 3, retryAfterMs = 1500L, random = random))
    }

    @Test
    fun `retry-after above maxDelay returns stop sentinel`() {
        val policy = RetryPolicy(initialDelayMs = 500, maxDelayMs = 10_000)
        assertEquals(RETRY_STOP_DELAY, retryBackoffDelay(policy, 2, retryAfterMs = 20_000L))
    }

    // ---- 可重试分类 ----

    @Test
    fun `isRetryable classifies transient statuses`() {
        assertTrue(HttpException("429", code = 429).isRetryable())
        assertTrue(HttpException("500", code = 500).isRetryable())
        assertTrue(HttpException("503", code = 503).isRetryable())
        assertFalse(HttpException("400", code = 400).isRetryable())
        assertFalse(HttpException("401", code = 401).isRetryable())
        assertFalse(HttpException("403", code = 403).isRetryable())
        assertFalse(HttpException("404", code = 404).isRetryable())
    }

    @Test
    fun `isRetryable treats io errors as retryable and others as not`() {
        assertTrue(IOException("network").isRetryable())
        assertFalse(RuntimeException("boom").isRetryable())
    }

    // ---- retryWithPolicy 执行器 ----

    @Test
    fun `retryWithPolicy succeeds after transient failures and reports retries`() = runBlocking {
        var calls = 0
        var onRetryCount = 0
        val result = retryWithPolicy(
            policy = RetryPolicy(maxRetries = 5),
            onRetry = { attempt, _, _ ->
                onRetryCount = attempt
            },
        ) {
            calls++
            if (calls <= 2) throw HttpException("transient", code = 503)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(2, onRetryCount)
    }

    @Test
    fun `retryWithPolicy throws after maxRetries exhausted`() = runBlocking {
        var calls = 0
        var onRetryCount = 0
        val caught = runCatching {
            retryWithPolicy(
                policy = RetryPolicy(maxRetries = 2),
                onRetry = { _, _, _ -> onRetryCount++ },
            ) {
                calls++
                throw HttpException("still failing", code = 429)
            }
        }.exceptionOrNull()
        assertTrue(caught is HttpException)
        assertEquals(3, calls) // 初始 + 2 次重试
        assertEquals(2, onRetryCount)
    }

    @Test
    fun `retryWithPolicy does not retry non-transient errors`() = runBlocking {
        var calls = 0
        var onRetryCount = 0
        val caught = runCatching {
            retryWithPolicy(
                policy = RetryPolicy(maxRetries = 5),
                onRetry = { _, _, _ -> onRetryCount++ },
            ) {
                calls++
                throw HttpException("bad request", code = 400)
            }
        }.exceptionOrNull()
        assertTrue(caught is HttpException)
        assertEquals(1, calls)
        assertEquals(0, onRetryCount)
    }

    @Test
    fun `retryWithPolicy stops when Retry-After exceeds maxDelay`() = runBlocking {
        var calls = 0
        var onRetryCount = 0
        val caught = runCatching {
            retryWithPolicy(
                policy = RetryPolicy(maxRetries = 5, maxDelayMs = 10_000),
                onRetry = { _, _, _ -> onRetryCount++ },
            ) {
                calls++
                throw HttpException("rate limited", code = 429, retryAfterMs = 20_000)
            }
        }.exceptionOrNull()
        assertTrue(caught is HttpException)
        assertEquals(1, calls)
        assertEquals(0, onRetryCount)
    }
}
