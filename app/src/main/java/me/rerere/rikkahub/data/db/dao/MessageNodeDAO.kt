package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>

    @RawQuery
    suspend fun getTrendByModelRaw(query: SupportSQLiteQuery): List<DayModelUsage>

    @RawQuery
    suspend fun getModelUsageRaw(query: SupportSQLiteQuery): List<ModelUsageEntry>

    @RawQuery
    suspend fun getAssistantUsageRaw(query: SupportSQLiteQuery): List<AssistantUsageEntry>

    @RawQuery
    suspend fun getModelNameSnapshotsRaw(query: SupportSQLiteQuery): List<ModelNameSnapshot>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

/** 单日单模型的用量（堆叠柱状图按模型分段的数据源） */
data class DayModelUsage(val day: String = "", val modelId: String = "", val count: Int = 0, val tokens: Long = 0)

/** 模型维度使用率（按 modelId 分组） */
data class ModelUsageEntry(val modelId: String = "", val count: Int = 0, val tokens: Long = 0)

/** 助手维度使用率（按 assistant_id 分组） */
data class AssistantUsageEntry(val assistantId: String = "", val count: Int = 0, val tokens: Long = 0)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )

// 按日+模型聚合用量（堆叠柱状图数据源）：消息数与活跃 token 消耗（prompt + completion）。
// 只取最近一年（12 周与 6 个月粒度均覆盖），避免全表 json_each 展开。
private val TREND_BY_MODEL_SQL = SimpleSQLiteQuery(
    "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
        "json_extract(j.value, '$.modelId') AS modelId, " +
        "COUNT(*) AS count, " +
        "COALESCE(SUM(" +
        "  CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER) + " +
        "  CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)" +
        "), 0) AS tokens " +
        "FROM message_node mn, json_each(mn.messages) j " +
        "WHERE json_extract(j.value, '$.createdAt') >= ? " +
        "AND json_extract(j.value, '$.modelId') IS NOT NULL " +
        "AND json_extract(j.value, '$.modelId') != '' " +
        "GROUP BY day, modelId",
)

suspend fun MessageNodeDAO.getTrendByModel(startDate: String): List<DayModelUsage> =
    getTrendByModelRaw(SimpleSQLiteQuery(TREND_BY_MODEL_SQL.sql, arrayOf(startDate)))

// 模型使用率：按消息 modelId 分组统计消息数与 token 消耗，由高到低。
// 与趋势一致只取最近一年，避免全表 json_each 展开拖慢统计页首屏。
suspend fun MessageNodeDAO.getModelUsage(startDate: String): List<ModelUsageEntry> =
    getModelUsageRaw(
        SimpleSQLiteQuery(
            "SELECT json_extract(j.value, '$.modelId') AS modelId, " +
                "COUNT(*) AS count, " +
                "COALESCE(SUM(" +
                "  CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER) + " +
                "  CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)" +
                "), 0) AS tokens " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.modelId') IS NOT NULL " +
                "AND json_extract(j.value, '$.modelId') != '' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY modelId ORDER BY count DESC",
            arrayOf(startDate)
        )
    )

// 助手使用率：消息 JOIN 会话取 assistant_id 分组统计，由高到低
suspend fun MessageNodeDAO.getAssistantUsage(startDate: String): List<AssistantUsageEntry> =
    getAssistantUsageRaw(
        SimpleSQLiteQuery(
            "SELECT c.assistant_id AS assistantId, " +
                "COUNT(*) AS count, " +
                "COALESCE(SUM(" +
                "  CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER) + " +
                "  CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)" +
                "), 0) AS tokens " +
                "FROM message_node mn " +
                "JOIN conversationentity c ON mn.conversation_id = c.id " +
                "CROSS JOIN json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY c.assistant_id ORDER BY count DESC",
            arrayOf(startDate)
        )
    )

/** 消息落库时的模型展示名快照（modelId -> 名字） */
data class ModelNameSnapshot(val modelId: String = "", val modelName: String = "")

// 模型名快照：消息生成时快照了模型展示名，模型从配置删除/更换后统计页仍可据此显示真实名称。
// 与用量查询同窗口（1 年），同一 modelId 取任意一条快照即可（同一模型名一般稳定）。
suspend fun MessageNodeDAO.getModelNameSnapshots(startDate: String): List<ModelNameSnapshot> =
    getModelNameSnapshotsRaw(
        SimpleSQLiteQuery(
            "SELECT json_extract(j.value, '$.modelId') AS modelId, " +
                "MAX(json_extract(j.value, '$.modelName')) AS modelName " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.modelId') IS NOT NULL " +
                "AND json_extract(j.value, '$.modelId') != '' " +
                "AND json_extract(j.value, '$.modelName') IS NOT NULL " +
                "AND json_extract(j.value, '$.modelName') != '' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY modelId",
            arrayOf(startDate)
        )
    )

