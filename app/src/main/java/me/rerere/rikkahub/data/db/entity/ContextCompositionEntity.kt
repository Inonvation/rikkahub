package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每会话一行、七个整型的上下文构成快照（见 [me.rerere.rikkahub.data.ai.ContextComposition]）。
 *
 * 进程级 store 在 app 重启/软件更新后清空，落库保证新会话的构成明细（系统提示词 /
 * 系统工具 / MCP / 技能 / 消息的 token 占比）在重启后依旧可恢复展示，而不是回落到
 * 无工具的兜底估算。随会话删除联动清理（见 ConversationRepository.deleteConversation）。
 */
@Entity(tableName = "context_composition")
data class ContextCompositionEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("system_tokens")
    val systemTokens: Int,
    @ColumnInfo("builtin_tool_tokens")
    val builtinToolTokens: Int,
    @ColumnInfo("mcp_tool_tokens")
    val mcpToolTokens: Int,
    @ColumnInfo("skill_tool_tokens")
    val skillToolTokens: Int,
    @ColumnInfo("message_tokens")
    val messageTokens: Int,
    /** 快照落库时间（毫秒），备用（如未来按会话做多版本保留） */
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)