package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val ftsManager: MemoryFtsManager,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"

        /** 相关记忆检索的 top-K 条数 */
        const val MEMORY_SEARCH_TOP_K = 5

        /** 恒兜底的最近 N 条记忆 */
        const val MEMORY_FALLBACK_RECENT_N = 2

        /** 记忆总量 ≤ 该值直接全量注入，不做检索（避免过度设计） */
        const val MEMORY_FULL_INJECTION_THRESHOLD = 6

        /** 记忆检索词最长字符数，控制 jieba 分词成本 */
        const val MEMORY_QUERY_MAX_CHARS = 200

        /** 去重用的内容归一化：trim、折叠连续空白、忽略大小写 */
        private fun normalizeContent(content: String): String =
            content.trim().replace(Regex("\\s+"), " ").lowercase()

        /**
         * 记忆检索词提取（多查询，纯函数）：
         * - 主查询 = 最新一条 USER 消息文本；太短/无实词（如「那这个呢？」）则回退拼接
         *   最近 3 条 USER 消息，给 FTS 更多检索面；
         * - 副查询 = 最新一条 ASSISTANT 回答文本：回答对话题的展开通常比单条用户输入更宽，
         *   两路并集提升召回（FTS 本地检索，零额外成本）。
         * 返回空列表 → 注入侧仅走「最近记忆」兜底。
         */
        fun extractMemoryQueries(messages: List<UIMessage>): List<String> {
            val userMessages = messages.filter { it.role == MessageRole.USER }
            if (userMessages.isEmpty()) return emptyList()
            val latest = userMessages.last().toText().trim()
            val primary = if (latest.isBlank() || latest.length < 4 || latest.none { it.isLetterOrDigit() }) {
                userMessages.takeLast(3)
                    .joinToString("\n") { it.toText().trim() }
                    .trim()
                    .takeIf { it.isNotBlank() }
            } else {
                latest.take(MEMORY_QUERY_MAX_CHARS)
            }
            val secondary = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.toText()?.trim()
                ?.takeIf { it.isNotBlank() && it != primary }
                ?.take(MEMORY_QUERY_MAX_CHARS)
            return listOfNotNull(primary, secondary)
        }
    }

    private fun MemoryEntity.toAssistantMemory() = AssistantMemory(
        id = id,
        content = content,
        category = MemoryCategory.fromNameOrNull(category),
        createdAt = createdAt.takeIf { it > 0 },
        updatedAt = updatedAt.takeIf { it > 0 },
    )

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { it.toAssistantMemory() }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { it.toAssistantMemory() }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { it.toAssistantMemory() }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { it.toAssistantMemory() }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        ftsManager.invalidate(assistantId)
    }

    /**
     * 更新记忆（校验归属：目标必须属于 [assistantId] 对应的记忆池，
     * 防止工具调用跨池误改其他助手的记忆）。
     */
    suspend fun updateContent(
        assistantId: String,
        id: Int,
        content: String,
        category: MemoryCategory? = null,
    ): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        if (old.assistantId != assistantId) {
            error("Memory record #$id not found in current memory space")
        }
        val newMemory = old.copy(
            content = content,
            category = (category?.name ?: old.category),
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        // 内容变了但 count 没变，对账发现不了，必须显式失效
        ftsManager.invalidate(newMemory.assistantId)
        return newMemory.toAssistantMemory()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        category: MemoryCategory? = null,
    ): AssistantMemory {
        // 去重守卫：模型自觉性不可靠，写入侧兜底拦截归一化后完全相同的重复条目
        val normalized = normalizeContent(content)
        getMemoriesOfAssistant(assistantId)
            .firstOrNull { normalizeContent(it.content) == normalized }
            ?.let { return it }
        val now = System.currentTimeMillis()
        val newMemory = MemoryEntity(
            assistantId = assistantId,
            content = content.trim(),
            category = category?.name,
            createdAt = now,
            updatedAt = now,
        )
        val id = memoryDAO.insertMemory(newMemory).toInt()
        ftsManager.invalidate(assistantId)
        return newMemory.copy(id = id).toAssistantMemory()
    }

    /** 删除记忆（校验归属，防止跨池误删）。 */
    suspend fun deleteMemoryInScope(assistantId: String, id: Int) {
        val memory = memoryDAO.getMemoryById(id) ?: return
        if (memory.assistantId != assistantId) {
            error("Memory record #$id not found in current memory space")
        }
        memoryDAO.deleteMemory(id)
        ftsManager.invalidate(assistantId)
    }

    suspend fun deleteMemory(id: Int) {
        val memory = memoryDAO.getMemoryById(id)
        memoryDAO.deleteMemory(id)
        memory?.let { ftsManager.invalidate(it.assistantId) }
    }

    /**
     * FTS 关键词检索（IO 线程）。FTS 不可用/查询失败 → 空列表，不抛。
     * 命中按 BM25 相关度排序。
     */
    suspend fun searchMemories(assistantId: String, query: String, topK: Int): List<AssistantMemory> =
        withContext(Dispatchers.IO) {
            if (!ftsManager.ensureIndex()) return@withContext emptyList()
            runCatching {
                reconcile(assistantId)
                val hits = ftsManager.search(assistantId, query, topK)
                if (hits.isEmpty()) return@withContext emptyList()
                val byId = memoryDAO.getMemoriesByIds(hits.map { it.memoryId }).associateBy { it.id }
                hits.mapNotNull { byId[it.memoryId]?.toAssistantMemory() }
            }.getOrDefault(emptyList())
        }

    /** 对账：memoryentity 与 memory_fts 数量不一致则重建该 assistant 的索引。 */
    private suspend fun reconcile(assistantId: String) {
        val real = memoryDAO.countByAssistantId(assistantId).toLong()
        if (real != ftsManager.countIndexed(assistantId)) {
            ftsManager.rebuild(assistantId, memoryDAO.getMemoriesOfAssistant(assistantId))
        }
    }

    /**
     * 生成时的记忆注入入口：
     * - 记忆 ≤ MEMORY_FULL_INJECTION_THRESHOLD → 全量注入，不检索；
     * - 否则：多查询并集检索（每个查询各自 top-K，按查询优先级合并，主查询命中排前）+
     *   恒带最近 N 条兜底；检索不足 topK 用最近记忆补齐；
     * - FTS 任何失败都降级为「最近记忆」，绝不崩、绝不空。
     */
    suspend fun getRelevantMemories(assistantId: String, queries: List<String>): List<AssistantMemory> {
        val all = getMemoriesOfAssistant(assistantId)
        if (all.size <= MEMORY_FULL_INJECTION_THRESHOLD) return all

        val recent = all.sortedByDescending { it.updatedAt ?: it.createdAt ?: 0L }
        val result = LinkedHashMap<Int, AssistantMemory>()

        // 多查询并集：副查询只补新 id（保持主查询的 BM25 优先序），扩大检索面零额外成本
        queries.filter { it.isNotBlank() }.forEach { query ->
            searchMemories(assistantId, query, MEMORY_SEARCH_TOP_K).forEach { memory ->
                result.putIfAbsent(memory.id, memory)
            }
        }
        // 兜底：最近 N 条恒在
        recent.take(MEMORY_FALLBACK_RECENT_N).forEach { result[it.id] = it }
        // 检索不足 topK → 用最新记忆补齐，保证 system prompt 始终有记忆
        if (result.size < MEMORY_SEARCH_TOP_K) {
            for (m in recent) {
                if (result.size >= MEMORY_SEARCH_TOP_K) break
                result.putIfAbsent(m.id, m)
            }
        }
        // 总量封顶 topK + fallbackN
        return result.values.take(MEMORY_SEARCH_TOP_K + MEMORY_FALLBACK_RECENT_N)
    }
}
