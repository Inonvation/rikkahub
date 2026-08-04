package me.rerere.knowledge.vector

import me.rerere.knowledge.data.dao.KnowledgeChunkDao
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.knowledge.vector.toFloatArray

data class SearchResult(
    val chunk: KnowledgeChunkEntity,
    val score: Float,
    val rank: Int,
)

/**
 * 向量存储与检索。
 *
 * 纯余弦全扫描需要目标知识库全部 chunk 的 embedding，无法按文档跳过，
 * 因此内存开销由「按知识库缓存」这一层约束（LRU，默认最多 10 个知识库）。
 * 性能优化的重点是：
 * 1. 检索时单次线性扫缓存，避免重复反序列化。
 * 2. [getEmbeddings] 批量取 embedding，供 MMR 预计算相似度矩阵，避免逐 chunk 反复扫描。
 */
class VectorStore(
    private val chunkDao: KnowledgeChunkDao,
    maxCachedBases: Int = DEFAULT_MAX_CACHED_BASES,
) {

    /**
     * 缓存项：只保留检索需要的字段（id + embedding + 少量定位字段），
     * 不存 content 全文——内容在算完 topK 后回表取，减少首检内存占用。
     */
    private data class CachedVector(
        val chunkId: String,
        val embedding: FloatArray,
        val documentId: String,
        val chunkIndex: Int,
    )

    /**
     * 获取单个 chunk 的 embedding 向量（兼容旧调用，MMR 内已改用批量版）。
     */
    fun getEmbedding(knowledgeBaseId: String, chunkId: String): FloatArray? {
        synchronized(cacheLock) {
            return cache[knowledgeBaseId]?.find { it.chunkId == chunkId }?.embedding
        }
    }

    /**
     * 批量获取指定 chunk 的 embedding，一次线性扫缓存，避免逐 chunk 反复扫描。
     * 供 MMR 预计算相似度矩阵使用。
     */
    fun getEmbeddings(knowledgeBaseId: String, chunkIds: Collection<String>): Map<String, FloatArray> {
        val idSet = chunkIds.toSet()
        synchronized(cacheLock) {
            val cached = cache[knowledgeBaseId] ?: return emptyMap()
            return cached.asSequence()
                .filter { it.chunkId in idSet }
                .associate { it.chunkId to it.embedding }
        }
    }

    private val cache = object : LinkedHashMap<String, List<CachedVector>>(
        /* initialCapacity */ 16,
        /* loadFactor */ 0.75f,
        /* accessOrder */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedVector>>?): Boolean {
            return size > maxCachedBases
        }
    }

    private val cacheLock = Any()

    suspend fun search(
        queryEmbedding: FloatArray,
        knowledgeBaseId: String,
        topK: Int = 10,
    ): List<SearchResult> {
        val cachedVectors = getOrLoadCache(knowledgeBaseId)
        if (cachedVectors.isEmpty()) return emptyList()

        // 单次线性扫缓存，计算余弦相似度
        val scored = cachedVectors.mapNotNull { cached ->
            val score = Similarity.cosineSimilarity(queryEmbedding, cached.embedding)
            if (score.isNaN()) return@mapNotNull null
            cached to score
        }

        val top = scored
            .sortedByDescending { it.second }
            .take(topK)

        if (top.isEmpty()) return emptyList()

        // 对 topK 条回表取完整实体（缓存只存了 embedding + 定位字段，不含 content）
        val fullById = chunkDao.getByIds(top.map { it.first.chunkId }).associateBy { it.id }
        return top.mapIndexed { index, (cached, score) ->
            SearchResult(
                chunk = fullById[cached.chunkId] ?: cached.toChunkEntity(knowledgeBaseId),
                score = score,
                rank = index + 1,
            )
        }
    }

    /**
     * 由缓存项回退构造实体（仅当回表失败时使用；content 为空串，下游应尽量避免触发）。
     */
    private fun CachedVector.toChunkEntity(knowledgeBaseId: String): KnowledgeChunkEntity =
        KnowledgeChunkEntity(
            id = chunkId,
            documentId = documentId,
            knowledgeBaseId = knowledgeBaseId,
            chunkIndex = chunkIndex,
            content = "",
        )

    /**
     * 清除指定知识库的缓存。文档导入、重处理、删除后必须调用，否则检索会拿到旧数据。
     */
    fun invalidateCache(knowledgeBaseId: String) {
        synchronized(cacheLock) {
            cache.remove(knowledgeBaseId)
        }
    }

    /**
     * 清除所有缓存。知识库删除等场景使用。
     */
    fun invalidateAll() {
        synchronized(cacheLock) {
            cache.clear()
        }
    }

    private suspend fun getOrLoadCache(knowledgeBaseId: String): List<CachedVector> {
        synchronized(cacheLock) {
            val cached = cache[knowledgeBaseId]
            if (cached != null) return cached
        }

        // 只读 id + embedding + 定位字段（轻量投影），不读 content 全文
        val chunks = chunkDao.getVectorsByKnowledgeBaseId(knowledgeBaseId)
        val vectors = chunks.mapNotNull { chunk ->
            val embedding = chunk.embedding?.toFloatArray() ?: return@mapNotNull null
            CachedVector(
                chunkId = chunk.id,
                embedding = embedding,
                documentId = chunk.documentId,
                chunkIndex = chunk.chunkIndex,
            )
        }

        synchronized(cacheLock) {
            cache[knowledgeBaseId] = vectors
        }
        return vectors
    }

    companion object {
        const val DEFAULT_MAX_CACHED_BASES = 10
    }
}
