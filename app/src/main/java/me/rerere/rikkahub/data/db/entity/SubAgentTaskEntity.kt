package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 子代理任务历史持久化表。
 *
 * 存储 [me.rerere.rikkahub.data.ai.subagent.SubAgentTask] 的完整 JSON 快照，
 * 让母代理聊天气泡里的 spawn/await 工具调用在进程重启后仍可跳转到详情页查看历史。
 * 设计对齐聊天消息落库：生成过程内存态实时更新，状态变化时写库，重启后从库恢复。
 */
@Entity(
    tableName = "subagent_task",
    indices = [Index("parent_conv")],
)
data class SubAgentTaskEntity(
    @PrimaryKey
    @ColumnInfo("task_id")
    val taskId: String,
    @ColumnInfo("parent_conv")
    val parentConv: String,
    @ColumnInfo("agent_id")
    val agentId: String,
    val status: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    /** 整个 SubAgentTask 的 JSON 快照（含 messages/steps/streamText/usage 等） */
    @ColumnInfo("task_json")
    val taskJson: String,
)
