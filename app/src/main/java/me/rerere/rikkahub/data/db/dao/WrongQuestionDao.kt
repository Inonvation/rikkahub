package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WrongQuestionEntity

@Dao
interface WrongQuestionDao {
    @Query("SELECT * FROM wrong_questions WHERE archived = 0 ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<WrongQuestionEntity>>

    @Query("SELECT * FROM wrong_questions WHERE archived = 0 AND subject = :subject ORDER BY created_at DESC")
    fun getBySubjectFlow(subject: String): Flow<List<WrongQuestionEntity>>

    @Query("SELECT DISTINCT subject FROM wrong_questions WHERE archived = 0")
    fun getAllSubjectsFlow(): Flow<List<String>>

    @Query("SELECT * FROM wrong_questions WHERE archived = 1 ORDER BY created_at DESC")
    suspend fun getArchived(): List<WrongQuestionEntity>

    @Query("SELECT * FROM wrong_questions WHERE archived = 0 AND (:subject IS NULL OR subject = :subject) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(subject: String?, limit: Int, offset: Int): List<WrongQuestionEntity>

    @Query("SELECT COUNT(*) FROM wrong_questions WHERE archived = 0 AND (:subject IS NULL OR subject = :subject)")
    suspend fun countActive(subject: String?): Int

    @Query("SELECT * FROM wrong_questions WHERE archived = 0 AND (:subject IS NULL OR subject = :subject) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandom(subject: String?, limit: Int): List<WrongQuestionEntity>

    @Query("UPDATE wrong_questions SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE wrong_questions SET archived = 0 WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM wrong_questions WHERE id = :id")
    suspend fun getById(id: String): WrongQuestionEntity?

    @Query("SELECT * FROM wrong_questions WHERE subject = :subject ORDER BY created_at DESC")
    suspend fun getBySubject(subject: String): List<WrongQuestionEntity>

    @Query("SELECT * FROM wrong_questions ORDER BY review_count ASC, last_reviewed_at ASC LIMIT :limit")
    suspend fun getDueForReview(limit: Int = 20): List<WrongQuestionEntity>

    @Query("SELECT * FROM wrong_questions WHERE archived = 0 AND (title LIKE '%' || :query || '%' OR question LIKE '%' || :query || '%' OR answer LIKE '%' || :query || '%' OR solution LIKE '%' || :query || '%' OR knowledge_points LIKE '%' || :query || '%') ORDER BY created_at DESC")
    suspend fun search(query: String): List<WrongQuestionEntity>

    @Insert
    suspend fun insert(entity: WrongQuestionEntity)

    @Update
    suspend fun update(entity: WrongQuestionEntity)

    @Query("DELETE FROM wrong_questions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM wrong_questions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}