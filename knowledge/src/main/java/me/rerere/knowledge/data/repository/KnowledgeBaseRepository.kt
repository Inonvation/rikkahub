package me.rerere.knowledge.data.repository

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.knowledge.data.dao.KnowledgeBaseDao
import me.rerere.knowledge.data.entity.KnowledgeBaseEntity
import me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount

class KnowledgeBaseRepository(
    private val dao: KnowledgeBaseDao,
) {
    fun getAllWithDocumentCount(): Flow<List<KnowledgeBaseWithDocumentCount>> = dao.getAllWithDocumentCount()
    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(
        name: String,
        description: String = "",
        embeddingModelId: String? = null,
        rerankModelId: String? = null,
        chunkSize: Int = KnowledgeBaseEntity.DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = KnowledgeBaseEntity.DEFAULT_CHUNK_OVERLAP,
        chunkStrategy: String = KnowledgeBaseEntity.DEFAULT_CHUNK_STRATEGY,
        topK: Int = KnowledgeBaseEntity.DEFAULT_TOP_K,
        similarityThreshold: Float = 0f,
    ): KnowledgeBaseEntity {
        val entity = KnowledgeBaseEntity(
            id = Uuid.random().toString(),
            name = name,
            description = description,
            embeddingModelId = embeddingModelId,
            rerankModelId = rerankModelId,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
            chunkStrategy = chunkStrategy,
            topK = topK,
            similarityThreshold = similarityThreshold,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun update(entity: KnowledgeBaseEntity) = dao.update(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun updateTimestamp(id: String, timestamp: Long) = dao.updateTimestamp(id, timestamp)

    suspend fun getById(id: String) = dao.getById(id)

    fun getAll() = dao.getAll()

    fun getByIdFlow(id: String) = dao.getByIdFlow(id)
}