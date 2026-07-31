package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface KeyRoulette {
    /**
     * 选择本次请求使用的 key。
     *
     * @param multipleKeys 是否开启多 key 轮询：
     *  - false：把 [keys] 当成单个 key 原样返回（不做拆分/轮询）
     *  - true：拆分后轮询，并跳过冷却中的 key（除非全部冷却）
     */
    fun next(keys: String, providerId: String = "", multipleKeys: Boolean = true): String

    /** 标记某个 key 请求失败，进入冷却，冷却期内 [next] 不再选中它 */
    fun markFailed(keys: String, providerId: String, key: String)

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * 顺序轮询，持久化存储到 cacheDir/key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String, multipleKeys: Boolean): String {
        if (!multipleKeys) return keys.trim()
        val keyList = splitApiKeys(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys.trim()
        }
    }

    override fun markFailed(keys: String, providerId: String, key: String) {
        // 无状态实现，不持久化，无需处理
    }
}

private const val CACHE_FILE = "key_roulette.json"
private const val COOLDOWN_MS = 60 * 1000L // 坏 key 冷却 60s
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object RouletteFileLock

@Serializable
private data class ProviderKeyState(
    val index: Int = 0,
    val cooldowns: Map<String, Long> = emptyMap(),
)

private typealias RouletteCache = Map<String, ProviderKeyState>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String, multipleKeys: Boolean): String {
        if (!multipleKeys) return keys.trim()
        val keyList = splitApiKeys(keys)
        if (keyList.isEmpty()) return keys.trim()
        if (keyList.size == 1) return keyList.first()

        synchronized(RouletteFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = allCache[providerId] ?: ProviderKeyState()

            val validCooldowns = providerCache.cooldowns
                .filter { (k, _) -> k in keyList && k.isNotBlank() }

            val activeKeys = keyList.filter { (validCooldowns[it] ?: 0L) <= now }

            val startIndex = providerCache.index % keyList.size
            val selectedIndex: Int
            val selectedKey: String
            val updatedCooldowns: Map<String, Long>

            if (activeKeys.isNotEmpty()) {
                // 从当前索引开始，找到第一个可用的 key
                selectedIndex = generateSequence(startIndex) { (it + 1) % keyList.size }
                    .take(keyList.size)
                    .first { keyList[it] in activeKeys }
                selectedKey = keyList[selectedIndex]
                updatedCooldowns = validCooldowns
            } else {
                // 全部冷却：选最早解冻的，并清除其冷却状态
                selectedIndex = keyList.indices.minByOrNull {
                    validCooldowns[keyList[it]] ?: Long.MAX_VALUE
                } ?: 0
                selectedKey = keyList[selectedIndex]
                updatedCooldowns = validCooldowns.toMutableMap().apply {
                    remove(selectedKey)
                }
            }

            allCache[providerId] = ProviderKeyState(
                index = (selectedIndex + 1) % keyList.size,
                cooldowns = updatedCooldowns,
            )

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.cooldowns.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selectedKey
        }
    }

    override fun markFailed(keys: String, providerId: String, key: String) {
        if (key.isBlank()) return

        synchronized(RouletteFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = allCache[providerId] ?: ProviderKeyState()

            allCache[providerId] = providerCache.copy(
                cooldowns = providerCache.cooldowns.toMutableMap().apply {
                    this[key] = now + COOLDOWN_MS
                }
            )

            saveCache(allCache)
        }
    }

    private fun loadCache(): RouletteCache {
        return try {
            val file = File(context.cacheDir, CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString<RouletteCache>(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: RouletteCache) {
        try {
            File(context.cacheDir, CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
