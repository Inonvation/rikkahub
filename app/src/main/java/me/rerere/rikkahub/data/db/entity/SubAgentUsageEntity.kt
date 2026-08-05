package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subagent_task_usage",
    indices = [Index("conversation_id")],
)
data class SubAgentUsageEntity(
    @PrimaryKey
    @ColumnInfo("task_id")
    val taskId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("agent_id")
    val agentId: String,
    /** 实际计费模型 id（任务继承主模型时为 null） */
    @ColumnInfo("model_id")
    val modelId: String? = null,
    val status: String,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long,
    /** 本次任务写入缓存的 token 数（cache write，如 Anthropic 的 cache_creation_input_tokens）。
     *  缓存命中率分母需剔除这部分；旧数据无该列时为 0。 */
    @ColumnInfo("cache_write_tokens")
    val cacheWriteTokens: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
