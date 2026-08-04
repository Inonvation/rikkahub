package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SubAgentUsageEntity

@Dao
interface SubAgentUsageDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubAgentUsageEntity)

    @Query(
        "SELECT " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens " +
            "FROM subagent_task_usage"
    )
    suspend fun getTokenStats(): SubAgentTokenStats

    /** 某会话的子代理用量明细（每任务一行），供会话费用/缓存统计逐条按实际模型计费。 */
    @Query("SELECT * FROM subagent_task_usage WHERE conversation_id = :conversationId")
    fun observeByConversation(conversationId: String): Flow<List<SubAgentUsageEntity>>

    @Query("DELETE FROM subagent_task_usage WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

data class SubAgentTokenStats(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)
