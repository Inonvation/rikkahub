package me.rerere.knowledge.data.repository

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.knowledge.data.dao.KnowledgeDocumentDao
import me.rerere.knowledge.data.entity.KnowledgeDocumentEntity

class KnowledgeDocumentRepository(
    private val dao: KnowledgeDocumentDao,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(
        knowledgeBaseId: String,
        fileName: String,
        fileType: String,
        filePath: String,
        fileSize: Long = 0,
    ): KnowledgeDocumentEntity {
        val entity = KnowledgeDocumentEntity(
            id = Uuid.random().toString(),
            knowledgeBaseId = knowledgeBaseId,
            fileName = fileName,
            fileType = fileType,
            filePath = filePath,
            fileSize = fileSize,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun update(entity: KnowledgeDocumentEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun getById(id: String) = dao.getById(id)

    suspend fun getByFileHashAndKnowledgeBaseId(fileHash: String, knowledgeBaseId: String) =
        dao.getByFileHashAndKnowledgeBaseId(fileHash, knowledgeBaseId)

    fun getByKnowledgeBaseId(knowledgeBaseId: String) = dao.getByKnowledgeBaseId(knowledgeBaseId)

    suspend fun getByKnowledgeBaseIdList(knowledgeBaseId: String) = dao.getByKnowledgeBaseIdList(knowledgeBaseId)

    suspend fun updateStatus(id: String, status: String, error: String? = null) =
        dao.updateStatus(id, status, error)

    suspend fun updateChunkCount(id: String, chunkCount: Int, status: String) =
        dao.updateChunkCount(id, chunkCount, status)

    suspend fun deleteByKnowledgeBaseId(knowledgeBaseId: String) =
        dao.deleteByKnowledgeBaseId(knowledgeBaseId)
}