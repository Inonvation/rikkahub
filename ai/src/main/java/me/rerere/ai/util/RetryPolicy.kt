package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Headers
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * AI 请求重试策略（对齐 DSH `@deepseek-ai/dsh-llm-retry` 的默认规范）：
 * - 默认最多重试 5 次（共 6 次尝试）
 * - 初始退避 500ms，上限 10s，对称 jitter 10%
 * - 可重试：429 / 5xx / 网络（IO）错误；鉴权/配置类（400/401/403/404）不重试
 * - 尊重服务端 `Retry-After` / `retry-after-ms`（≤ maxDelayMs 直接采用、不加 jitter）
 */
data class RetryPolicy(
    val maxRetries: Int = 5,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 10_000,
    val jitterRatio: Double = 0.1,
    val retryable: (Throwable) -> Boolean = { it.isRetryable() },
)

/** 当服务端要求的等待时间超过 [RetryPolicy.maxDelayMs] 时，normal 模式应停止重试的哨兵值。 */
const val RETRY_STOP_DELAY: Long = Long.MAX_VALUE

/** 判断异常是否属于「瞬态、可重试」：429 / 5xx / 网络 IO 错误。 */
fun Throwable.isRetryable(): Boolean = when (this) {
    is HttpException -> {
        val code = this.code
        code != null && (code == 429 || code in 500..599)
    }

    is IOException -> true
    else -> false
}

/**
 * 计算下一次重试的退避时长：
 * - 若服务端给出 [retryAfterMs] 且 ≤ maxDelayMs，则直接采用（不加 jitter）；
 * - 若 [retryAfterMs] > maxDelayMs，返回 [RETRY_STOP_DELAY]（normal 模式停止重试）；
 * - 否则使用有界指数退避 + 对称 jitter，结果封顶到 maxDelayMs。
 */
fun retryBackoffDelay(
    policy: RetryPolicy,
    attempt: Int,
    retryAfterMs: Long? = null,
    random: Random = Random.Default,
): Long {
    val maxDelay = policy.maxDelayMs.coerceAtLeast(1)
    if (retryAfterMs != null && retryAfterMs > 0) {
        return if (retryAfterMs > maxDelay) RETRY_STOP_DELAY else retryAfterMs
    }
    val exponential = cappedExponentialBackoff(policy.initialDelayMs, maxDelay, attempt)
    val jitter = 1.0 - policy.jitterRatio + 2.0 * policy.jitterRatio * random.nextDouble()
    return (exponential * jitter).toLong().coerceIn(1, maxDelay)
}

/**
 * 有界指数退避：`min(initial * 2^(attempt-1), max)`，避免位移/乘法溢出。
 */
internal fun cappedExponentialBackoff(initialDelayMs: Long, maxDelayMs: Long, attempt: Int): Long {
    val max = maxDelayMs.coerceAtLeast(1)
    var value = initialDelayMs.coerceIn(1, max)
    repeat(attempt - 1) {
        if (value >= max) return max
        value = if (value > max / 2) max else value * 2
    }
    return value.coerceAtMost(max)
}

/**
 * 通用重试执行器：包住一次可重试调用，按 [RetryPolicy] 决定是否退避后重试。
 * - 取消（CancellationException）立即透传，不重试。
 * - 超过 maxRetries 或不可重试时抛出原始异常。
 */
suspend fun <T> retryWithPolicy(
    policy: RetryPolicy = RetryPolicy(),
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= policy.maxRetries || !policy.retryable(e)) throw e
            attempt++
            val retryAfterMs = (e as? HttpException)?.retryAfterMs
            val delayMs = retryBackoffDelay(policy, attempt, retryAfterMs)
            if (delayMs == RETRY_STOP_DELAY) throw e
            onRetry(attempt, delayMs, e)
            delay(delayMs)
        }
    }
}

/**
 * 从响应头解析服务端建议的重试等待时长（毫秒）：
 * - `retry-after-ms`：直接为毫秒数；
 * - `Retry-After`：为秒数或 HTTP 日期；秒数转毫秒，日期按「距今剩余」计算。
 * 均为空/非法时返回 null。
 */
fun Headers.retryAfterMillis(): Long? {
    this["retry-after-ms"]?.trim()?.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    val value = this["Retry-After"]?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    value.toLongOrNull()?.let { seconds -> return (seconds.coerceAtLeast(0)) * 1000L }
    return runCatching {
        val zoned = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
        (zoned.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}
