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

    @Query("SELECT * FROM study_notes WHERE archived = 1 ORDER BY updated_at DESC")
    suspend fun getArchived(): List<NoteEntity>

    @Query("UPDATE study_notes SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE study_notes SET archived = 0 WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM study_notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM study_notes WHERE archived = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY updated_at DESC")
    suspend fun search(query: String): List<NoteEntity>

    @Query("SELECT DISTINCT category FROM study_notes ORDER BY category")
    suspend fun getAllCategories(): List<String>

    @Insert
    suspend fun insert(entity: NoteEntity)

    @Update
    suspend fun update(entity: NoteEntity)

    @Query("DELETE FROM study_notes WHERE id = :id")
    suspend fun deleteById(id: String)
}