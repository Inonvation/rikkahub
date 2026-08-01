package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "study_notes")
data class NoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content", defaultValue = "")
    val content: String = "",

    @ColumnInfo(name = "category")
    val category: String, // 作文模板/好句积累/语法笔记/解题思路/公式定理/论述框架/时政热点/背诵要点/机构图解/公式推导/真题解析

    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]", // JSON: List<String>

    @ColumnInfo(name = "source_assistant_id", defaultValue = "")
    val sourceAssistantId: String = "",

    @ColumnInfo(name = "source_conversation_id", defaultValue = "")
    val sourceConversationId: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false,
)