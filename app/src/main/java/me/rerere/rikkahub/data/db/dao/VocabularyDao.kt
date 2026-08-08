package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.VocabularyEntity

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary WHERE archived = 0 ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE archived = 0 ORDER BY created_at DESC")
    suspend fun getAll(): List<VocabularyEntity>

    @Query("SELECT * FROM vocabulary WHERE archived = 0 ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<VocabularyEntity>

    @Query("SELECT COUNT(*) FROM vocabulary WHERE archived = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM vocabulary WHERE archived = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandom(limit: Int): List<VocabularyEntity>

    @Query("SELECT * FROM vocabulary WHERE archived = 1 ORDER BY created_at DESC")
    suspend fun getArchived(): List<VocabularyEntity>

    @Query("SELECT * FROM vocabulary WHERE archived = 0 AND word LIKE '%' || :query || '%' ORDER BY created_at DESC")
    suspend fun search(query: String): List<VocabularyEntity>

    @Query("UPDATE vocabulary SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE vocabulary SET archived = 0 WHERE id = :id")
    suspend fun restore(id: String)

    @Insert
    suspend fun insert(entity: VocabularyEntity)

    @Update
    suspend fun update(entity: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM vocabulary WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    suspend fun getById(id: String): VocabularyEntity?

    @Query("SELECT COUNT(*) FROM vocabulary WHERE word = :word COLLATE NOCASE")
    suspend fun countByWord(word: String): Int
}