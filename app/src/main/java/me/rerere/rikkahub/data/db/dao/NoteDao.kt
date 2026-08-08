package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.NoteEntity

@Dao
interface NoteDao {
    @Query("SELECT * FROM study_notes WHERE archived = 0 ORDER BY updated_at DESC")
    fun getAllFlow(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM study_notes WHERE archived = 0 AND category = :category ORDER BY updated_at DESC")
    fun getByCategoryFlow(category: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM study_notes WHERE archived = 0 AND subject = :subject ORDER BY updated_at DESC")
    fun getBySubjectFlow(subject: String): Flow<List<NoteEntity>>

    @Query("SELECT DISTINCT subject FROM study_notes WHERE archived = 0")
    fun getAllSubjectsFlow(): Flow<List<String>>

    @Query("SELECT * FROM study_notes WHERE archived = 1 ORDER BY updated_at DESC")
    suspend fun getArchived(): List<NoteEntity>

    @Query("SELECT * FROM study_notes WHERE archived = 0 AND (:subject IS NULL OR subject = :subject) ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(subject: String?, limit: Int, offset: Int): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM study_notes WHERE archived = 0 AND (:subject IS NULL OR subject = :subject)")
    suspend fun countActive(subject: String?): Int

    @Query("UPDATE study_notes SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE study_notes SET archived = 0 WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM study_notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM study_notes WHERE archived = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY updated_at DESC")
    suspend fun search(query: String): List<NoteEntity>

    @Insert
    suspend fun insert(entity: NoteEntity)

    @Update
    suspend fun update(entity: NoteEntity)

    @Query("DELETE FROM study_notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM study_notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}