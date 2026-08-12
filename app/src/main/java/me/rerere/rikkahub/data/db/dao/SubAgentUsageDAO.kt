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

    /** 子代理用量按模型聚合（次数 + prompt+completion token），由高到低。model_id 为空（继承主模型）保留空串，由 UI 归入「其他」。 */
    @Query(
        "SELECT COALESCE(model_id, '') AS modelId, " +
            "COUNT(*) AS count, " +
            "COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens " +
            "FROM subagent_task_usage " +
            "GROUP BY modelId ORDER BY count DESC"
    )
    suspend fun getModelUsage(): List<ModelUsageEntry>

    /** 子代理用量按日 + 模型聚合，供趋势图合并；created_at 为 epoch 毫秒，转本地日期与主聊天对齐。 */
    @Query(
        "SELECT substr(datetime(created_at / 1000, 'unixepoch', 'localtime'), 1, 10) AS day, " +
            "COALESCE(model_id, '') AS modelId, " +
            "COUNT(*) AS count, " +
            "COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens " +
            "FROM subagent_task_usage " +
            "WHERE created_at >= :startMillis " +
            "GROUP BY day, modelId"
    )
    suspend fun getTrendByModel(startMillis: Long): List<DayModelUsage>

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
