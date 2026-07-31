package me.rerere.ai.util

/**
 * 带 HTTP 状态码的请求异常。
 * 只有 401（鉴权失败）与 429（限流）才值得换 key 重试；
 * 其余错误码（400/500 等）重试也大概率同样失败。
 */
class RetryableHttpException(
    val code: Int,
    message: String,
) : RuntimeException(message) {
    val retryable: Boolean get() = code == 401 || code == 429
}

/**
 * 选 key → 执行请求 → 401/429 时冷却当前 key 并换下一个重试，直到成功或全部 key 都失败。
 *
 * - 单 key（未开多 key 或只有一个 key）不重试，避免白费一次请求
 * - [markFailed] 会冷却坏 key，下一次 [next] 自动跳过它（除非全部冷却，走兜底）
 * - 重试耗尽后原样抛最后一次的 [RetryableHttpException]
 */
suspend fun <T> KeyRoulette.retryWithKeyFallback(
    keys: String,
    providerId: String,
    multipleKeys: Boolean = false,
    block: suspend (key: String) -> T,
): T {
    val keyList = splitApiKeys(keys)
    if (!multipleKeys || keyList.size < 2) {
        return block(next(keys, providerId, multipleKeys))
    }

    val startKey = next(keys, providerId, multipleKeys)
    val startIndex = keyList.indexOf(startKey).takeIf { it >= 0 } ?: 0
    var lastException: RetryableHttpException? = null

    for (i in keyList.indices) {
        val index = (startIndex + i) % keyList.size
        val key = keyList[index]
        try {
            return block(key)
        } catch (e: RetryableHttpException) {
            lastException = e
            if (!e.retryable) throw e
            markFailed(keys, providerId, key)
        }
    }

    throw lastException ?: IllegalStateException("All keys failed")
}
