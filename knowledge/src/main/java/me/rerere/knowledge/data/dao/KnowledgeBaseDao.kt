package me.rerere.knowledge.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import kotlinx.coroutines.flow.Flow
import me.rerere.knowledge.data.entity.KnowledgeBaseEntity
import me.rerere.knowledge.data.entity.KnowledgeBaseWithDocumentCount

@Dao
interface KnowledgeBaseDao {
    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT kb.*,
               (SELECT COUNT(*) FROM knowledge_document kd WHERE kd.knowledge_base_id = kb.id) as document_count,
               (SELECT COUNT(*) FROM knowledge_chunk kc WHERE kc.knowledge_base_id = kb.id) as chunk_count
        FROM knowledge_base kb
        ORDER BY kb.updated_at DESC
    """)
    fun getAllWithDocumentCount(): Flow<List<KnowledgeBaseWithDocumentCount>>

    @Query("SELECT * FROM knowledge_base ORDER BY updated_at DESC")
    fun getAll(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_base WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_base WHERE id = :id")
    fun getByIdFlow(id: String): Flow<KnowledgeBaseEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnowledgeBaseEntity)

    @androidx.room.Update
    suspend fun update(entity: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_base WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE knowledge_base SET updated_at = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM knowledge_base")
    suspend fun count(): Int
}