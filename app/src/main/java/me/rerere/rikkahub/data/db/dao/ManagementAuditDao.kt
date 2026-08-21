package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ManagementAuditEntity

@Dao
interface ManagementAuditDao {
    @Insert
    suspend fun insert(entity: ManagementAuditEntity)

    @Query("SELECT * FROM management_audit ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ManagementAuditEntity>>

    @Query("SELECT * FROM management_audit ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ManagementAuditEntity>

    @Query("DELETE FROM management_audit")
    suspend fun clearAll()
}
