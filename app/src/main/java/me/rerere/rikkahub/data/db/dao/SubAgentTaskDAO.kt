package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SubAgentTaskEntity

@Dao
interface SubAgentTaskDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubAgentTaskEntity)

    @Query("SELECT * FROM subagent_task WHERE task_id = :taskId")
    suspend fun getById(taskId: String): SubAgentTaskEntity?

    /** 某会话的全部任务，按创建时间倒序 */
    @Query("SELECT * FROM subagent_task WHERE parent_conv = :conversationId ORDER BY created_at DESC")
    suspend fun getByConversation(conversationId: String): List<SubAgentTaskEntity>

    /** 进程启动时恢复全部历史任务（按创建时间倒序，仅需要近期） */
    @Query("SELECT * FROM subagent_task ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SubAgentTaskEntity>

    @Query("DELETE FROM subagent_task WHERE parent_conv = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM subagent_task WHERE task_id = :taskId")
    suspend fun deleteById(taskId: String)
}
