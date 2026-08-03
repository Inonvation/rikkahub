package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.GroupEntity

@Dao
interface GroupDAO {
    @Query("SELECT * FROM groupentity ORDER BY update_at DESC")
    fun getAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groupentity WHERE id = :id")
    fun getFlow(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groupentity WHERE id = :id")
    suspend fun getById(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)

    @Update
    suspend fun update(group: GroupEntity)

    @Query("DELETE FROM groupentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE groupentity SET update_at = :updateAt WHERE id = :id")
    suspend fun touchUpdateAt(id: String, updateAt: Long)
}
