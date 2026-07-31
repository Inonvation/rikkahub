package me.rerere.ai.util

/**
 * 单个 key 的轮询状态。
 * 序列化进 lru_key_roulette.json，跨 Provider 实例与进程共享。
 */
@kotlinx.serialization.Serializable
data class KeyState(
    val lastUsed: Long = 0L,
    val cooldownUntil: Long = 0L, // 0 = 不在冷却中
)

/**
 * LRU 轮询的核心选择逻辑，纯函数便于 JVM 单测。
 *
 * 规则：
 * - 剔除不在当前 key 列表、或已超过 [expireMs] 未使用的记录
 * - 优先从未用过的 key，其次选最久未用
 * - 跳过冷却中的 key（cooldownUntil > now）
 * - 全部 key 都在冷却中时，兜底选最早解冻的那个并清除冷却，避免死循环
 */
internal object KeyRouletteSelector {
    fun select(
        keyList: List<String>,
        providerCache: Map<String, KeyState>,
        now: Long,
        expireMs: Long,
    ): Pair<String, Map<String, KeyState>> {
        val valid = providerCache
            .filter { (k, s) -> k in keyList && now - s.lastUsed < expireMs }
            .toMutableMap()

        val active = keyList.filter { (valid[it]?.cooldownUntil ?: 0L) <= now }

        val selected: String
        if (active.isEmpty()) {
            // 全部冷却：选最早解冻的，清除冷却（宁可多试一次，也不要请求卡死）
            selected = keyList.minByOrNull { valid[it]?.cooldownUntil ?: 0L } ?: keyList.first()
            valid[selected] = KeyState(now, 0L)
        } else {
            selected = active.firstOrNull { it !in valid }
                ?: active.minByOrNull { valid[it]!!.lastUsed }!!
            valid[selected] = valid[selected]?.copy(lastUsed = now) ?: KeyState(now, 0L)
        }
        return selected to valid
    }

    fun markFailed(
        cache: Map<String, KeyState>,
        key: String,
        now: Long,
        cooldownMs: Long,
    ): Map<String, KeyState> {
        val m = cache.toMutableMap()
        m[key] = KeyState(lastUsed = m[key]?.lastUsed ?: now, cooldownUntil = now + cooldownMs)
        return m
    }
}
