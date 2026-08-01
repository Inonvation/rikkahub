package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_29_30"

val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(29, 30)
        try {
            // 生词面板
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS vocabulary (
                    id TEXT PRIMARY KEY NOT NULL,
                    word TEXT NOT NULL,
                    pronunciation TEXT DEFAULT '' NOT NULL,
                    translations TEXT DEFAULT '[]' NOT NULL,
                    examples TEXT DEFAULT '[]' NOT NULL,
                    mnemonic TEXT DEFAULT '' NOT NULL,
                    tags TEXT DEFAULT '[]' NOT NULL,
                    source_conversation_id TEXT DEFAULT '' NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_reviewed_at INTEGER NOT NULL,
                    review_count INTEGER DEFAULT 0 NOT NULL
                )
            """.trimIndent())

            // 错题本
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS wrong_questions (
                    id TEXT PRIMARY KEY NOT NULL,
                    question TEXT NOT NULL,
                    answer TEXT DEFAULT '' NOT NULL,
                    solution TEXT DEFAULT '' NOT NULL,
                    knowledge_points TEXT DEFAULT '[]' NOT NULL,
                    subject TEXT NOT NULL,
                    tags TEXT DEFAULT '[]' NOT NULL,
                    image_paths TEXT DEFAULT '[]' NOT NULL,
                    source_conversation_id TEXT DEFAULT '' NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_reviewed_at INTEGER NOT NULL,
                    review_count INTEGER DEFAULT 0 NOT NULL
                )
            """.trimIndent())

            // 知识点卡片
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS knowledge_cards (
                    id TEXT PRIMARY KEY NOT NULL,
                    concept TEXT NOT NULL,
                    explanation TEXT DEFAULT '' NOT NULL,
                    memory_aid TEXT DEFAULT '' NOT NULL,
                    subject TEXT NOT NULL,
                    tags TEXT DEFAULT '[]' NOT NULL,
                    source_conversation_id TEXT DEFAULT '' NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_reviewed_at INTEGER NOT NULL,
                    review_count INTEGER DEFAULT 0 NOT NULL
                )
            """.trimIndent())

            // 笔记
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS study_notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT DEFAULT '' NOT NULL,
                    category TEXT NOT NULL,
                    tags TEXT DEFAULT '[]' NOT NULL,
                    source_assistant_id TEXT DEFAULT '' NOT NULL,
                    source_conversation_id TEXT DEFAULT '' NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}