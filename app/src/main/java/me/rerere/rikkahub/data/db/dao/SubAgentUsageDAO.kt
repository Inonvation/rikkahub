package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM subagent_task_usage WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

data class SubAgentTokenStats(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)
