package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_cards")
data class KnowledgeCardEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "concept")
    val concept: String,

    @ColumnInfo(name = "explanation", defaultValue = "")
    val explanation: String = "",

    @ColumnInfo(name = "memory_aid", defaultValue = "")
    val memoryAid: String = "",

    @ColumnInfo(name = "subject")
    val subject: String, // "politics" / "mechanics"

    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]", // JSON: List<String>

    @ColumnInfo(name = "source_conversation_id", defaultValue = "")
    val sourceConversationId: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_reviewed_at")
    val lastReviewedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "review_count")
    val reviewCount: Int = 0,

    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false,
)