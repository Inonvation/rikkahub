package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 翻译历史记录。
 *
 * 语言以 locale language tag（如 zh-CN / en / es-ES）存储而不是存展示名，
 * 这样界面语言切换后仍能按当前系统语言展示语言名。
 * sourceLanguage 为 null 表示当时源语言是「自动检测」。
 */
@Entity(
    tableName = "translation_history",
    indices = [
        Index(value = ["created_at"])
    ]
)
data class TranslationRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_text")
    val sourceText: String,
    @ColumnInfo("translated_text")
    val translatedText: String,
    @ColumnInfo("source_language")
    val sourceLanguage: String?,
    @ColumnInfo("target_language")
    val targetLanguage: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
