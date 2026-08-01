package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "pronunciation", defaultValue = "")
    val pronunciation: String = "",

    @ColumnInfo(name = "translations", defaultValue = "[]")
    val translations: String = "[]", // JSON: List<Translation>

    @ColumnInfo(name = "examples", defaultValue = "[]")
    val examples: String = "[]", // JSON: List<Example>

    @ColumnInfo(name = "mnemonic", defaultValue = "")
    val mnemonic: String = "",

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