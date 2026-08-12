package me.rerere.rikkahub.ui.pages.stats

import me.rerere.rikkahub.data.db.dao.DayModelUsage
import me.rerere.rikkahub.data.db.dao.ModelUsageEntry

/**
 * 将按 Model.id（Uuid）聚合的模型用量按显示名合并。
 *
 * 不同供应商可能配置同名模型（Uuid 不同、显示名相同），消息 JSON 里记录的是模型
 * 的唯一 id，直接按 id 聚合会在统计里出现多行名称相同的模型。这里把显示名相同的
 * 条目合并为一行，次数与 token 加总。
 *
 * 合并键对显示名做归一化（去首尾空白 + 转小写），所以「GPT-4o」与「gpt-4o」也视为
 * 同一模型；条目保留第一个遇到的原始显示名，避免界面出现小写变体。未匹配到当前配置
 * 的历史条目保留原 id，由 UI 侧的「其他」合并逻辑兜底。
 */
internal fun mergeModelUsageByDisplayName(
    entries: List<ModelUsageEntry>,
    names: Map<String, String>,
): List<ModelUsageEntry> {
    val nameByKey = HashMap<String, String>()
    val merged = LinkedHashMap<String, ModelUsageEntry>()
    entries.forEach { entry ->
        val displayName = names[entry.modelId] ?: entry.modelId
        val key = normalizeNameKey(displayName)
        val display = nameByKey.getOrPut(key) { displayName }
        val prev = merged[key]
        merged[key] = ModelUsageEntry(
            modelId = display,
            count = (prev?.count ?: 0) + entry.count,
            tokens = (prev?.tokens ?: 0) + entry.tokens,
        )
    }
    return merged.values.sortedByDescending { it.count }
}

/**
 * 趋势数据按 (日期, 显示名) 合并，保证堆叠柱状图中同名模型只出现一个分段。
 *
 * SQL 按 (day, modelId) 分组，同名模型在各自 Uuid 下各有一行；这里按日期 + 归一化
 * 显示名再次归并，次数与 token 加总。同一模型跨天统一使用第一个遇到的原始显示名，
 * 避免柱状图出现两个大小写不同的分段。
 */
internal fun mergeTrendByDisplayName(
    entries: List<DayModelUsage>,
    names: Map<String, String>,
): List<DayModelUsage> {
    val nameByKey = HashMap<String, String>()
    val merged = LinkedHashMap<Pair<String, String>, DayModelUsage>()
    entries.forEach { entry ->
        val displayName = names[entry.modelId] ?: entry.modelId
        val keyName = normalizeNameKey(displayName)
        val display = nameByKey.getOrPut(keyName) { displayName }
        val key = entry.day to keyName
        val prev = merged[key]
        merged[key] = DayModelUsage(
            day = entry.day,
            modelId = display,
            count = (prev?.count ?: 0) + entry.count,
            tokens = (prev?.tokens ?: 0) + entry.tokens,
        )
    }
    return merged.values.toList()
}

/** 合并键归一化：忽略首尾空白与大小写差异 */
private fun normalizeNameKey(name: String): String = name.trim().lowercase()
