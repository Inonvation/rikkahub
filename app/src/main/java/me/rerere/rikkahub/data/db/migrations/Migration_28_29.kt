package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_28_29"

val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(28, 29)
        try {
            // Small-to-Big 检索：父 chunk 大小（0=禁用）
            db.execSQL("ALTER TABLE knowledge_base ADD COLUMN parent_chunk_size INTEGER DEFAULT 0 NOT NULL")

            // Small-to-Big 检索：子 chunk 关联的父 chunk ID
            db.execSQL("ALTER TABLE knowledge_chunk ADD COLUMN parent_chunk_id TEXT DEFAULT NULL")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}