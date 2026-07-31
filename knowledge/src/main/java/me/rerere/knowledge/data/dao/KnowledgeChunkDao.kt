package me.rerere.knowledge.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity

@Dao
interface KnowledgeChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId ORDER BY document_id, chunk_index")
    suspend fun getByKnowledgeBaseId(knowledgeBaseId: String): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk WHERE id IN (:chunkIds)")
    suspend fun getByChunkIds(chunkIds: List<String>): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk WHERE document_id = :documentId ORDER BY chunk_index")
    suspend fun getByDocumentId(documentId: String): List<KnowledgeChunkEntity>

    @Query("DELETE FROM knowledge_chunk WHERE document_id = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("DELETE FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun deleteByKnowledgeBaseId(knowledgeBaseId: String)

    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun countByKnowledgeBaseId(knowledgeBaseId: String): Int

    /**
     * 根据 chunk 列表查询所属文档文件名，返回 chunkId -> fileName 映射。
     */
    @Query(
        "SELECT kc.id AS chunkId, kd.file_name AS fileName " +
            "FROM knowledge_chunk kc " +
            "JOIN knowledge_document kd ON kc.document_id = kd.id " +
            "WHERE kc.id IN (:chunkIds)"
    )
    suspend fun getDocumentNamesByChunkIds(chunkIds: List<String>): List<ChunkDocumentName>
}