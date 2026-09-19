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

        /**
         * 记忆总量 ≤ 该值直接全量注入，不做检索。
         *
         * 降敏（2026-09）：旧值 6 意味着「记忆少 = 每轮全量出现」，恰是记忆感知最敏感的时期
         * （用户刚启用记忆、每条都跟当前话题无关却持续出现在上下文里）。降到 2 只保留
         * "只有一两条记忆时检索无意义"的工程收益，不再让少量无关记忆每轮刷存在感。
         */
        const val MEMORY_FULL_INJECTION_THRESHOLD = 2

        /** 记忆检索词最长字符数，控制 jieba 分词成本 */
        const val MEMORY_QUERY_MAX_CHARS = 200

        /** 去重用的内容归一化：trim、折叠连续空白、忽略大小写 */
        private fun normalizeContent(content: String): String =
            content.trim().replace(Regex("\\s+"), " ").lowercase()

        /**
         * 记忆检索词提取（纯函数）：
         * 主查询 = 最新一条 USER 消息文本；太短/无实词（如「那这个呢？」）则回退拼接
         * 最近 3 条 USER 消息，给 FTS 更多检索面。
         *
         * 降敏（2026-09）：移除「副查询 = 最新 ASSISTANT 回答文本」。该设计是想提升召回，
         * 实际形成自我强化回路——模型上一轮展开过的话题（无论与用户事实是否相关）都会成为
         * 下一轮的检索词，把该话题的记忆持续拉回上下文，表现为"AI 总在提记忆"。
         * 用户消息才是记忆需求的唯一可靠信号。
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
            return listOfNotNull(primary)
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
     * - 否则：按 USER 查询做 FTS 检索（BM25 相关度序）；
     * - **无命中 → 返回空列表，本轮不注入任何记忆块**（降敏 2026-09）。
     *
     * 降敏变更（原实现见 git 历史）：
     * 1. 删「恒兜底最近 2 条」——把与当前话题无关的最新记忆每轮塞进上下文，
     *    是"AI 总在提记忆"的直接来源；
     * 2. 删「检索不足 topK 用最新补齐」——原注释写着"保证 system prompt 始终有记忆"，
     *    与「相关才注入」的产品意图相反；
     * 3. FTS 失败 → 空列表（不再降级全量）：宁可不注入，也不要让无关记忆刷屏。
     * 调用侧 ChatService.loadMemoriesForGeneration 的全量兜底同步移除。
     */
    suspend fun getRelevantMemories(assistantId: String, queries: List<String>): List<AssistantMemory> {
        val all = getMemoriesOfAssistant(assistantId)
        if (all.size <= MEMORY_FULL_INJECTION_THRESHOLD) return all
        if (queries.none { it.isNotBlank() }) return emptyList()

        val result = LinkedHashMap<Int, AssistantMemory>()
        queries.filter { it.isNotBlank() }.forEach { query ->
            searchMemories(assistantId, query, MEMORY_SEARCH_TOP_K).forEach { memory ->
                result.putIfAbsent(memory.id, memory)
            }
        }
        return result.values.take(MEMORY_SEARCH_TOP_K)
    }
}
