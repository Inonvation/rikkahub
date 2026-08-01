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
 * 为了避免每次检索都从 Room 全量加载知识库 chunk 并反复做 ByteArray → FloatArray 反序列化，
 * 内部维护一个按 knowledgeBaseId 缓存的内存索引。缓存使用 LRU 策略，默认最多保留 5 个知识库。
 */
class VectorStore(
    private val chunkDao: KnowledgeChunkDao,
    maxCachedBases: Int = DEFAULT_MAX_CACHED_BASES,
) {

    /**
     * 缓存项：只保留检索需要的字段，embedding 已经是反序列化后的 FloatArray。
     */
    private data class CachedVector(
        val chunkId: String,
        val content: String,
        val embedding: FloatArray,
        val documentId: String,
        val chunkIndex: Int,
        val metadata: String,
    ) {
        fun toChunkEntity(knowledgeBaseId: String): KnowledgeChunkEntity =
            KnowledgeChunkEntity(
                id = chunkId,
                documentId = documentId,
                knowledgeBaseId = knowledgeBaseId,
                chunkIndex = chunkIndex,
                content = content,
                embedding = null, // 缓存命中时不需要再序列化回去
                tokenCount = 0,
                metadata = metadata,
            )
    }

    /**
     * 获取指定 chunk 的 embedding 向量，用于 MMR 多样性计算。
     */
    fun getEmbedding(knowledgeBaseId: String, chunkId: String): FloatArray? {
        synchronized(cacheLock) {
            return cache[knowledgeBaseId]?.find { it.chunkId == chunkId }?.embedding
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

        val scored = cachedVectors.mapNotNull { cached ->
            val score = Similarity.cosineSimilarity(queryEmbedding, cached.embedding)
            if (score.isNaN()) return@mapNotNull null
            cached to score
        }

        return scored
            .sortedByDescending { it.second }
            .take(topK)
            .mapIndexed { index, (cached, score) ->
                SearchResult(
                    chunk = cached.toChunkEntity(knowledgeBaseId),
                    score = score,
                    rank = index + 1,
                )
            }
    }

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

        val chunks = chunkDao.getByKnowledgeBaseId(knowledgeBaseId)
        val vectors = chunks.mapNotNull { chunk ->
            val embedding = chunk.embedding?.toFloatArray() ?: return@mapNotNull null
            CachedVector(
                chunkId = chunk.id,
                content = chunk.content,
                embedding = embedding,
                documentId = chunk.documentId,
                chunkIndex = chunk.chunkIndex,
                metadata = chunk.metadata,
            )
        }

        synchronized(cacheLock) {
            cache[knowledgeBaseId] = vectors
        }
        return vectors
    }

    companion object {
        const val DEFAULT_MAX_CACHED_BASES = 5
    }
}
