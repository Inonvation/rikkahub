package me.rerere.knowledge.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.knowledge.data.entity.KnowledgeDocumentEntity

@Dao
interface KnowledgeDocumentDao {
    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :knowledgeBaseId ORDER BY created_at DESC")
    fun getByKnowledgeBaseId(knowledgeBaseId: String): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :knowledgeBaseId ORDER BY created_at DESC")
    suspend fun getByKnowledgeBaseIdList(knowledgeBaseId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_document WHERE id = :id")
    suspend fun getById(id: String): KnowledgeDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnowledgeDocumentEntity)

    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_document WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun deleteByKnowledgeBaseId(knowledgeBaseId: String)

    @Query("UPDATE knowledge_document SET status = :status, error = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_document SET chunk_count = :chunkCount, status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateChunkCount(id: String, chunkCount: Int, status: String, updatedAt: Long = System.currentTimeMillis())
}