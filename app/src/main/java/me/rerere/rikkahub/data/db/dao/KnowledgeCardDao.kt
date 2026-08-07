package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.KnowledgeCardEntity

@Dao
interface KnowledgeCardDao {
    @Query("SELECT * FROM knowledge_cards WHERE archived = 0 ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<KnowledgeCardEntity>>

    @Query("SELECT * FROM knowledge_cards WHERE archived = 0 AND subject = :subject ORDER BY created_at DESC")
    fun getBySubjectFlow(subject: String): Flow<List<KnowledgeCardEntity>>

    @Query("SELECT DISTINCT subject FROM knowledge_cards WHERE archived = 0")
    fun getAllSubjectsFlow(): Flow<List<String>>

    @Query("SELECT * FROM knowledge_cards WHERE archived = 1 ORDER BY created_at DESC")
    suspend fun getArchived(): List<KnowledgeCardEntity>

    @Query("SELECT * FROM knowledge_cards WHERE archived = 0 AND (:subject IS NULL OR subject = :subject) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(subject: String?, limit: Int, offset: Int): List<KnowledgeCardEntity>

    @Query("SELECT COUNT(*) FROM knowledge_cards WHERE archived = 0 AND (:subject IS NULL OR subject = :subject)")
    suspend fun countActive(subject: String?): Int

    @Query("UPDATE knowledge_cards SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE knowledge_cards SET archived = 0 WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM knowledge_cards WHERE id = :id")
    suspend fun getById(id: String): KnowledgeCardEntity?

    @Query("SELECT * FROM knowledge_cards WHERE subject = :subject ORDER BY created_at DESC")
    suspend fun getBySubject(subject: String): List<KnowledgeCardEntity>

    @Query("SELECT * FROM knowledge_cards ORDER BY review_count ASC, last_reviewed_at ASC LIMIT :limit")
    suspend fun getDueForReview(limit: Int = 20): List<KnowledgeCardEntity>

    @Query("SELECT * FROM knowledge_cards WHERE archived = 0 AND (concept LIKE '%' || :query || '%' OR explanation LIKE '%' || :query || '%' OR memory_aid LIKE '%' || :query || '%') ORDER BY created_at DESC")
    suspend fun search(query: String): List<KnowledgeCardEntity>

    @Insert
    suspend fun insert(entity: KnowledgeCardEntity)

    @Update
    suspend fun update(entity: KnowledgeCardEntity)

    @Query("DELETE FROM knowledge_cards WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_cards WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}