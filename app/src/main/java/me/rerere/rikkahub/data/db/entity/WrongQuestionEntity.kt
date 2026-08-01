package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wrong_questions")
data class WrongQuestionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "question")
    val question: String,

    @ColumnInfo(name = "answer", defaultValue = "")
    val answer: String = "",

    @ColumnInfo(name = "solution", defaultValue = "")
    val solution: String = "",

    @ColumnInfo(name = "knowledge_points", defaultValue = "[]")
    val knowledgePoints: String = "[]", // JSON: List<String>

    @ColumnInfo(name = "subject")
    val subject: String, // "math" / "mechanics"

    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]", // JSON: List<String>

    @ColumnInfo(name = "image_paths", defaultValue = "[]")
    val imagePaths: String = "[]", // JSON: List<String>

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